"""
Local HTTP/WebSocket bridge: phone ACUS (adb forward) → browser preview.

Endpoints on 127.0.0.1:<proxyPort>:
  GET /stream.info          — JSON metadata (HTTP-compatible)
  GET /stream.video         — raw I420 frames (HTTP-compatible), only when
                              the phone negotiated YUV. Canonical alias of
                              the older /stream.yuv endpoint.
  GET /stream.mjpeg         — multipart JPEG frames, only when the phone
                              negotiated JPEG compression.
  GET /acus/ws              — WebSocket: text JSON meta, then binary frames
                              (either YUV or JPEG depending on the
                              negotiated protocol).
"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import select
import socket
import struct
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Callable
from urllib.parse import urlparse

from . import acus_driver
from .adb_tools import AdbClient
from .env_utils import load_root_env

LogFn = Callable[[str], None]

STATE_DIR = Path("/tmp/androidcamera-weblive")
FRME_MAGIC = b"FRME"
_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
ENV_MAX_RETRIES = "ACUS_MAX_RETRIES"

_lock = threading.Lock()
_proxies: dict[tuple[str, int], "AcusPreviewProxy"] = {}


def acus_max_retries() -> int:
    """Max ACUS reconnect attempts after errors (required in repo-root .env)."""
    load_root_env()
    raw = os.environ.get(ENV_MAX_RETRIES, "").strip()
    if not raw:
        raise RuntimeError(
            f"{ENV_MAX_RETRIES} mancante o vuoto nel file .env della root del monorepo"
        )
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError(
            f"{ENV_MAX_RETRIES} non valido nel .env: {raw!r} (intero >= 0 richiesto)"
        ) from exc
    if value < 0:
        raise RuntimeError(
            f"{ENV_MAX_RETRIES} non valido nel .env: {value} (intero >= 0 richiesto)"
        )
    return value


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    return acus_driver._recv_exact(sock, n)


def _read_handshake(sock: socket.socket) -> dict:
    """Read the ACUS Protocol v1 handshake.

    After the name the phone sends exactly the number of trailing bytes the
    flags claim (0 / 1) — never a fixed pair. We honor a field only when its
    flag bit is set; for raw YUV the phone sends nothing and we default the
    value (jpeg_quality=0).
    """
    prefix = _recv_exact(sock, 15)
    if prefix[:4] != acus_driver.ACUS_MAGIC:
        raise ConnectionError("not an ACUS stream")
    version = prefix[4]
    if version != acus_driver.ACUS_VERSION:
        raise ConnectionError(f"unsupported ACUS version {version}")
    flags = prefix[5]
    width, height, fps, rotation = struct.unpack(">HHHH", prefix[6:14])
    name_len = prefix[14]
    name = (
        _recv_exact(sock, name_len).decode("utf-8", errors="replace")
        if name_len
        else "AndroidCamera"
    )

    # Trailing bytes are FLAG-DRIVEN (0/1 depending on flags):
    #   FLAG_JPEG → 1 byte  jpeg_quality
    #   none      → 0 bytes
    n_extra = 1 if (flags & acus_driver.ACUS_FLAG_JPEG) else 0
    extras = _recv_exact(sock, n_extra) if n_extra else b""

    jpeg_supported = bool(flags & acus_driver.ACUS_FLAG_JPEG)
    jpeg_quality = extras[0] if jpeg_supported else 0
    if jpeg_supported and not (10 <= jpeg_quality <= 100):
        raise ConnectionError(f"invalid ACUS jpegQuality={jpeg_quality}")

    if width <= 0 or height <= 0:
        raise ConnectionError("invalid ACUS size")
    return {
        "deviceName": name.strip() or "AndroidCamera",
        "width": width,
        "height": height,
        "outputWidth": width,
        "outputHeight": height,
        "fps": int(fps or 30),
        "rotation": int(rotation),
        "pixelFormat": "jpeg" if jpeg_supported else "yuv420p",
        "ready": True,
        "transport": "acus",
        "path": "/stream.video",
        "format": "JPEG" if jpeg_supported else "YUV",
        "protocolVersion": acus_driver.ACUS_VERSION,
        "jpegSupported": jpeg_supported,
        "jpegQuality": jpeg_quality,
        # Encoder backend (HW vs SW) is sourced from the most recent JPEG
        # frame's tag; the handshake itself does not carry it. Initialize
        # to None so the GUI can show "Detecting…" until the first frame
        # arrives.
        "jpegEncoder": None,
    }


def _read_frame(sock: socket.socket) -> tuple[int, bytes]:
    hdr = _recv_exact(sock, 12)
    if hdr[:4] != FRME_MAGIC:
        raise ConnectionError("bad FRME magic")
    seq, size = struct.unpack(">II", hdr[4:12])
    # JPEG frames are smaller than YUV (typically a few KB to ~30KB), so we
    # accept a wide range while still rejecting nonsense from a buggy phone.
    if size <= 0 or size > 32 * 1024 * 1024:
        raise ConnectionError(f"invalid frame size {size}")
    return seq, _recv_exact(sock, size)


@dataclass
class _HubState:
    info: dict
    frame: bytes | None = None
    sequence: int = -1
    generation: int = 0


class _AcusHub(threading.Thread):
    """Background reader: one ACUS TCP client, latest-frame fan-out."""

    def __init__(self, serial: str, phone_port: int, log: LogFn) -> None:
        super().__init__(daemon=True, name=f"acus-hub-{serial}-{phone_port}")
        self.serial = serial
        self.phone_port = phone_port
        self.log = log
        self._stop = threading.Event()
        self._cond = threading.Condition()
        self._state: _HubState | None = None
        self._error: str | None = None
        self._sock: socket.socket | None = None

    def stop(self) -> None:
        self._stop.set()
        sock = self._sock
        if sock is not None:
            try:
                sock.close()
            except OSError:
                pass

    def wait_info(self, timeout: float = 12.0, *, need_frame: bool = False) -> dict:
        deadline = time.time() + timeout
        with self._cond:
            while True:
                if self._state is not None:
                    if not need_frame or self._state.frame is not None:
                        return dict(self._state.info)
                # Hub thread ended without a live session → fail.
                if not self.is_alive() and not self._stop.is_set():
                    break
                if self._stop.is_set() and self._state is None:
                    break
                remaining = deadline - time.time()
                if remaining <= 0:
                    break
                self._cond.wait(timeout=remaining)
            if self._state is not None and (
                not need_frame or self._state.frame is not None
            ):
                return dict(self._state.info)
            raise RuntimeError(self._error or "ACUS hub timeout")

    def snapshot(self) -> tuple[dict, bytes, int] | None:
        with self._cond:
            if self._state is None or self._state.frame is None:
                return None
            return dict(self._state.info), self._state.frame, self._state.generation

    def wait_frame(self, after_gen: int, timeout: float = 2.0) -> tuple[dict, bytes, int] | None:
        deadline = time.time() + timeout
        with self._cond:
            while True:
                if self._state is not None and self._state.frame is not None:
                    if self._state.generation != after_gen:
                        return (
                            dict(self._state.info),
                            self._state.frame,
                            self._state.generation,
                        )
                remaining = deadline - time.time()
                if remaining <= 0 or self._stop.is_set():
                    return None
                self._cond.wait(timeout=remaining)

    def run(self) -> None:
        client = AdbClient()
        max_retries = acus_max_retries()
        failures = 0
        try:
            while not self._stop.is_set():
                sock: socket.socket | None = None
                try:
                    # Clear any stale forward (e.g. leftover after reboot).
                    try:
                        client.forward_remove(self.serial, self.phone_port)
                    except Exception:
                        pass
                    if not client.forward(self.serial, self.phone_port):
                        raise RuntimeError("adb forward failed")
                    sock = socket.create_connection(
                        ("127.0.0.1", self.phone_port), timeout=3.0
                    )
                    sock.settimeout(5.0)
                    self._sock = sock
                    info = _read_handshake(sock)
                    failures = 0  # successful session resets the retry budget
                    with self._cond:
                        self._state = _HubState(info=info)
                        self._error = None
                        self._cond.notify_all()
                    self.log(
                        f"ACUS preview hub: {info['deviceName']} "
                        f"{info['width']}x{info['height']}@{info['fps']} "
                        f"({self.serial}:{self.phone_port})"
                    )
                    expected = int(info["width"]) * int(info["height"]) * 3 // 2
                    is_jpeg = info.get("pixelFormat") == "jpeg" or info.get("format") == "JPEG"
                    while not self._stop.is_set():
                        seq, payload = _read_frame(sock)
                        # JPEG frames have variable size matching compressed output;
                        # only enforce fixed size when streaming raw I420.
                        if not is_jpeg and len(payload) != expected:
                            # Resolution/fps changed on the phone → re-handshake.
                            raise ConnectionError(
                                f"frame size {len(payload)} != handshake "
                                f"{info['width']}x{info['height']} ({expected} bytes)"
                            )
                        with self._cond:
                            assert self._state is not None
                            self._state.frame = payload
                            self._state.sequence = seq
                            self._state.generation += 1
                            self._state.info["ready"] = True
                            self._cond.notify_all()
                except Exception as exc:  # noqa: BLE001
                    with self._cond:
                        self._error = str(exc)
                        # Drop stale handshake so callers cannot reuse old WxH@fps.
                        self._state = None
                        self._cond.notify_all()
                    if self._stop.is_set():
                        break
                    failures += 1
                    if failures > max_retries:
                        msg = (
                            f"ACUS preview hub: stop dopo {max_retries} retry "
                            f"({ENV_MAX_RETRIES}={max_retries}): {exc}"
                        )
                        self.log(msg)
                        with self._cond:
                            self._error = msg
                            self._cond.notify_all()
                        break
                    self.log(
                        f"ACUS preview hub reconnect "
                        f"{failures}/{max_retries}: {exc}"
                    )
                    time.sleep(0.8)
                finally:
                    self._sock = None
                    if sock is not None:
                        try:
                            sock.close()
                        except OSError:
                            pass
        finally:
            with self._cond:
                self._state = None
                self._cond.notify_all()


def _ws_accept_key(key: str) -> str:
    digest = hashlib.sha1((key.strip() + _GUID).encode("ascii")).digest()
    return base64.b64encode(digest).decode("ascii")


def _ws_send(sock: socket.socket, payload: bytes, *, opcode: int) -> None:
    header = bytearray()
    header.append(0x80 | (opcode & 0x0F))
    n = len(payload)
    if n < 126:
        header.append(n)
    elif n < 65536:
        header.append(126)
        header.extend(struct.pack(">H", n))
    else:
        header.append(127)
        header.extend(struct.pack(">Q", n))
    sock.sendall(header + payload)


def _ws_recv_message(
    sock: socket.socket, *, timeout: float = 0.0
) -> tuple[int, bytes] | None:
    """Read one WebSocket data frame (client→server, masked).

    Returns None if nothing is ready within ``timeout`` (default: non-blocking poll).
    Close frames return ``("close", payload)``.
    """
    rlist, _, _ = select.select([sock], [], [], timeout)
    if not rlist:
        return None
    hdr = sock.recv(2)
    if not hdr or len(hdr) < 2:
        return ("close", b"")  # type: ignore[return-value]
    opcode = hdr[0] & 0x0F
    masked = (hdr[1] & 0x80) != 0
    length = hdr[1] & 0x7F
    if length == 126:
        length = struct.unpack(">H", _recv_exact(sock, 2))[0]
    elif length == 127:
        length = struct.unpack(">Q", _recv_exact(sock, 8))[0]
    mask = _recv_exact(sock, 4) if masked else b""
    data = _recv_exact(sock, length) if length else b""
    if masked and mask:
        data = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
    if opcode == 0x8:
        return ("close", data)  # type: ignore[return-value]
    if opcode == 0x9:  # ping → pong
        _ws_send(sock, data, opcode=0xA)
        return None
    return opcode, data


class AcusPreviewProxy:
    def __init__(self, serial: str, phone_port: int, log: LogFn | None = None) -> None:
        self.serial = serial
        self.phone_port = phone_port
        self.log = log or (lambda _m: None)
        self.hub = _AcusHub(serial, phone_port, self.log)
        self.httpd: ThreadingHTTPServer | None = None
        self.proxy_port = 0
        self._thread: threading.Thread | None = None

    @property
    def origin(self) -> str:
        return f"http://127.0.0.1:{self.proxy_port}"

    @property
    def ws_url(self) -> str:
        return f"ws://127.0.0.1:{self.proxy_port}/acus/ws"

    def start(self) -> None:
        self.hub.start()
        # The phone ACUS port can only serve ONE client at a time.
        # When the C driver (acus_driver) is already holding the
        # connection for the same phone, our socket.connect() may be
        # accepted by adb but never receive a handshake from the phone.
        # We use a short wait so the user gets a fast failure; the GUI
        # falls back to reading frames from the existing /dev/videoN
        # node (see InAppPreviewWindow._prepare_proxy).
        self.hub.wait_info(timeout=1.5, need_frame=True)

        hub = self.hub
        log = self.log
        proxy_self = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt: str, *args) -> None:  # noqa: A003
                return

            def _cors(self) -> None:
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
                self.send_header("Access-Control-Allow-Headers", "*")

            def do_OPTIONS(self) -> None:  # noqa: N802
                self.send_response(204)
                self._cors()
                self.end_headers()

            def do_GET(self) -> None:  # noqa: N802
                path = urlparse(self.path).path
                if path in ("/acus/ws", "/ws"):
                    self._handle_ws()
                    return
                if path in ("/stream.info", "/info"):
                    self._handle_info()
                    return
                if path in ("/stream.yuv", "/stream.video", "/stream", "/cam.yuv", "/cam.video", "/video"):
                    self._handle_yuv()
                    return
                if path in ("/stream.mjpeg", "/mjpeg"):
                    self._handle_mjpeg()
                    return
                if path in ("/", "/index"):
                    body = (
                        b"AndroidCamera ACUS preview proxy. "
                        b"Use /stream.info, /stream.video, /stream.mjpeg or /acus/ws\n"
                    )
                    self.send_response(200)
                    self._cors()
                    self.send_header("Content-Type", "text/plain; charset=utf-8")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                    return
                self.send_error(404)

            def _handle_info(self) -> None:
                try:
                    info = hub.wait_info(timeout=8.0, need_frame=True)
                except RuntimeError as exc:
                    self.send_error(503, str(exc))
                    return
                info = dict(info)
                snap = hub.snapshot()
                info["ready"] = snap is not None
                body = json.dumps(info).encode("utf-8")
                self.send_response(200)
                self._cors()
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(body)

            def _handle_yuv(self) -> None:
                try:
                    hub.wait_info(timeout=8.0)
                except RuntimeError as exc:
                    self.send_error(503, str(exc))
                    return
                self.send_response(200)
                self._cors()
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Cache-Control", "no-cache, no-store")
                self.send_header("Connection", "keep-alive")
                self.end_headers()
                gen = -1
                try:
                    while not hub._stop.is_set():
                        got = hub.wait_frame(gen, timeout=2.0)
                        if got is None:
                            continue
                        _info, frame, gen = got
                        self.wfile.write(frame)
                        self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, OSError):
                    return

            def _handle_mjpeg(self) -> None:
                """
                Multipart JPEG stream for browsers that cannot consume raw I420
                (the Web Live Preview falls back to <img src="/stream.mjpeg">).

                The Android side is publishing either raw YUV frames or JPEG
                frames depending on the negotiated format. When the phone is
                streaming raw I420 we compress each frame to JPEG here so the
                browser still has something to render; phone-side JPEG
                compression already gives near-zero overhead for ACUS over USB.
                """
                try:
                    info = hub.wait_info(timeout=8.0, need_frame=True)
                except RuntimeError as exc:
                    self.send_error(503, str(exc))
                    return

                from .preview_jpeg_encoder import encode_i420_as_jpeg
                phone_native_jpeg = str(info.get("format") or "YUV").upper() == "JPEG"

                self.send_response(200)
                self._cors()
                self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
                self.send_header("Cache-Control", "no-cache, no-store")
                self.send_header("Connection", "keep-alive")
                self.end_headers()
                gen = -1
                try:
                    while not hub._stop.is_set():
                        got = hub.wait_frame(gen, timeout=2.0)
                        if got is None:
                            continue
                        _info, frame, gen = got
                        data = frame if phone_native_jpeg else encode_i420_as_jpeg(
                            payload=frame,
                            width=int(_info.get("width") or 0),
                            height=int(_info.get("height") or 0),
                            quality=int(_info.get("jpegQuality") or 75),
                        )
                        if data is None:
                            continue
                        header = (
                            f"--frame\r\n"
                            f"Content-Type: image/jpeg\r\n"
                            f"Content-Length: {len(data)}\r\n"
                            f"\r\n"
                        ).encode("ascii")
                        self.wfile.write(header)
                        self.wfile.write(data)
                        self.wfile.write(b"\r\n")
                        self.wfile.flush()
                except (BrokenPipeError, ConnectionResetError, OSError):
                    return

            def _handle_ws(self) -> None:
                key = self.headers.get("Sec-WebSocket-Key")
                if not key or self.headers.get("Upgrade", "").lower() != "websocket":
                    self.send_error(400, "WebSocket upgrade required")
                    return
                try:
                    info = hub.wait_info(timeout=8.0)
                except RuntimeError as exc:
                    self.send_error(503, str(exc))
                    return

                accept = _ws_accept_key(key)
                self.send_response(101, "Switching Protocols")
                self.send_header("Upgrade", "websocket")
                self.send_header("Connection", "Upgrade")
                self.send_header("Sec-WebSocket-Accept", accept)
                self._cors()
                self.end_headers()

                sock = self.connection
                # Non-blocking polls for client control frames; frame wait is on the hub.
                sock.settimeout(5.0)
                last_meta_key: tuple[int, int, int] | None = None

                def send_meta(src: dict) -> tuple[int, int, int]:
                    meta = dict(src)
                    meta["protocol"] = "acus"
                    meta["proxy"] = proxy_self.origin
                    _ws_send(sock, json.dumps(meta).encode("utf-8"), opcode=0x1)
                    return (
                        int(meta.get("width") or 0),
                        int(meta.get("height") or 0),
                        int(meta.get("fps") or 0),
                    )

                try:
                    last_meta_key = send_meta(info)
                    gen = -1
                    while not hub._stop.is_set():
                        # Drain client control frames in a non-blocking pass so
                        # pings/pongs and close frames are processed promptly
                        # even when no new ACUS frame has arrived. The previous
                        # implementation only drained BEFORE wait_frame(), which
                        # left ping frames unanswered for up to 1.0 s and made
                        # the browser kill the socket with 0 frames rendered.
                        while True:
                            msg = _ws_recv_message(sock, timeout=0.0)
                            if msg is None:
                                break
                            if msg[0] == "close":
                                return
                        got = hub.wait_frame(gen, timeout=0.2)
                        if got is None:
                            # No frame this tick — keep draining so the
                            # browser-side keepalive timer stays happy.
                            while True:
                                msg = _ws_recv_message(sock, timeout=0.0)
                                if msg is None:
                                    break
                                if msg[0] == "close":
                                    return
                            continue
                        _info, frame, gen = got
                        meta_key = (
                            int(_info.get("width") or 0),
                            int(_info.get("height") or 0),
                            int(_info.get("fps") or 0),
                        )
                        if meta_key != last_meta_key:
                            last_meta_key = send_meta(_info)
                        _ws_send(sock, frame, opcode=0x2)  # binary I420
                except (BrokenPipeError, ConnectionResetError, OSError, ConnectionError):
                    return

        # Bind ephemeral port on localhost
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.proxy_port = int(self.httpd.server_address[1])
        self._thread = threading.Thread(
            target=self.httpd.serve_forever,
            kwargs={"poll_interval": 0.3},
            daemon=True,
            name=f"acus-proxy-http-{self.proxy_port}",
        )
        self._thread.start()
        STATE_DIR.mkdir(parents=True, exist_ok=True)
        state_file = STATE_DIR / f"acus-proxy-{self.serial}-{self.phone_port}.json"
        state_file.write_text(
            json.dumps(
                {
                    "serial": self.serial,
                    "phonePort": self.phone_port,
                    "proxyPort": self.proxy_port,
                    "origin": self.origin,
                    "ws": self.ws_url,
                    "pid": os.getpid(),
                }
            ),
            encoding="utf-8",
        )
        self.log(f"ACUS preview proxy su {self.origin} (ws {self.ws_url})")

    def stop(self) -> None:
        self.hub.stop()
        if self.httpd is not None:
            self.httpd.shutdown()
            self.httpd.server_close()
            self.httpd = None


class HttpPreviewProxy:
    """
    Lightweight HTTP forward proxy for HTTP phones (USB Tethering / Wi-Fi).

    The in-app preview cannot fetch the phone's HTTP stream directly due
    to CORS restrictions, so this proxy runs on 127.0.0.1 and forwards
    requests to the phone while adding the necessary CORS headers.
    """

    def __init__(
        self,
        phone_host: str,
        phone_port: int,
        log: LogFn | None = None,
    ) -> None:
        self.phone_host = phone_host
        self.phone_port = int(phone_port)
        self.log = log or (lambda _m: None)
        self.httpd: ThreadingHTTPServer | None = None
        self.proxy_port = 0
        self._thread: threading.Thread | None = None

    @property
    def origin(self) -> str:
        return f"http://127.0.0.1:{self.proxy_port}"

    def start(self) -> None:
        hub = self  # reference for the handler closure
        log = self.log

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt: str, *args) -> None:  # noqa: N802
                return

            def _cors(self) -> None:
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header(
                    "Access-Control-Allow-Methods", "GET, OPTIONS"
                )
                self.send_header("Access-Control-Allow-Headers", "*")

            def do_OPTIONS(self) -> None:  # noqa: N802
                self.send_response(204)
                self._cors()
                self.end_headers()

            def do_GET(self) -> None:  # noqa: N802
                path = urlparse(self.path).path
                # Map the proxy paths to the phone's HTTP endpoints.
                phone_url = (
                    f"http://{hub.phone_host}:{hub.phone_port}{path}"
                )
                try:
                    resp = urllib.request.urlopen(
                        phone_url, timeout=10.0
                    )
                    content_type = resp.headers.get(
                        "Content-Type", "application/octet-stream"
                    )
                    self.send_response(200)
                    self._cors()
                    self.send_header(
                        "Content-Type", content_type
                    )
                    self.send_header(
                        "Cache-Control", "no-cache, no-store"
                    )
                    self.send_header(
                        "Connection", "keep-alive"
                    )
                    self.end_headers()
                    # Stream chunks directly to the client so
                    # MJPEG/YUV continuous streams work without
                    # buffering the entire body in memory.
                    while True:
                        chunk = resp.read(8192)
                        if not chunk:
                            break
                        self.wfile.write(chunk)
                    self.wfile.flush()
                except Exception as exc:  # noqa: BLE001
                    log(
                        f"HTTP proxy error forwarding {path}: {exc}"
                    )
                    try:
                        self.send_error(502, str(exc))
                    except Exception:
                        pass

        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.proxy_port = int(self.httpd.server_address[1])
        self._thread = threading.Thread(
            target=self.httpd.serve_forever,
            kwargs={"poll_interval": 0.3},
            daemon=True,
            name=f"http-proxy-{self.proxy_port}",
        )
        self._thread.start()
        self.log(
            f"HTTP forward proxy su {self.origin} "
            f"→ {self.phone_host}:{self.phone_port}"
        )

    def stop(self) -> None:
        if self.httpd is not None:
            self.httpd.shutdown()
            self.httpd.server_close()
            self.httpd = None


def ensure_proxy(
    serial: str,
    phone_port: int,
    log: LogFn | None = None,
    *,
    refresh: bool = False,
    phone_host: str | None = None,
) -> AcusPreviewProxy | HttpPreviewProxy:
    """Return a running proxy for (serial, phone_port), starting one if needed.

    When ``phone_host`` is provided the phone uses HTTP transport and
    the returned HttpPreviewProxy simply forwards HTTP requests.
    When ``phone_host`` is None (ACUS/USB Device), an AcusPreviewProxy
    is used (binary ACUS protocol over adb forward).

    When ``refresh`` is True (e.g. every Live Preview open), tear down
    any existing proxy so the hub re-handshakes and picks up new WxH /
    fps from the phone after a resolution change.
    """
    log = log or (lambda _m: None)
    port = int(phone_port)
    if phone_host is not None:
        # HTTP phone: forward requests via a lightweight HTTP proxy so
        # the in-app preview client can reach the stream without CORS
        # issues (browser fetch() requires same-origin or CORS headers).
        key = ("http", phone_host, port)
        with _lock:
            existing = _proxies.get(key)
            if existing is not None and not refresh:
                if existing.httpd is not None:
                    return existing
                existing.stop()
            if existing is not None:
                _proxies.pop(key, None)
            proxy = HttpPreviewProxy(phone_host, port, log=log)
            proxy.start()
            _proxies[key] = proxy
            return proxy

    # ACUS phone: binary protocol proxy over adb forward.
    key = (serial, port)
    with _lock:
        existing = _proxies.get(key)
        if existing is not None:
            reusable = (
                not refresh
                and existing.httpd is not None
                and existing.hub.is_alive()
            )
            if reusable:
                try:
                    existing.hub.wait_info(timeout=2.0, need_frame=True)
                    return existing
                except RuntimeError:
                    pass
            existing.stop()
            _proxies.pop(key, None)
        proxy = AcusPreviewProxy(serial, port, log=log)
        proxy.start()
        _proxies[key] = proxy
        return proxy
