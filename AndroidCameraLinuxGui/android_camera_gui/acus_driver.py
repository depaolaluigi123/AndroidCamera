"""Locate and invoke the AndroidCameraUsbDriver (acus_driver)."""

from __future__ import annotations

import os
import shutil
import socket
import struct
import subprocess
import time
from pathlib import Path

_REPO_ROOT = Path(__file__).resolve().parents[2]
_DRIVER_DIR = _REPO_ROOT / "AndroidCameraUsbDriver"
_DRIVER_BIN = _DRIVER_DIR / "bin" / "acus_driver"

ACUS_MAGIC = b"ACUS"
# ACUS Protocol v1. The version byte is always 1.
ACUS_VERSION = 1

# Flag bits in the ACUS handshake flags byte.
ACUS_FLAG_JPEG = 0x01


def driver_path() -> Path | None:
    env = os.environ.get("ACUS_DRIVER")
    if env:
        p = Path(env)
        if p.is_file() and os.access(p, os.X_OK):
            return p
    if _DRIVER_BIN.is_file() and os.access(_DRIVER_BIN, os.X_OK):
        return _DRIVER_BIN
    which = shutil.which("acus_driver")
    if which:
        return Path(which)
    return None


def ensure_built(log=None) -> Path:
    """Return path to acus_driver, building it if necessary."""
    existing = driver_path()
    if existing is not None:
        return existing
    makefile = _DRIVER_DIR / "Makefile"
    if not makefile.is_file():
        raise RuntimeError(
            "AndroidCameraUsbDriver non trovato. Compila il driver in "
            f"{_DRIVER_DIR} (make)."
        )
    if log:
        log("Compilo AndroidCameraUsbDriver (acus_driver)…")
    proc = subprocess.run(
        ["make", "-C", str(_DRIVER_DIR)],
        capture_output=True,
        text=True,
        timeout=60,
    )
    if proc.returncode != 0:
        raise RuntimeError(
            "Compilazione acus_driver fallita.\n"
            + (proc.stderr or proc.stdout or "")
        )
    built = driver_path()
    if built is None:
        raise RuntimeError("acus_driver compilato ma non eseguibile")
    return built


def _recv_exact(sock: socket.socket, n: int) -> bytes:
    buf = bytearray()
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise ConnectionError("connection closed")
        buf.extend(chunk)
    return bytes(buf)


def probe(serial: str, port: int, timeout: float = 2.0) -> dict | None:
    """
    ACUS Protocol v1 handshake over adb forward (see ACUS-PROTOCOL.md). After
    the name the phone sends exactly the number of trailing bytes the flags
    claim (0 / 1) — never a fixed pair. We honor a field only when its
    flag bit is set; when the flag is clear the phone sends nothing and we
    default the value (jpeg_quality=0).

    Returns a probe dict (deviceName, width, height, fps, rotation, format,
    jpegQuality, protocol_version, jpeg_supported), or None when the phone
    is not reachable / not speaking the supported version.
    """
    from .adb_tools import AdbClient

    client = AdbClient()
    try:
        client.forward_remove(serial, port)
    except Exception:
        pass
    if not client.forward(serial, port):
        existing = client.list_forwards(serial)
        matching = [(hp, dp) for hp, dp in existing if dp == port]
        if not matching:
            return None
        host_port = matching[0][0]
    else:
        host_port = port

    attempt_timeout = min(timeout, 1.0)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", host_port), timeout=attempt_timeout) as sock:
                sock.settimeout(attempt_timeout)
                prefix = _recv_exact(sock, 15)
                if prefix[:4] != ACUS_MAGIC:
                    return None
                version = prefix[4]
                # Single supported version (v1).
                if version != ACUS_VERSION:
                    return None
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
                n_extra = 1 if (flags & ACUS_FLAG_JPEG) else 0
                extras = _recv_exact(sock, n_extra) if n_extra else b""

                jpeg_supported = bool(flags & ACUS_FLAG_JPEG)
                # Honor a field only when its flag is set; otherwise default.
                jpeg_quality = extras[0] if jpeg_supported else 0
                if jpeg_supported and not (10 <= jpeg_quality <= 100):
                    return None
                if width <= 0 or height <= 0:
                    return None
                pixel_format = "jpeg" if jpeg_supported else "yuv420p"
                return {
                    "deviceName": name.strip() or "AndroidCamera",
                    "width": width,
                    "height": height,
                    "outputWidth": width,
                    "outputHeight": height,
                    "fps": fps or 30,
                    "rotation": rotation,
                    "format": "JPEG" if jpeg_supported else "YUV",
                    "pixelFormat": pixel_format,
                    "ready": True,
                    "transport": "acus",
                    "protocol_version": ACUS_VERSION,
                    "jpeg_supported": jpeg_supported,
                    "jpegQuality": jpeg_quality,
                    # snake_case mirror of the same field (the C probe emits
                    # snake_case; keep both so either source parses cleanly).
                    "jpeg_quality": jpeg_quality,
                }
        except (OSError, ConnectionError, TimeoutError, struct.error):
            time.sleep(0.3)
            continue
    return None


def parse_acus_url(url: str) -> tuple[str, str, int] | None:
    """
    Parse acus://SERIAL@HOST:PORT → (serial, host, port).
    Also accepts acus://HOST:PORT (serial empty).
    """
    if not url.startswith("acus://"):
        return None
    rest = url[len("acus://") :]
    serial = ""
    if "@" in rest:
        serial, rest = rest.split("@", 1)
    if ":" not in rest:
        return None
    host, port_s = rest.rsplit(":", 1)
    try:
        port = int(port_s)
    except ValueError:
        return None
    return serial, host, port
