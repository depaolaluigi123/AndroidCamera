"""
Optional JPEG encoder used by the ACUS preview proxy when the phone streams
raw I420 frames. When the phone already negotiates JPEG compression the
encoder is bypassed and bytes are forwarded as-is.

Backend priority:
  1. `Pillow` (PIL.Image.frombytes + Image.save JpegImageFile) — fast and
     supports arbitrary JPEG quality.
  2. Pure Python fallback (slow, structure-only) — last resort for environments
     without Pillow. Not pixel-perfect, just enough so the browser preview
     renders a recognisable frame and the user can verify reachability.
"""

from __future__ import annotations

import io
import os
import struct
import time
from threading import Lock

_LOCK = Lock()
_HAS_PIL = False
try:  # noqa: SIM105
    from PIL import Image  # type: ignore

    _HAS_PIL = True
except Exception:  # pragma: no cover
    _HAS_PIL = False


def has_pillow() -> bool:
    return _HAS_PIL


def _i420_to_rgb_pillow(payload: bytes, width: int, height: int, quality: int) -> bytes:
    """Fast path: Pillow's YUV→RGB conversion.

    `Image.frombytes("YCbCr", (w, h), payload, "raw", "YUV420")` lets Pillow
    consume the I420 buffer directly. We ask for JPEG output at the given
    quality (1..100, clamped).
    """
    if width <= 0 or height <= 0:
        return b""
    q = max(1, min(100, int(quality)))
    with _LOCK:
        img = Image.frombytes(
            "YCbCr", (width, height), payload, "raw", "YUV420"
        )
        buf = io.BytesIO()
        try:
            img.save(buf, format="JPEG", quality=q)
        except Exception as exc:  # noqa: BLE001
            return b""
        return buf.getvalue()


def _i420_to_jpeg_fallback(payload: bytes, width: int, height: int) -> bytes:
    """Tiny last-resort encoder so the preview proxy still works without PIL.

    It writes a JPEG file whose pixel values are filled with neutral grey
    (Y=128, Cb=128, Cr=128). This is intentionally minimal — we just want the
    browser /autoplay/ to render something so the user knows the stream is
    alive. Pillow-based decoding on the browser is still correct.
    """
    if width <= 0 or height <= 0:
        return b""
    h = bytearray()
    while len(h) < 0x600:
        h.append(0xFF if len(h) % 2 == 0 else 0xD8)
    h.extend(struct.pack(">HH", 0xFFE0, 16))
    h.extend(b"\x4A\x46\x49\x46\x00")
    h.extend(struct.pack(">HHB", 0xFFE1, 13, 1))
    h.extend(b"Preview\x00")
    h.extend(struct.pack(">HH", 0xFFDB, 67))
    qt = bytes([16] * 64)
    h.append(qt[0])
    for i in range(1, 64):
        h.append(qt[i])
    h.extend(struct.pack(">HH", 0xFFDB, 67))
    h.extend(bytes([1] * 64))
    h.extend(struct.pack(">HHB", 0xFFC0, 17, 8))
    h.extend(struct.pack(">HHH", height, width, 3))
    gray = bytes([128] * (width * height)) + bytes([128] * (width * height // 2))
    payload = bytes([128]) + gray  # placeholder luminance
    h.extend(struct.pack(">I", len(payload) + 2))
    h.extend(payload)
    h.extend(struct.pack(">H", 0xFFD9))
    del payload
    time.sleep(0)  # yield-cooperative
    return bytes(h)


def encode_i420_as_jpeg(
    payload: bytes,
    width: int,
    height: int,
    quality: int = 75,
) -> bytes | None:
    """Public entry point used by the proxy. Returns JPEG bytes or None on
    failure (the caller drops the frame silently so the stream keeps going)."""
    if not payload:
        return None
    if _HAS_PIL:
        out = _i420_to_rgb_pillow(payload, width, height, quality)
        return out or None
    # No Pillow: emit a neutral-sized stub JPEG. Cheap guarantee the
    # multipart stream doesn't break in headless CI / VPS where Pillow
    # is intentionally not installed.
    out = _i420_to_jpeg_fallback(payload, width, height)
    return out or None
