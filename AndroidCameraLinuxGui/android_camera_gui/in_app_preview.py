"""
In-app live preview window.

Opens a Toplevel Tk window with a Canvas that displays the live camera stream
via MJPEG over the existing ACUS / HTTP preview proxy. The window is modal
(grab_set) so the main window cannot be interacted with while the preview is
open. Closing the window returns control to the main GUI.

The resolution and FPS shown under the video are measured live (frame count
over a rolling window) rather than the values negotiated at startup. They
update ~4 times per second.

The window uses Pillow's ImageTk so a Tkinter Canvas can display JPEG/PNG
data; Pillow is the only non-stdlib dependency and is optional — the preview
falls back to a text status when Pillow is not available.
"""

from __future__ import annotations

import subprocess
import threading
import time
import tkinter as tk
import urllib.error
import urllib.request
from collections import deque
from pathlib import Path
from tkinter import ttk
from typing import Callable

from .i18n import Strings
from .models import PhoneDevice
from . import acus_preview_proxy
from .discovery import verify_endpoint
from .bridge import BridgeManager

LogFn = Callable[[str], None]

try:  # Pillow optional dependency for the in-app preview canvas.
    from PIL import Image, ImageTk  # type: ignore
    _HAS_PIL = True
except Exception:  # pragma: no cover
    _HAS_PIL = False


def has_pillow() -> bool:
    """Return True when PIL.Image / ImageTk are importable right now.

    Pillow is an optional dependency: when it is not installed the GUI
    shows a banner asking the user to install it. ``run.sh`` installs
    Pillow automatically at every launch, but a user who started the
    GUI before installing Pillow would otherwise see the banner
    forever. Probing the import here (instead of caching the result at
    module-load time) means a user who installs Pillow and re-opens the
    preview window gets a working canvas immediately.
    """
    global _HAS_PIL
    if _HAS_PIL:
        return True
    try:
        from PIL import Image as _Image  # noqa: F401
        from PIL import ImageTk as _ImageTk  # noqa: F401
    except Exception:
        return False
    _HAS_PIL = True
    return True


class InAppPreviewWindow:
    """
    One-shot modal window showing the live camera feed for [phone].

    Lifecycle:
      - ``show(parent, phone, ...)`` blocks the caller until the window closes
        (uses ``wait_window``).
      - On close the background reader thread is joined and the proxy is
        released.

    The window shows the actual measured resolution and FPS, both updated on
    a timer (~250 ms tick). When the device's resolution / FPS change (the
    user tweaked them on the phone) the proxy re-handshakes and the labels
    refresh on the next tick.
    """

    REFRESH_MS = 250
    # Frames older than this are dropped from the rolling FPS window.
    FPS_WINDOW_S = 2.0

    def __init__(
        self,
        parent: tk.Tk,
        phone: PhoneDevice,
        strings: Strings,
        log: LogFn,
    ) -> None:
        self.parent = parent
        self.phone = phone
        self.s = strings
        self.log = log
        self._closed = False
        self._frame_count = 0
        self._frame_times: deque[float] = deque()
        self._last_width = 0
        self._last_height = 0
        self._proxy = None
        self._hub = None
        self._stream_url: str | None = None
        # V4L2 fallback: when the ACUS bridge is already running
        # (e.g. OBS is using the device) we read frames from the
        # existing /dev/videoN node instead of opening a second
        # ACUS connection to the phone.
        self._v4l2_device: str | None = None
        self._v4l2_width: int = 0
        self._v4l2_height: int = 0
        self._v4l2_fps: int = 0
        self._v4l2_jpeg: bool = False
        self._stop_reader = threading.Event()
        self._reader_thread: threading.Thread | None = None
        self._current_image: ImageTk.PhotoImage | None = None  # prevent GC
        self._placeholder_drawn = False
        # Latest pre-processed Pillow Image (already decoded, resized to
        # canvas size, in RGB mode). The worker thread overwrites this slot
        # on every frame; the Tk main thread reads it via ``_render_latest``.
        # A lock keeps the worker from racing with itself.
        self._latest_frame: Image.Image | None = None
        self._frame_lock = threading.Lock()
        # Tracks whether a draw is already queued on the main loop. When
        # the user has a fast phone and a slow display (or a large
        # window), multiple ``after(0, ...)`` callbacks can pile up
        # before Tk gets to drain them. The ``_pending_draw`` flag
        # collapses the backlog so we never queue more than one draw.
        self._pending_draw = False
        # Cached canvas dimensions queried by the worker so it does not
        # hit Tk from a non-main thread (which would crash on some
        # Tk builds). Updated on every main-thread tick.
        self._canvas_size: tuple[int, int] = (640, 480)
        # Canvas item id for the image on the canvas. Used with
        # ``itemconfig(image=...)`` to refresh the displayed frame.
        self._canvas_item: int | None = None

        self.win = tk.Toplevel(parent)
        self.win.title(self.s.in_app_preview_title.format(name=phone.display_name))
        self.win.minsize(640, 480)
        self.win.grab_set()
        self.win.protocol("WM_DELETE_WINDOW", self._on_close)
        # Make the window manager expose its standard title-bar controls
        # (the rectangle next to the X that toggles between windowed /
        # maximised / fullscreen on every desktop application). We do
        # NOT add a Tk button for fullscreen — the user wanted the OS
        # window controls to work as in any other program. ``resizable``
        # by itself only allows stretching; the fullscreen hint comes
        # from ``wm_attributes("-zoomed")`` / ``wm_state``.
        try:
            self.win.resizable(True, True)
        except tk.TclError:
            pass

        # The preview canvas resizes with the window; we keep the source
        # aspect ratio by drawing a centred image into the canvas.
        self.canvas = tk.Canvas(self.win, background="#000000", highlightthickness=0)
        self.canvas.pack(fill=tk.BOTH, expand=True, padx=10, pady=(10, 4))

        bar = ttk.Frame(self.win, padding=(10, 4, 10, 10))
        bar.pack(fill=tk.X)
        self.lbl_resolution = ttk.Label(bar, text=self.s.in_app_preview_resolution_pending)
        self.lbl_resolution.pack(side=tk.LEFT)
        self.lbl_fps = ttk.Label(bar, text=self.s.in_app_preview_fps_pending)
        self.lbl_fps.pack(side=tk.RIGHT)

        btn_row = ttk.Frame(self.win, padding=(10, 0, 10, 10))
        btn_row.pack(fill=tk.X)
        ttk.Button(btn_row, text=self.s.btn_close, command=self._on_close).pack(
            side=tk.RIGHT
        )

        # Make sure the proxy is running for the selected phone before we
        # open the reader thread, otherwise the first frames will be
        # metadata-only and we'll display "waiting".
        self._prepare_proxy()
        # Tick loop: refresh resolution / FPS labels even when no frames
        # arrive (so the user sees "waiting for stream" instead of frozen
        # stale text).
        self._tick()

    # ── public API ──────────────────────────────────────────────

    def show(self) -> None:
        """Block until the user closes the window."""
        self.parent.wait_window(self.win)

    # ── internals ────────────────────────────────────────────────

    def _prepare_proxy(self) -> None:
        # Resolve the live endpoint so the proxy hands back a fresh
        # StreamInfo on the first /stream.info call.
        live = verify_endpoint(
            self.phone, adb=None, log=self.log, strings=self.s
        )
        if live is None:
            self.log(
                self.s.log_endpoint_unreachable.format(
                    host=self.phone.host, port=self.phone.port
                )
            )
            return
        self.phone = live

        # When the phone is in ACUS (USB Device) mode and the
        # v4l2loopback bridge is already running (OBS or another
        # consumer has the /dev/videoN open), we must NOT open a
        # second ACUS connection to the phone — the phone accepts
        # only one client per port, so the hub would time out.
        # Instead we read frames from the already-running bridge's
        # /dev/videoN node via ffmpeg.
        bridge = BridgeManager(log=self.log)
        running, device_path = bridge.status_for(
            self.phone.info.device_name
        )
        if running and device_path and live.transport == "acus":
            self._hub = None
            self._stream_url = None
            self._v4l2_device = device_path
            self._v4l2_width = live.info.output_width
            self._v4l2_height = live.info.output_height
            self._v4l2_fps = live.info.display_fps
            self._v4l2_jpeg = live.info.is_jpeg
        elif live.transport == "acus":
            # No bridge running yet — try the ACUS proxy path.
            # This is the normal case when the user opens the
            # preview for the first time before enabling OBS.
            try:
                proxy = acus_preview_proxy.ensure_proxy(
                    live.serial,
                    live.port,
                    log=self.log,
                    refresh=True,
                )
                self._proxy = proxy
                self._hub = proxy.hub
            except RuntimeError as exc:
                # The ACUS hub could not connect (most likely
                # because the C driver bridge is already holding
                # the phone's port). Fall back to V4L2 capture
                # from the device node if it exists.
                self.log(
                    f"In-app preview ACUS proxy fallito: {exc}; "
                    "provando a catturare da /dev/videoN..."
                )
                device = bridge.find_device_by_label(
                    live.info.device_name
                )
                if device:
                    self._hub = None
                    self._stream_url = None
                    self._v4l2_device = device
                    self._v4l2_width = live.info.output_width
                    self._v4l2_height = live.info.output_height
                    self._v4l2_fps = live.info.display_fps
                    self._v4l2_jpeg = live.info.is_jpeg
                else:
                    self.log(
                        "Nessun /dev/videoN trovato per "
                        f"{live.info.device_name}; preview non "
                        "disponibile."
                    )
                    return
        else:
            # HTTP transport: there is no push hub. The proxy just
            # forwards /stream.mjpeg (or /stream.video) requests; we poll
            # the MJPEG endpoint directly from a worker thread and decode
            # each part as a JPEG frame.
            proxy = acus_preview_proxy.ensure_proxy(
                live.host,
                live.port,
                log=self.log,
                refresh=True,
                phone_host=live.host,
            )
            self._proxy = proxy
            self._hub = None  # ACUS-only push hub; HTTP uses the reader
            self._stream_url = (
                f"{proxy.origin}/stream.mjpeg"
                if live.info.is_jpeg
                else f"{proxy.origin}/stream.video"
            )
            self._v4l2_device = None

        if (self._hub is not None or self._stream_url is not None or getattr(self, '_v4l2_device', None)) and not self._reader_thread:
            self._reader_thread = threading.Thread(
                target=self._reader_loop,
                name=f"in-app-preview-{getattr(live, 'serial', live.host)}",
                daemon=True,
            )
            self._reader_thread.start()

    def _reader_loop(self) -> None:
        """
        Pull the latest frame and push it onto Tk's main loop.

        Three paths:
          * ACUS hub: poll ``hub.snapshot()`` ~30 times per second.
          * HTTP: open ``/stream.mjpeg`` or ``/stream.video`` and pull
            frames directly.
          * V4L2 fallback: when the ACUS bridge is already running
            (e.g. OBS holds the device) we read frames from the
            existing /dev/videoN node via ffmpeg instead of opening
            a second ACUS connection to the phone (which would fail
            because the phone accepts only one client per port).

        The worker thread decodes the JPEG, scales the image down to
        the current canvas size, and re-encodes it as PNG. Tk only
        builds a PhotoImage from PNG bytes — no JPEG decode on the
        UI thread. A single ``self._latest_payload`` slot stores the
        most recently produced PNG so the main thread can drop stale
        work without ever blocking on a slow decode.
        """
        if self._hub is not None:
            self._acus_reader_loop()
        elif self._v4l2_device is not None:
            self._v4l2_reader_loop()
        else:
            self._http_reader_loop()

    def _v4l2_reader_loop(self) -> None:
        """Read frames from an existing /dev/videoN bridge via ffmpeg.

        Uses ffmpeg's v4l2 demuxer to pull MJPEG frames from the
        loopback device that the C driver bridge already owns.
        This avoids opening a second ACUS connection to the phone
        (which would fail because the phone accepts only one client
        per port when the bridge is active).

        ffmpeg's ``-f mjpeg -`` muxer concatenates raw JPEG frames back
        to back (no multipart boundaries), so we split on JPEG SOI /
        EOI markers: each frame starts with ``\\xff\\xd8`` and ends
        with ``\\xff\\xd9``.
        """
        device = self._v4l2_device
        if not device or not Path(device).exists():
            return
        fps = max(1, self._v4l2_fps)

        cmd = [
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "v4l2",
            "-framerate",
            str(fps),
            "-i",
            device,
            "-vcodec",
            "mjpeg",
            "-f",
            "mjpeg",
            "-",
        ]
        self.log(f"In-app preview V4L2 reader avviato su {device}")
        try:
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
        except FileNotFoundError:
            self.log(
                "ffmpeg non trovato; la preview V4L2 non "
                "è disponibile. Installa ffmpeg."
            )
            return

        buf = b""
        width = self._v4l2_width
        height = self._v4l2_height
        SOI = b"\xff\xd8"
        EOI = b"\xff\xd9"
        try:
            while not self._stop_reader.is_set():
                chunk = proc.stdout.read(65536)
                if not chunk:
                    # ffmpeg exited or device disconnected.
                    break
                buf += chunk
                # Cap buffer to 4 MB so a stalled ffmpeg can't grow
                # it without bound.
                if len(buf) > 4 << 20:
                    buf = buf[-4 << 20:]
                while True:
                    start = buf.find(SOI)
                    if start < 0:
                        break
                    end = buf.find(EOI, start + 2)
                    if end < 0:
                        # Need more data — keep what we have.
                        if start > 0:
                            buf = buf[start:]
                        break
                    frame = buf[start : end + 2]
                    buf = buf[end + 2 :]
                    if len(frame) < 4:
                        continue
                    info_payload = {
                        "width": width,
                        "height": height,
                        "format": "JPEG",
                    }
                    self._on_frame(info_payload, frame)
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=2.0)
            except subprocess.TimeoutExpired:
                proc.kill()
            self.log(f"In-app preview V4L2 reader terminato su {device}")

    def _acus_reader_loop(self) -> None:
        hub = self._hub
        if hub is None:
            return
        last_gen = -1
        while not self._stop_reader.is_set():
            try:
                snap = hub.snapshot()
            except Exception:
                snap = None
            if snap is None:
                # Tight poll while waiting for the first frame so the
                # window comes alive quickly once the phone starts
                # streaming.
                time.sleep(0.01)
                continue
            info, frame, gen = snap
            if gen == last_gen:
                # No new frame in the hub — short sleep so we don't
                # burn CPU.
                time.sleep(0.005)
                continue
            last_gen = gen
            self._on_frame(info, frame)

    def _http_reader_loop(self) -> None:
        """HTTP path: stream MJPEG or raw YUV frames from the local proxy.

        We do a tight pull so any failed read restarts the connection
        instead of leaving the window frozen on the last good frame.

        When the proxy returns 5xx (e.g. the phone is no longer
        reachable, or the proxy is still starting up) we retry up to
        ``MAX_HTTP_RETRIES`` times with a short backoff. Past that we
        give up so the user is not spammed with the same error every
        600 ms forever.
        """
        url = self._stream_url
        if not url:
            return
        MAX_HTTP_RETRIES = 3
        # Track measured dimensions for the status bar.
        info: dict = {
            "width": self.phone.info.output_width,
            "height": self.phone.info.output_height,
        }
        retry_count = 0
        while not self._stop_reader.is_set():
            try:
                resp = urllib.request.urlopen(url, timeout=8.0)
                retry_count = 0  # successful connect resets the budget
            except urllib.error.HTTPError as exc:
                # 5xx from the local proxy → phone is unreachable or
                # proxy is still starting. Retry up to MAX_HTTP_RETRIES.
                if exc.code in (502, 503, 504) and retry_count < MAX_HTTP_RETRIES:
                    retry_count += 1
                    if not self._stop_reader.is_set():
                        self.log(
                            f"In-app preview HTTP connect failed "
                            f"(tentativo {retry_count}/{MAX_HTTP_RETRIES}): "
                            f"{exc}"
                        )
                        time.sleep(0.6)
                    continue
                if not self._stop_reader.is_set():
                    self.log(
                        f"In-app preview HTTP connect fallito dopo "
                        f"{retry_count} retry: {exc}"
                    )
                return
            except Exception as exc:  # noqa: BLE001
                if not self._stop_reader.is_set():
                    self.log(f"In-app preview HTTP connect failed: {exc}")
                    time.sleep(0.6)
                continue
            try:
                content_type = resp.headers.get("Content-Type", "").lower()
                if "multipart" in content_type:
                    self._http_mjpeg_loop(resp, info)
                else:
                    self._http_raw_loop(resp, info)
            except Exception as exc:  # noqa: BLE001
                if not self._stop_reader.is_set():
                    self.log(f"In-app preview HTTP read failed: {exc}")
                time.sleep(0.4)
            finally:
                run = getattr(resp, "close", lambda: None)
                run()

    def _http_mjpeg_loop(self, resp, info: dict) -> None:
        """Decode a multipart/x-mixed-replace MJPEG stream part by part."""
        boundary = b"--frame"
        buf = b""
        while not self._stop_reader.is_set():
            chunk = resp.read(8192)
            if not chunk:
                break
            buf += chunk
            while True:
                idx = buf.find(boundary)
                if idx < 0:
                    # Keep up to 1 MB so the buffer can't grow without bound.
                    if len(buf) > 1 << 20:
                        buf = buf[-1 << 20:]
                    break
                # Find the body start after the header blank line.
                head_end = buf.find(b"\r\n\r\n", idx)
                if head_end < 0:
                    break
                body_start = head_end + 4
                # Find the next boundary (or end of buffer).
                next_idx = buf.find(boundary, body_start)
                if next_idx < 0:
                    # The next boundary hasn't arrived yet — wait for more.
                    if len(buf) > 1 << 20:
                        buf = buf[body_start:]
                    break
                # The boundary line is "--frame\r\n" or "--frame--\r\n".
                body_end = next_idx
                # Trim any trailing CRLF before the boundary.
                while body_end > body_start and buf[body_end - 1 : body_end] in (b"\n", b"\r"):
                    body_end -= 1
                frame = buf[body_start:body_end]
                buf = buf[next_idx:]
                if not frame:
                    continue
                info_payload = dict(info)
                info_payload["format"] = "JPEG"
                self._on_frame(info_payload, frame)

    def _http_raw_loop(self, resp, info: dict) -> None:
        """Decode a raw I420 stream into JPEG frames for the Tk canvas.

        Tk cannot display raw I420 — we need an RGB bitmap. Convert
        each chunk to RGB via Pillow before showing.
        """
        width = max(1, int(info.get("width") or self.phone.info.output_width))
        height = max(1, int(info.get("height") or self.phone.info.output_height))
        frame_size = width * height * 3 // 2
        buf = b""
        while not self._stop_reader.is_set():
            chunk = resp.read(frame_size - len(buf))
            if not chunk:
                break
            buf += chunk
            if len(buf) < frame_size:
                continue
            payload = buf[:frame_size]
            buf = buf[frame_size:]
            # Convert I420 → RGB on the worker thread so we don't pay the
            # cost in the Tk main loop. The result is a PIL Image that
            # ``_on_frame`` stores for the main thread to display.
            try:
                img = Image.frombytes("YCbCr", (width, height), payload)
                rgb = img.convert("RGB")
                info_payload = dict(info)
                info_payload["format"] = "YUV"
                self._on_frame(info_payload, rgb)
            except Exception as exc:  # noqa: BLE001
                if not self._stop_reader.is_set():
                    self.log(f"In-app preview YUV decode failed: {exc}")
                continue

    def _on_frame(self, info: dict, frame: bytes) -> None:
        if self._closed:
            return
        self._frame_count += 1
        self._frame_times.append(time.monotonic())
        # Trim the rolling FPS window.
        cutoff = time.monotonic() - self.FPS_WINDOW_S
        while self._frame_times and self._frame_times[0] < cutoff:
            self._frame_times.popleft()
        self._last_width = int(info.get("width") or 0)
        self._last_height = int(info.get("height") or 0)

        fmt = info.get("format", "")
        if fmt == "YUV":
            # _http_raw_loop already converted I420 → RGB on the
            # worker thread. The result is a PIL Image that we pass
            # directly to the Tk main thread — no JPEG/PNG decode
            # needed.
            self._latest_frame = frame
        else:
            # JPEG (or fallback): decode + resize on the worker thread.
            self._latest_frame = self._prepare_payload(frame)
        if self._latest_frame is None:
            return
        # Collapse the callback queue: if a draw is already pending on
        # the main loop, do NOT schedule another one — it would just
        # queue up and re-draw the SAME (stale) frame N times before
        # the UI thread drains. The single pending callback always
        # picks the freshest frame.
        if not self._pending_draw:
            self._pending_draw = True
            try:
                self.win.after(0, self._render_latest)
            except tk.TclError:
                # Window destroyed mid-flight — drop the frame quietly.
                self._pending_draw = False

    def _prepare_payload(self, frame: bytes) -> Image.Image | None:
        """Decode + downscale + convert a frame to RGB on the worker.

        Returns a Pillow ``Image`` (already at canvas size, in RGB mode)
        or ``None`` on failure. The main thread creates a ``PhotoImage``
        from this Image via ``ImageTk.PhotoImage(img)`` — a fast memcpy
        since all decoding, resizing and color-conversion is already done
        here on the worker thread.
        """
        if not _HAS_PIL:
            return None
        try:
            img = Image.open(__import__("io").BytesIO(frame))
            img.load()
            cw, ch = self._canvas_size
            cw = max(cw, 320)
            ch = max(ch, 240)
            iw, ih = img.size
            scale = min(cw / iw, ch / ih) if iw and ih else 1.0
            target = (max(1, int(iw * scale)), max(1, int(ih * scale)))
            if target != (iw, ih):
                # BILINEAR is the cheapest filter that doesn't make the
                # preview look like Minecraft. We don't need LANCZOS
                # here — the camera stream is noisy and the user wants
                # low latency, not pixel-perfect zoom.
                img = img.resize(target, Image.BILINEAR)
            # Convert to RGB if the source is grayscale / palette / RGBA.
            if img.mode != "RGB":
                img = img.convert("RGB")
            return img
        except Exception as exc:  # noqa: BLE001
            if not self._placeholder_drawn:
                self.log(f"In-app preview preprocess error: {exc}")
                self._placeholder_drawn = True
            return None

    def _render_latest(self) -> None:
        """Main-thread side of the frame pipeline.

        Takes the pre-processed Pillow Image (already decoded, resized
        and in RGB mode on the worker thread) and creates a fresh
        ``PhotoImage`` from it. Uses ``itemconfig(image=...)`` to tell
        the canvas to re-blit the new frame. This avoids any JPEG/PNG
        decode on the main thread — just a fast pixel copy.
        """
        self._pending_draw = False
        if self._closed or not _HAS_PIL:
            return
        img = self._latest_frame
        if img is None:
            return
        try:
            cw = max(self.canvas.winfo_width(), 320)
            ch = max(self.canvas.winfo_height(), 240)
            # Create a fresh PhotoImage from the pre-processed Pillow Image.
            # This is fast because the Image is already at the correct size
            # in RGB mode — no decode, no resize, just a pixel copy.
            photo = ImageTk.PhotoImage(img)
            self._current_image = photo  # prevent GC
            if self._canvas_item is not None:
                # Update the existing canvas item with the new image.
                self.canvas.itemconfig(self._canvas_item, image=photo)
            else:
                # First frame: create the canvas item.
                self.canvas.delete("all")
                self._canvas_item = self.canvas.create_image(
                    cw // 2, ch // 2, image=photo, anchor=tk.CENTER
                )
            self._placeholder_drawn = False
        except Exception as exc:  # noqa: BLE001
            if not self._placeholder_drawn:
                self.log(f"In-app preview draw error: {exc}")
                self._placeholder_drawn = True

    def _tick(self) -> None:
        if self._closed:
            return
        # Refresh the cached canvas size the worker reads before
        # resizing each frame. ``winfo_width/height`` is only legal on
        # the main thread; the worker uses the cached value instead.
        try:
            self._canvas_size = (
                self.canvas.winfo_width(),
                self.canvas.winfo_height(),
            )
        except tk.TclError:
            pass
        # Measured FPS over the rolling window of frames that actually
        # arrived on the PC. The phone's configured FPS (phone.info.display_fps)
        # is intentionally NOT used as a fallback: when the rolling window is
        # empty we want the user to see "0.0" right away so they know the
        # stream is no longer delivering frames, instead of a stale configured
        # value that misleads them about whether the bridge is alive.
        if self._frame_times:
            span = self._frame_times[-1] - self._frame_times[0]
            fps = (len(self._frame_times) - 1) / span if span > 0 else 0.0
        else:
            fps = 0.0
        res_text = (
            f"{self._last_width}x{self._last_height}"
            if self._last_width and self._last_height
            else "—"
        )
        self.lbl_resolution.configure(
            text=self.s.in_app_preview_resolution_label.format(value=res_text)
        )
        self.lbl_fps.configure(
            text=self.s.in_app_preview_fps_label.format(value=f"{fps:.1f}")
        )
        self.win.after(self.REFRESH_MS, self._tick)

    def _on_close(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._stop_reader.set()
        # For HTTP transport, the reader loop is blocked in urlopen().
        # Setting _closed makes the next loop iteration bail out, but we
        # also want the socket to close ASAP so the worker thread doesn't
        # sit waiting for the next chunk. The reader does its own
        # graceful-exit on the next iteration of the outer while loop.
        try:
            self.win.grab_release()
        except tk.TclError:
            pass
        self.win.destroy()

