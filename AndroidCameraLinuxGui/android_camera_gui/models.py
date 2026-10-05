from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class StreamInfo:
    """Live-stream metadata advertised by the Android app or the ACUS probe.

    Every caller is expected to populate stream_format / jpeg_quality /
    protocol_version. The fields below are NOT optional defaults — they are
    required.
    """

    device_name: str
    width: int
    height: int
    output_width: int
    output_height: int
    fps: int
    rotation: int
    # "stream.video" (canonical alias of the older "stream.yuv") for
    # raw I420, "stream.mjpeg" for JPEG over HTTP, or the bare resource
    # id (e.g. "acus") when the answer comes from the ACUS probe and
    # the consumer switches on `transport` instead of this URL.
    path: str
    # "yuv420p" or "jpeg".
    pixel_format: str
    # Configured (target) FPS — what the user picked in the app. Distinct from
    # `measured_fps` (live, fluctuating).
    configured_fps: int
    measured_fps: int
    # Transport-level stream format negotiated over the ACUS handshake.
    # Always one of "YUV" or "JPEG".
    stream_format: str
    # JPEG quality 10..100 — meaningful iff stream_format == "JPEG".
    jpeg_quality: int
    # ACUS protocol extensions filled in by either the ACUS probe or the HTTP
    # stream.info endpoint.
    jpeg_supported: bool
    protocol_version: int

    @property
    def display_fps(self) -> int:
        """FPS to show in the GUI tree: configured target, not measured."""
        return self.configured_fps

    @property
    def is_jpeg(self) -> bool:
        """True when the stream is JPEG-compressed (lower bandwidth, WiFi-friendly)."""
        return self.stream_format.upper() == "JPEG"

    @property
    def display_format(self) -> str:
        """Short human label for the streaming format."""
        if self.is_jpeg:
            return f"JPEG q{self.jpeg_quality}"
        return "YUV"


@dataclass
class PhoneDevice:
    """A phone with an active AndroidCamera Webcam service."""

    serial: str
    model: str
    host: str
    port: int
    info: StreamInfo
    adb_transport: str = "usb"
    # "http" = USB Tethering / IP modes; "acus" = USB Device binary driver path.
    transport: str = "http"
    # Human-readable label for the connection type shown in the GUI endpoint column.
    # e.g. "USB Tethering", "Wi-Fi 192.168.1.72", "USB Device (ACUS)".
    endpoint_label: str = ""

    @property
    def label(self) -> str:
        return sanitize_label(self.info.device_name)

    @property
    def display_name(self) -> str:
        """Human-friendly name used by the GUI tree, log lines and dialogs.

        Falls back to the sanitised label when the phone reports an empty
        `deviceName` (we never want to show a blank cell).
        """
        raw = (self.info.device_name or "").strip()
        return raw or self.label

    @property
    def stream_url(self) -> str:
        """Best URL to fetch frames from this phone.

        - ACUS transport: binary `acus://` URL, consumed by the C driver.
        - HTTP YUV: `/stream.video` (canonical alias of the older `/stream.yuv`;
          the Kotlin app serves the same I420 bytes under both names so the URL
          can stay stable across format switches and HTTP-mode handovers).
        - HTTP JPEG (with or without batching): `/stream.mjpeg`
        """
        if self.transport == "acus":
            return f"acus://{self.serial}@{self.host}:{self.port}"
        if self.info.is_jpeg:
            return f"http://{self.host}:{self.port}/stream.mjpeg"
        return f"http://{self.host}:{self.port}/stream.video"


@dataclass
class BridgeStatus:
    label: str
    video_nr: int
    device_path: str
    running: bool
    resolution: str = ""
    fps: int = 0


def sanitize_label(raw: str) -> str:
    import re

    label = re.sub(r"[^A-Za-z0-9 _.-]+", "", (raw or "").strip())
    label = re.sub(r"\s+", "_", label).strip("._-")
    return (label or "AndroidCamera")[:31]


# Practical upper bound for /dev/videoN on Linux (videodev minor space).
_MAX_VIDEO_NR = 255
# Lowest /dev/videoN index the allocator is allowed to hand out. The
# allocator starts from here and walks upward until it finds a free
# slot, so on machines without Iriun / DroidCam / OBS virtual cameras
# the first phone gets the first free node (typically /dev/video0).
START_VIDEO_NR = 0


def live_video_nrs() -> set[int]:
    """Numbers already claimed by any V4L2 node on this machine."""
    from pathlib import Path

    taken: set[int] = set()
    base = Path("/sys/class/video4linux")
    if not base.is_dir():
        return taken
    for entry in base.glob("video*"):
        suffix = entry.name.removeprefix("video")
        if suffix.isdigit():
            taken.add(int(suffix))
    return taken


def allocate_video_nr(label: str, used: dict[str, int]) -> int:
    """
    Pick a free /dev/videoN for this phone label.

    Reuses a stable assignment from *used* when present; otherwise takes the
    lowest free index in ``START_VIDEO_NR..255`` that is neither in *used*
    nor already live under /sys/class/video4linux (so we never collide
    with the laptop webcam, other loopbacks, etc.).

    We start from ``/dev/video`` :data:`START_VIDEO_NR` (default 1) so
    the always-present ``/dev/video0`` is left alone — on most machines
    ``/dev/video0`` is the built-in webcam, and on machines that ship
    Iriun / DroidCam / OBS virtual cameras ``/dev/video1`` is often the
    first virtual sink. By leaving ``/dev/video0`` untouched we keep the
    OS-level default camera available to other applications, even when
    the phone is the only thing plugged in via USB debug.
    """
    if label in used:
        return used[label]
    taken = set(used.values()) | live_video_nrs()
    for n in range(START_VIDEO_NR, _MAX_VIDEO_NR + 1):
        if n not in taken:
            return n
    raise RuntimeError(
        f"Nessun /dev/videoN libero tra {START_VIDEO_NR} e "
        f"{_MAX_VIDEO_NR} (tutti i nodi V4L2 risultano occupati)."
    )
