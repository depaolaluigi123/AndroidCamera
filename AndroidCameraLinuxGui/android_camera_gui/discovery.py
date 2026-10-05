from __future__ import annotations

import concurrent.futures
import http.client
import json
import os
import time
import urllib.error
import urllib.request
from typing import Callable, Protocol

from . import acus_driver
from .adb_tools import AdbClient, AdbDevice
from .env_utils import load_root_env
from .models import PhoneDevice, StreamInfo

DEFAULT_PORTS = (2743,)
DEFAULT_SUBNET_SCAN_MAX_CONCURRENT = 32
ENV_SUBNET_SCAN_MAX_CONCURRENT = "SUBNET_SCAN_MAX_CONCURRENT"
LogFn = Callable[[str], None]

# Cached value of SUBNET_SCAN_MAX_CONCURRENT, resolved once from .env on first
# use and reused on every subsequent call so the file is opened only once.
_subnet_scan_max_concurrent: int | None = None


def _resolve_subnet_scan_max_concurrent() -> int:
    """Resolve ``SUBNET_SCAN_MAX_CONCURRENT`` from .env **once** and cache it.

    Falls back to :data:`DEFAULT_SUBNET_SCAN_MAX_CONCURRENT` when the .env file
    is missing, the value is unset/empty, or it is not a positive integer.
    On invalid values a warning is logged to stderr.
    """
    global _subnet_scan_max_concurrent
    if _subnet_scan_max_concurrent is not None:
        return _subnet_scan_max_concurrent

    def _fallback(reason: str) -> int:
        print(
            f"{ENV_SUBNET_SCAN_MAX_CONCURRENT} non valido nel .env: {reason}; "
            f"uso il default {DEFAULT_SUBNET_SCAN_MAX_CONCURRENT}",
            flush=True,
        )
        return DEFAULT_SUBNET_SCAN_MAX_CONCURRENT

    try:
        load_root_env()
    except FileNotFoundError:
        # .env missing — fall back to the default. We MUST NOT crash the GUI
        # at import time just because the env file is absent.
        _subnet_scan_max_concurrent = DEFAULT_SUBNET_SCAN_MAX_CONCURRENT
        return _subnet_scan_max_concurrent

    raw = os.environ.get(ENV_SUBNET_SCAN_MAX_CONCURRENT, "").strip()
    if not raw:
        _subnet_scan_max_concurrent = DEFAULT_SUBNET_SCAN_MAX_CONCURRENT
        return _subnet_scan_max_concurrent
    try:
        value = int(raw)
    except ValueError:
        result = _fallback(f"expected integer, got {raw!r}")
    else:
        if value < 1:
            result = _fallback(f"expected integer >= 1, got {value}")
        else:
            result = value

    _subnet_scan_max_concurrent = result
    return _subnet_scan_max_concurrent


class LogStrings(Protocol):
    """Protocol for log strings used in discovery."""

    log_probe_http_tether: str
    log_probe_acus: str
    log_probe_http_multi: str
    log_endpoint_unreachable_acus: str
    log_endpoint_unreachable_http: str
    log_endpoint_reachable_acus: str
    log_endpoint_reachable_http: str
    log_adb_error: str
    log_scanning_device: str
    log_service_inactive: str
    log_found_usb_device: str
    # Additional log strings for enhanced debugging
    log_scan_start: str
    log_scan_end: str
    log_adb_devices_found: str
    log_authorized_devices: str
    log_no_authorized: str
    log_scanning_endpoint: str
    log_http_endpoints_found: str
    log_acus_detected: str
    log_no_endpoint_found: str
    log_acus_invalid_data: str


def fetch_stream_info(host: str, port: int, timeout: float = 0.3) -> StreamInfo | None:
    """Fetch /stream.info from the phone and parse it into a StreamInfo.

    The current ACUS protocol is mandatory: every required field must be
    present. Older servers are intentionally NOT supported (the GUI will
    treat that as "service not active", pushing the user to upgrade the
    Android app).
    """
    url = f"http://{host}:{port}/stream.info"
    try:
        with urllib.request.urlopen(url, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
        data = json.loads(raw)
    except (
        urllib.error.URLError,
        TimeoutError,
        json.JSONDecodeError,
        ValueError,
        OSError,
        http.client.HTTPException,
    ):
        # HTTPException covers LineTooLong when an ACUS binary server is hit
        # via adb forward instead of the HTTP /stream.info endpoint.
        return None

    # Mandatory fields — missing key => server is too old.
    required = (
        "ready", "width", "height",
        "format", "jpegQuality",
        "configuredFps", "deviceName",
        "outputWidth", "outputHeight", "rotation", "fps",
    )
    if any(key not in data for key in required):
        return None

    if not bool(data.get("ready")):
        return None
    width = int(data.get("width") or 0)
    height = int(data.get("height") or 0)
    out_w = int(data.get("outputWidth") or width)
    out_h = int(data.get("outputHeight") or height)
    if width <= 0 or height <= 0:
        return None

    raw_format = str(data.get("format") or "").upper()
    if raw_format not in ("YUV", "JPEG"):
        return None
    jpeg_quality = int(data.get("jpegQuality") or 0)
    # JPEG quality is only validated when the stream actually is JPEG; the
    # Kotlin app reports jpegQuality=0 (well, the configured value) for YUV,
    # and that must not disqualify a perfectly good YUV stream.
    if raw_format == "JPEG" and (jpeg_quality < 10 or jpeg_quality > 100):
        return None
    if raw_format == "YUV":
        jpeg_quality = 0

    name = str(data.get("deviceName") or "").strip() or "AndroidCamera"
    fps_cfg = int(data.get("configuredFps") or 30)
    fps_meas = int(data.get("fps") or fps_cfg)
    path = "/stream.mjpeg" if raw_format == "JPEG" else "/stream.video"
    pixel = "jpeg" if raw_format == "JPEG" else "yuv420p"
    return StreamInfo(
        device_name=name,
        width=width,
        height=height,
        output_width=out_w,
        output_height=out_h,
        fps=fps_cfg,
        rotation=int(data.get("rotation") or 0),
        path=path,
        pixel_format=pixel,
        configured_fps=fps_cfg,
        measured_fps=fps_meas,
        stream_format=raw_format,
        jpeg_quality=jpeg_quality,
        jpeg_supported=(raw_format == "JPEG"),
        protocol_version=1,
    )


def _info_from_acus_probe(data: dict) -> StreamInfo | None:
    """Parse the JSON emitted by `./acus_driver --probe` (ACUS handshake).

    Mandatory fields: width/height > 0, deviceName, fps, pixelFormat,
    jpegQuality (or jpeg_quality), protocol_version, jpeg_supported.
    Any missing/garbage field makes the probe unusable.
    """
    if not isinstance(data, dict):
        return None
    # The C driver probe emits snake_case keys (jpeg_quality, jpeg_supported,
    # protocol_version) while the HTTP app emits camelCase (jpegQuality).
    # Accept either shape.
    def pick(camel: str, snake: str, default=0):
        if camel in data:
            return data[camel]
        if snake in data:
            return data[snake]
        return default

    required = (
        "width", "height", "deviceName",
        "fps", "pixelFormat",
        "protocol_version", "outputWidth", "outputHeight", "rotation",
    )
    if any(key not in data for key in required):
        return None
    # At least one spelling of jpeg-quality must be present so we can populate
    # StreamInfo; the C driver uses snake_case, the HTTP app camel.
    if "jpegQuality" not in data and "jpeg_quality" not in data:
        return None
    width = int(data.get("width") or 0)
    height = int(data.get("height") or 0)
    if width <= 0 or height <= 0:
        return None

    jpeg_supported = bool(pick("jpegSupported", "jpeg_supported", 0))
    jpeg_quality = int(pick("jpegQuality", "jpeg_quality", 0) or 0)
    # JPEG quality is only meaningful when the phone negotiated JPEG; for a
    # raw YUV probe the C driver reports jpegQuality=0 and that is expected.
    if jpeg_supported and (jpeg_quality < 10 or jpeg_quality > 100):
        return None
    if not jpeg_supported and jpeg_quality != 0:
        return None

    pixel = str(data.get("pixelFormat") or "").lower()
    if pixel in ("jpeg", "mjpeg") or pixel.startswith("jpeg"):
        # The C driver reports "mjpeg" when it will feed the v4l2loopback node
        # as V4L2_PIX_FMT_MJPEG; that is the JPEG transport on the wire.
        stream_format = "JPEG"
        path = "acus"
        pixel_norm = "jpeg"
    elif pixel == "yuv420p":
        stream_format = "YUV"
        path = "acus"
        pixel_norm = "yuv420p"
    else:
        return None

    # The wire format flag must agree with pixelFormat — a server claiming
    # jpeg_supported=true while emitting a yuv420p stream (or vice-versa) is
    # not protocol-compliant.
    if jpeg_supported != (stream_format == "JPEG"):
        return None

    name = str(data.get("deviceName") or "").strip() or "AndroidCamera"
    fps_cfg = int(data.get("fps") or 30)
    return StreamInfo(
        device_name=name,
        width=width,
        height=height,
        output_width=int(data.get("outputWidth") or width),
        output_height=int(data.get("outputHeight") or height),
        fps=fps_cfg,
        rotation=int(data.get("rotation") or 0),
        path=path,
        pixel_format=pixel_norm,
        configured_fps=fps_cfg,
        measured_fps=fps_cfg,
        stream_format=stream_format,
        jpeg_quality=jpeg_quality,
        jpeg_supported=jpeg_supported,
        protocol_version=int(data.get("protocol_version") or 1),
    )


def _endpoint_label_for_host(host: str) -> str:
    """Return a human-readable connection label for an HTTP endpoint IP."""
    if host in ("127.0.0.1", "localhost"):
        return "adb forward (127.0.0.1)"
    if _is_tethering_ip(host):
        return f"USB Tethering ({host})"
    return f"Wi-Fi ({host})"


def _probe_host(
    serial: str,
    model: str,
    host: str,
    ports: tuple[int, ...],
) -> PhoneDevice | None:
    for port in ports:
        info = fetch_stream_info(host, port)
        if info is None:
            continue
        return PhoneDevice(
            serial=serial,
            model=model,
            host=host,
            port=port,
            info=info,
            transport="http",
            endpoint_label=_endpoint_label_for_host(host),
        )
    return None


def _probe_usb_device(
    serial: str,
    model: str,
    ports: tuple[int, ...],
    log: LogFn,
    strings: LogStrings | None = None,
    *,
    timeout: float = 3.0,
) -> PhoneDevice | None:
    """Discover phones streaming binary ACUS via USB debugging (no HTTP).

    ACUS (Android Camera USB) is a binary protocol used when the phone is in
    USB Device mode. This function uses adb forward to expose the phone's
    ACUS port on localhost, then performs a binary handshake to verify the stream.

    Args:
        serial: ADB serial number of the device
        model: Device model name
        ports: Ports to probe (only 2743)
        log: Logging function
        strings: Internationalization strings (falls back to English if None)
        timeout: Total timeout for ACUS probe (default 3.0 seconds)

    Returns:
        PhoneDevice if ACUS stream found, None otherwise
    """
    # Get the appropriate strings or fall back to English defaults
    s = strings
    if s is None:
        from .i18n import en
        s = en.STRINGS

    for port in ports:
        data = acus_driver.probe(serial, port, timeout=timeout)
        if data is None:
            continue
        info = _info_from_acus_probe(data)
        if info is None:
            log(f"    {s.log_acus_invalid_data.format(port=port)}")
            continue
        log(f"    {s.log_acus_detected.format(port=port, name=info.device_name, width=info.output_width, height=info.output_height, fps=info.fps)}")
        # Surface the negotiated options so users can see they're getting JPEG
        # compression when their phone supports it.
        try:
            ext = getattr(s, "log_format_negotiated", None)
            if ext and info.jpeg_supported:
                log(ext.format(
                    format=info.stream_format,
                    quality=info.jpeg_quality,
                ))
        except Exception:  # logging is best-effort
            pass
        return PhoneDevice(
            serial=serial,
            model=model,
            host="127.0.0.1",
            port=port,
            info=info,
            transport="acus",
            endpoint_label="USB Device (ACUS)",
        )
    return None


def _preferred_ports(hint_port: int | None, ports: tuple[int, ...]) -> tuple[int, ...]:
    ordered: list[int] = []
    if hint_port and hint_port > 0:
        ordered.append(hint_port)
    for port in ports:
        if port not in ordered:
            ordered.append(port)
    return tuple(ordered)


# _TETHERING_PREFIXES = (
#     "192.168.42.", "192.168.43.", "192.168.44.",
#     "192.168.37.", "192.168.76.", "192.168.95.",
# )


def _is_tethering_ip(host: str) -> bool:
    """Return True when *host* looks like an Android USB‑tethering subnet IP."""
    #return host.startswith(_TETHERING_PREFIXES)
    return not host.startswith("192.168.1.")


def _sort_http_hosts(hosts: list[str]) -> list[str]:
    return sorted(
        hosts,
        key=lambda ip: (0 if _is_tethering_ip(ip) else 1, ip),
    )


def _probe_http_via_adb_forward(
    client: AdbClient,
    serial: str,
    model: str,
    ports: tuple[int, ...],
    strings: LogStrings | None = None,
) -> PhoneDevice | None:
    """
    Reach the phone HTTP server over USB debug (adb forward → 127.0.0.1).

    Useful when Wi‑Fi/LAN to the phone is firewalled but USB debugging works.
    Distinguishes from ACUS: /stream.info must answer as HTTP JSON.
    """
    for port in ports:
        if not client.forward(serial, port):
            continue
        info = fetch_stream_info("127.0.0.1", port, timeout=0.5)
        if info is None:
            continue
        return PhoneDevice(
            serial=serial,
            model=model,
            host="127.0.0.1",
            port=port,
            info=info,
            transport="http",
            endpoint_label="adb forward (127.0.0.1)",
        )
    return None


def _probe_http_via_adb_forward_multi(
    client: AdbClient,
    serial: str,
    model: str,
    ports: tuple[int, ...],
    strings: LogStrings | None = None,
) -> list[PhoneDevice]:
    """adb‑forward variant that returns all reachable ports on 127.0.0.1."""
    result: list[PhoneDevice] = []
    for port in ports:
        if not client.forward(serial, port):
            continue
        info = fetch_stream_info("127.0.0.1", port, timeout=0.5)
        if info is None:
            continue
        result.append(PhoneDevice(
            serial=serial,
            model=model,
            host="127.0.0.1",
            port=port,
            info=info,
            transport="http",
            endpoint_label="adb forward (127.0.0.1)",
        ))
    return result


def _probe_http_multi_on_device(
    client: AdbClient,
    serial: str,
    model: str,
    ports: tuple[int, ...],
    *,
    hint_host: str | None = None,
    enable_tethering: bool = False,
    attempts: int = 2,
    try_adb_forward: bool = True,
    strings: LogStrings | None = None,
) -> list[PhoneDevice]:
    """Like _probe_http_on_device but returns all reachable IPs (not just first)."""
    # Get the appropriate strings or fall back to English defaults
    s = strings
    if s is None:
        from .i18n import en
        s = en.STRINGS

    if enable_tethering:
        client.enable_usb_tethering(serial)
        time.sleep(0.5)

    found: list[PhoneDevice] = []
    seen: set[tuple[str, int]] = set()

    for attempt in range(max(1, attempts)):
        hosts: list[str] = []
        if hint_host and hint_host not in ("127.0.0.1", "localhost"):
            hosts.append(hint_host)
        for ip in client.list_ipv4(serial):
            if ip not in hosts:
                hosts.append(ip)
        hosts = _sort_http_hosts(hosts)
        if not hosts and attempt < attempts - 1:
            time.sleep(0.2)
            continue
        for host in hosts:
            phone = _probe_host(serial, model, host, ports)
            if phone is not None and (phone.host, phone.port) not in seen:
                seen.add((phone.host, phone.port))
                found.append(phone)
        if attempt < attempts - 1 and not found:
            time.sleep(0.2)

    if found or not try_adb_forward:
        return found

    # adb-forward fallback adds any port not already found via direct IP.
    for phone in _probe_http_via_adb_forward_multi(client, serial, model, ports, strings=s):
        if (phone.host, phone.port) not in seen:
            seen.add((phone.host, phone.port))
            found.append(phone)
    return found


def _probe_http_on_device(
    client: AdbClient,
    serial: str,
    model: str,
    ports: tuple[int, ...],
    *,
    hint_host: str | None = None,
    enable_tethering: bool = False,
    attempts: int = 2,
    try_adb_forward: bool = True,
    strings: LogStrings | None = None,
) -> PhoneDevice | None:
    # Get the appropriate strings or fall back to English defaults
    s = strings
    if s is None:
        from .i18n import en
        s = en.STRINGS

    if enable_tethering:
        client.enable_usb_tethering(serial)
        time.sleep(0.5)

    for attempt in range(max(1, attempts)):
        hosts: list[str] = []
        if hint_host and hint_host not in ("127.0.0.1", "localhost"):
            hosts.append(hint_host)
        for ip in client.list_ipv4(serial):
            if ip not in hosts:
                hosts.append(ip)
        hosts = _sort_http_hosts(hosts)
        if not hosts and attempt < attempts - 1:
            time.sleep(0.2)
            continue
        for host in hosts:
            phone = _probe_host(serial, model, host, ports)
            if phone is not None:
                return phone
        if attempt < attempts - 1:
            time.sleep(0.2)

    if try_adb_forward:
        return _probe_http_via_adb_forward(client, serial, model, ports)
    return None


def _pick_active_phone(
    client: AdbClient,
    serial: str,
    model: str,
    ports: tuple[int, ...],
    *,
    hint_host: str | None = None,
    prefer: str | None = None,
    enable_tethering_fallback: bool = True,
    log: LogFn,
    strings: LogStrings | None = None,
) -> PhoneDevice | None:
    """
    Probe ACUS (USB Device) and HTTP (USB Tethering / IP).

    Order prioritizes ACUS first when available, then falls back to HTTP.
    This ensures USB Device mode (ACUS) works without interference from
    tethering mode switching.

    Default order (Refresh):
      1) ACUS driver handshake via adb forward (USB Device mode)
      2) USB Tethering + HTTP on phone IPs (and adb-forward HTTP fallback)
    If *prefer* is "http", HTTP is tried first then ACUS.
    If *prefer* is "acus", ACUS is tried first (default behavior).
    """
    # Get the appropriate strings or fall back to Italian defaults
    s = strings
    if s is None:
        from .i18n import en
        s = en.STRINGS

    def probe_http(*, with_tether: bool, attempts: int) -> PhoneDevice | None:
        if with_tether:
            log(s.log_probe_http_tether)
        return _probe_http_on_device(
            client,
            serial,
            model,
            ports,
            hint_host=hint_host,
            enable_tethering=with_tether,
            attempts=attempts,
        )

    def probe_acus() -> PhoneDevice | None:
        log(s.log_probe_acus)
        return _probe_usb_device(serial, model, ports, log, timeout=1.0)

    # Default behavior: ACUS first (works via USB debug without WiFi)
    if prefer in (None, "acus"):
        acus = probe_acus()
        if acus is not None:
            return acus
        # ACUS not active, try HTTP with tethering fallback
        http = probe_http(with_tether=enable_tethering_fallback, attempts=2 if enable_tethering_fallback else 1)
        return http

    # HTTP-prefer: USB Tethering (HTTP) before ACUS.
    http = probe_http(
        with_tether=enable_tethering_fallback,
        attempts=2 if enable_tethering_fallback else 1,
    )
    if http is not None:
        return http

    return probe_acus()


def _pick_active_phones_multi(
    client: AdbClient,
    serial: str,
    model: str,
    ports: tuple[int, ...],
    *,
    hint_host: str | None = None,
    enable_tethering_fallback: bool = True,
    log: LogFn,
    strings: LogStrings | None = None,
) -> list[PhoneDevice]:
    """
    Multi-endpoint discovery for one ADB device.

    Returns every reachable HTTP endpoint (USB Tethering, LAN Wi-Fi, adb forward)
    as a separate PhoneDevice entry so the GUI shows each one as a row the user
    can pick. When no HTTP endpoint is reachable, falls back to ACUS.

    Discovery order (changed to prioritize ACUS):
      1) ACUS (USB Device mode via adb forward) - works without WiFi/tethering
      2) USB Tethering + HTTP on phone IPs (and adb-forward HTTP fallback)

    ACUS is tried first to avoid disrupting USB Device mode when probing HTTP
    would force-enable USB Tethering, which breaks the ACUS connection.
    """
    # Get the appropriate strings or fall back to English defaults
    s = strings
    if s is None:
        from .i18n import en
        s = en.STRINGS

    log(s.log_scanning_endpoint.format(model=model, serial=serial))

    # First, try ACUS (USB Device mode) - does NOT enable tethering
    # This is critical: HTTP probing with tethering would break ACUS mode.
    log(s.log_probe_acus)
    acus = _probe_usb_device(serial, model, ports, log, strings=s, timeout=1.0)
    if acus is not None:
        log(s.log_acus_detected.format(
            port=acus.port, name=acus.display_name,
            width=acus.info.output_width, height=acus.info.output_height, fps=acus.info.fps
        ))
        return [acus]

    # ACUS not active, try HTTP endpoints (USB Tethering, Wi-Fi, adb forward)
    tether: bool = enable_tethering_fallback
    if tether:
        log(s.log_probe_http_multi)
    http_phones = _probe_http_multi_on_device(
        client,
        serial,
        model,
        ports,
        hint_host=hint_host,
        enable_tethering=tether,
        attempts=2 if tether else 1,
        strings=s,
    )
    if http_phones:
        log(s.log_http_endpoints_found.format(count=len(http_phones)))
        return http_phones

    log(s.log_no_endpoint_found.format(serial=serial))
    return []


def resolve_active_protocol(
    phone: PhoneDevice,
    adb: AdbClient | None = None,
    ports: tuple[int, ...] = DEFAULT_PORTS,
    log: LogFn | None = None,
    strings: LogStrings | None = None,
) -> PhoneDevice:
    """
    Re-probe the phone right now and return a PhoneDevice with the live protocol.

    The GUI tree may be stale after switching USB Device ↔ USB Tethering / IP
    without Refresh. Call this immediately before Enable for OBS.
    """
    # Get the appropriate strings or fall back to English defaults
    s = strings
    if s is None:
        from .i18n import en
        s = en.STRINGS

    log = log or (lambda _msg: None)
    client = adb or AdbClient()
    ports_t = _preferred_ports(phone.port, ports)

    log(
        f"Verifying protocol on {phone.model} ({phone.serial}) "
        f"(transport: {phone.transport})..."
    )

    live = _pick_active_phone(
        client,
        phone.serial,
        phone.model,
        ports_t,
        hint_host=phone.host,
        prefer=phone.transport if phone.transport in ("http", "acus") else None,
        enable_tethering_fallback=True,
        log=log,
        strings=s,
    )
    if live is None:
        raise RuntimeError(
            f"No active stream on «{phone.display_name}» ({phone.serial}).\n"
            "Start the service in the app (USB Tethering, USB Device or IP) and try again."
        )

    if live.transport == "acus":
        log(
            f"  active protocol: USB Device / ACUS "
            f"→ acus://{live.serial}@127.0.0.1:{live.port} "
            f"{live.info.output_width}x{live.info.output_height}@{live.info.fps}"
        )
    else:
        log(
            f"  active protocol: HTTP "
            f"→ {live.host}:{live.port} "
            f"{live.info.output_width}x{live.info.output_height}@{live.info.fps}"
        )
    return live


def verify_endpoint(
    phone: PhoneDevice,
    adb: AdbClient | None = None,
    log: LogFn | None = None,
    strings: LogStrings | None = None,
) -> PhoneDevice | None:
    """
    Verify if a specific endpoint is still reachable.

    This function checks ONLY the exact host:port of the given PhoneDevice,
    without falling back to other endpoints. Used when opening live preview
    to ensure the selected endpoint is still active.

    Returns the PhoneDevice with updated stream info if reachable, None otherwise.
    """
    # Get the appropriate strings or fall back to English defaults
    s = strings
    if s is None:
        from .i18n import en
        s = en.STRINGS

    log = log or (lambda _msg: None)
    client = adb or AdbClient()

    endpoint_str = f"{phone.host}:{phone.port}"
    log(f"Verifying endpoint {endpoint_str}…")

    if phone.transport == "acus":
        # For ACUS, try to probe the specific serial and port
        data = acus_driver.probe(phone.serial, phone.port, timeout=2.0)
        if data is None:
            log(s.log_endpoint_unreachable_acus.format(endpoint=endpoint_str))
            return None
        info = _info_from_acus_probe(data)
        if info is None:
            log(s.log_endpoint_unreachable_acus.format(endpoint=endpoint_str))
            return None
        log(s.log_endpoint_reachable_acus.format(endpoint=endpoint_str))
        return PhoneDevice(
            serial=phone.serial,
            model=phone.model,
            host=phone.host,
            port=phone.port,
            info=info,
            transport="acus",
            endpoint_label=phone.endpoint_label,
        )
    else:
        # For HTTP, try to fetch stream.info from the specific host:port
        info = fetch_stream_info(phone.host, phone.port, timeout=1.8)
        if info is None:
            log(s.log_endpoint_unreachable_http.format(endpoint=endpoint_str))
            return None
        log(s.log_endpoint_reachable_http.format(endpoint=endpoint_str))
        return PhoneDevice(
            serial=phone.serial,
            model=phone.model,
            host=phone.host,
            port=phone.port,
            info=info,
            transport="http",
            endpoint_label=phone.endpoint_label,
        )


def _scan_subnet_http(
    ports: tuple[int, ...],
    log: LogFn,
    strings: LogStrings | None = None,
    subnet_base: str = "192.168.1",
    max_concurrent: int | None = None,
) -> list[PhoneDevice]:
    """Scan a /24 LAN subnet in parallel on the given port(s) for HTTP endpoints.

    Probes every host from ``{subnet_base}.1`` through ``{subnet_base}.254``
    (skipping the network .0 and broadcast .255 addresses) concurrently using
    a thread pool. Each probe is a lightweight ``/stream.info`` HTTP GET with
    a 0.3 s timeout, so the entire /254 scan finishes in roughly
    ``ceil(254 / max_concurrent) * 0.3`` seconds.

    Returns one PhoneDevice per responding host (de-duplicated by host:port).
    Phones found here are NOT tied to an ADB serial — they are identified by
    a synthetic ``subnet-{host}`` serial so the GUI can still display and
    enable them.
    """
    s = strings
    if s is None:
        from .i18n import en
        s = en.STRINGS

    if max_concurrent is None:
        max_concurrent = _resolve_subnet_scan_max_concurrent()

    def _probe_one(octet: int) -> PhoneDevice | None:
        host = f"{subnet_base}.{octet}"
        for port in ports:
            info = fetch_stream_info(host, port, timeout=0.3)
            if info is not None:
                return PhoneDevice(
                    serial=f"subnet-{host}",
                    model=info.device_name,
                    host=host,
                    port=port,
                    info=info,
                    transport="http",
                    endpoint_label=f"Wi-Fi ({host})",
                )
        return None

    log(s.log_subnet_scan_start.format(subnet=f"{subnet_base}.0/24", port=ports[0] if ports else 2743))
    found: list[PhoneDevice] = []
    seen: set[tuple[str, int]] = set()

    # 254 hosts (.1 … .254) — skip .0 (network) and .255 (broadcast)
    octets = range(1, 255)
    with concurrent.futures.ThreadPoolExecutor(max_workers=max_concurrent) as pool:
        futures = {pool.submit(_probe_one, o): o for o in octets}
        for future in concurrent.futures.as_completed(futures):
            try:
                phone = future.result()
            except Exception as exc:  # noqa: BLE001
                log(f"  subnet probe error on .{futures[future]}: {exc}")
                continue
            if phone is not None and (phone.host, phone.port) not in seen:
                seen.add((phone.host, phone.port))
                found.append(phone)

    log(s.log_subnet_scan_end.format(count=len(found)))
    return found


def discover_phones(
    adb: AdbClient | None = None,
    ports: tuple[int, ...] = DEFAULT_PORTS,
    enable_tethering: bool = True,
    log: LogFn | None = None,
    strings: LogStrings | None = None,
) -> list[PhoneDevice]:
    """
    Find phones with an active AndroidCamera Webcam service.

    Returns one entry per reachable endpoint (USB Tethering IP, LAN Wi-Fi IP,
    adb forward, ACUS) so the GUI can show all available connection paths.
    The user can then pick which endpoint to enable for OBS.

    Discovery order:
    1. ACUS (USB Device mode via adb forward binary protocol) - works without WiFi
    2. USB Tethering + HTTP (on phone IPs, with adb-forward fallback)
    3. Subnet sweep of 192.168.1.0/24 on port 2743 (HTTP channel only)

    For ACUS detection, the function uses adb forward to expose the phone's
    ACUS port on localhost, then performs a binary handshake to verify the stream.
    ACUS is tried first to avoid disrupting USB Device mode when probing HTTP
    would force-enable USB Tethering.
    """
    # Get the appropriate strings or fall back to English defaults
    s = strings
    if s is None:
        from .i18n import en
        s = en.STRINGS

    log = log or (lambda _msg: None)
    client = adb or AdbClient()
    found: list[PhoneDevice] = []
    seen_endpoints: set[tuple[str, str, int]] = set()  # (serial, host, port)
    seen_host_ports: set[tuple[str, int]] = set()  # (host, port) for cross-dedup

    log(s.log_scan_start)
    try:
        devices = client.devices()
        log(s.log_adb_devices_found.format(count=len(devices)))
    except Exception as exc:  # noqa: BLE001
        log(s.log_adb_error.format(error=exc))
        devices = []

    authorized = [d for d in devices if d.state == "device"]
    if authorized:
        log(s.log_authorized_devices.format(count=len(authorized)))
        for device in authorized:
            log(s.log_scanning_device.format(model=device.model, serial=device.serial))

            phones = _pick_active_phones_multi(
                client,
                device.serial,
                device.model,
                ports,
                enable_tethering_fallback=(
                    enable_tethering and device.transport != "emulator"
                ),
                log=log,
                strings=s,
            )

            if not phones:
                log(s.log_service_inactive.format(serial=device.serial))
                continue

            for phone in phones:
                key = (phone.serial, phone.host, phone.port)
                if key in seen_endpoints:
                    continue
                seen_endpoints.add(key)
                seen_host_ports.add((phone.host, phone.port))
                found.append(phone)
                if phone.transport == "acus":
                    log(
                        s.log_found_usb_device.format(
                            name=phone.display_name,
                            width=phone.info.output_width,
                            height=phone.info.output_height,
                            fps=phone.info.display_fps,
                            serial=phone.serial,
                            port=phone.port,
                        )
                    )
                else:
                    log(
                        f"  found {phone.endpoint_label} «{phone.display_name}» "
                        f"{phone.info.output_width}x{phone.info.output_height}"
                        f"@{phone.info.display_fps} "
                        f"→ {phone.host}:{phone.port}"
                    )
    else:
        log(s.log_no_authorized)

    # Subnet sweep: probe every 192.168.1.x on port 2743 in parallel.
    # This catches phones on the LAN Wi-Fi network even when USB debugging
    # is unavailable, and runs concurrently with nothing else so it adds
    # minimal wall-clock time (~2-3 s with 32 workers at 0.3 s per probe).
    # Deduplication uses (host, port) so a phone already found via ADB at
    # 192.168.1.x is not shown a second time as a subnet entry.
    subnet_phones = _scan_subnet_http(
        ports, log=log, strings=s, subnet_base="192.168.1"
    )
    for phone in subnet_phones:
        if (phone.host, phone.port) in seen_host_ports:
            continue
        key = (phone.serial, phone.host, phone.port)
        if key in seen_endpoints:
            continue
        seen_endpoints.add(key)
        seen_host_ports.add((phone.host, phone.port))
        found.append(phone)
        log(
            f"  found subnet {phone.endpoint_label} «{phone.display_name}» "
            f"{phone.info.output_width}x{phone.info.output_height}"
            f"@{phone.info.display_fps} → {phone.host}:{phone.port}"
        )

    log(s.log_scan_end.format(count=len(found)))
    return found


def list_adb_overview(adb: AdbClient | None = None) -> list[AdbDevice]:
    client = adb or AdbClient()
    return client.devices()