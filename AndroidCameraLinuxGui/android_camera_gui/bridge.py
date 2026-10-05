from __future__ import annotations

import json
import os
import shlex
import signal
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from . import acus_driver
from .discovery import resolve_active_protocol
from .env_utils import load_root_env
from .i18n import Strings
from .models import PhoneDevice, START_VIDEO_NR, sanitize_label, _MAX_VIDEO_NR
# OBS WebSocket sync was removed: "Enable webcam" now only hosts the
# phone as a /dev/videoN node; any V4L2 consumer (browser, VLC, MPV,
# Teams, OBS itself) can pick it up without any GUI/OBS coupling.

STATE_DIR = Path("/tmp/androidcamera-v4l2")
STATE_FILE = STATE_DIR / "devices.tsv"
# How often the watchdog polls ADB /stream.info to detect that the
# Kotlin-side streaming has stopped; in that case we run ``disable_phone``
# automatically so the user does not have to click "Disable" by hand.
WATCHDOG_POLL_S = 1.0
LogFn = Callable[[str], None]

# Total number of /dev/videoN slots the v4l2loopback module is loaded with
# at GUI startup. This equals the maximum number of phones that can be
# enabled concurrently: each phone claims exactly one kernel-owned slot, and
# slots are only reused after the phone stops streaming (the Kotlin app stops
# the stream on resolution/format change, releasing its slot). All slots stay
# kernel-owned and free until claimed, so multiple phones (2nd, 3rd, 5th, …)
# can be enabled without unloading v4l2loopback while OBS/VLC hold a device.
# The value is read from the repo-root .env as V4L2_SLOTS (integer >= 1).
DEFAULT_V4L2_SLOTS = 10
ENV_V4L2_SLOTS = "V4L2_SLOTS"


def v4l2_slots() -> int:
    """Total v4l2loopback slots to create at module load.

    Read from the repo-root .env (``V4L2_SLOTS``); falls back to
    :data:`DEFAULT_V4L2_SLOTS` when unset or invalid. The user can tweak this
    to allow more or fewer concurrent phones.
    """
    try:
        load_root_env()
    except FileNotFoundError:
        # .env missing — fall back to the default. We MUST NOT crash the GUI
        # at import time just because the env file is absent.
        return DEFAULT_V4L2_SLOTS
    raw = os.environ.get(ENV_V4L2_SLOTS, "").strip()
    if not raw:
        return DEFAULT_V4L2_SLOTS
    try:
        value = int(raw)
    except ValueError:
        self_log = getattr(v4l2_slots, "_warned", False)
        if not self_log:
            print(
                f"{ENV_V4L2_SLOTS} non valido nel .env: {raw!r} "
                f"(intero >= 1 richiesto); uso il default {DEFAULT_V4L2_SLOTS}",
                flush=True,
            )
            v4l2_slots._warned = True  # type: ignore[attr-defined]
        return DEFAULT_V4L2_SLOTS
    if value < 1:
        return DEFAULT_V4L2_SLOTS
    return value


@dataclass
class StateRow:
    label: str
    # Stable identifier for this phone (sanitised device name + port). The
    # same phone re-enabled with a different resolution shares the same
    # ``key`` so the GUI can tell "this is the same phone, just retuned"
    # from "this is a brand new phone". The first phone we ever see gets
    # key == label for backwards compatibility with state files written by
    # older GUI versions.
    key: str = ""
    video_nr: int = 0
    port: int = 0
    stream_url: str = ""
    in_w: int = 0
    in_h: int = 0
    out_w: int = 0
    out_h: int = 0
    fps: int = 0
    rotation: int = 0
    # Stream source format/quality hints, used by ffmpeg wiring.
    stream_format: str = "YUV"
    jpeg_quality: int = 75
    # v4l2loopback pixelformat that ``v4l2-ctl --set-fmt-video-out`` accepted
    # on the last successful probe. Different module versions expose
    # different aliases for YUV420-planar (YU12 / YV12 / YUV420). The value
    # is remembered so subsequent ``set-fmt`` calls stay compatible with the
    # loopback in use. None means "not yet probed" — fall back to the
    # candidate list in [BridgeManager.prepare_loopback_format].
    loopback_pixelformat: str | None = None
    # Whether this row's device is still claimed by a consumer (typically
    # OBS). When True we never try to remove the corresponding /dev/videoN
    # node, even if the row is removed from the active list — the consumer
    # gets to keep its camera until it releases the source on its own.
    consumer_attached: bool = False

    @property
    def device_path(self) -> str:
        return f"/dev/video{self.video_nr}"

    @property
    def safe_name(self) -> str:
        return self.label.replace(" ", "_").replace("/", "_")

    @property
    def pid_file(self) -> Path:
        return STATE_DIR / f"{self.safe_name}.pid"

    @property
    def log_file(self) -> Path:
        return STATE_DIR / f"{self.safe_name}.log"


def phone_key(label: str, port: int) -> str:
    """Stable identifier for a phone: label + port (without re-sanitising
    a second time). Different ports → different keys even when the label
    matches, which is what we need to tell a USB Device phone from a
    tether / Wi-Fi phone that share the same ``AndroidCamera`` name."""
    return f"{sanitize_label(label)}@{port}"


class BridgeManager:
    """Creates named v4l2loopback devices and ffmpeg bridges for OBS."""

    def __init__(
        self,
        log: LogFn | None = None,
        strings: Strings | None = None,
    ) -> None:
        self.log = log or (lambda _m: None)
        self.strings = strings
        STATE_DIR.mkdir(parents=True, exist_ok=True)

    # ── state file ──────────────────────────────────────────────

    def load_state(self) -> dict[str, StateRow]:
        """Load the persistent state file.

        Rows are keyed by the phone's stable [key] (label + port) instead of
        just the label, so two phones sharing the same AndroidCamera name
        but on different transports don't collapse into a single row.

        For backwards compatibility we also key a parallel dict by the
        label (legacy behaviour). If a row has no key yet, we back-fill
        it from the label so a single old file still loads.
        """
        rows: dict[str, StateRow] = {}
        if not STATE_FILE.is_file():
            return rows
        for line in STATE_FILE.read_text(encoding="utf-8").splitlines():
            parts = line.split("\t")
            if len(parts) < 10:
                continue
            try:
                # Column 10 is the loopback pixelformat chosen by
                # prepare_loopback_format (None / empty for legacy rows).
                # Columns 11 and 12 are key + consumer_attached added in
                # the multi-phone reload-fix; legacy rows have fewer
                # columns and we back-fill sensible defaults.
                loopback_pf = parts[10] if len(parts) > 10 else ""
                key = parts[11] if len(parts) > 11 else ""
                consumer_attached = (
                    parts[12].lower() in ("1", "true", "yes")
                    if len(parts) > 12
                    else False
                )
                row = StateRow(
                    label=parts[0],
                    video_nr=int(parts[1]),
                    port=int(parts[2]),
                    stream_url=parts[3],
                    in_w=int(parts[4]),
                    in_h=int(parts[5]),
                    out_w=int(parts[6]),
                    out_h=int(parts[7]),
                    fps=int(parts[8]),
                    rotation=int(parts[9]),
                    loopback_pixelformat=loopback_pf or None,
                    key=key or parts[0],
                    consumer_attached=consumer_attached,
                )
            except ValueError:
                continue
            rows[row.key or row.label] = row
        return rows

    def save_state(self, rows: dict[str, StateRow]) -> None:
        # Auto-prune archived rows whose /dev/videoN is no longer claimed
        # by any consumer. These rows accumulate when the user toggles
        # resolutions repeatedly; left alone they balloon the modprobe
        # ``devices=`` and ``video_nr=`` lists to the point where the
        # kernel rejects the load with "Invalid argument".
        kept: dict[str, StateRow] = {}
        for k, r in rows.items():
            if "#archive" in (r.key or k):
                if r.consumer_attached and Path(r.device_path).exists():
                    if self._device_is_locked(r.video_nr):
                        kept[k] = r
                        continue
                # Drop the archive row (consumer gone, or node absent).
                # Also drop archive rows whose bridge writer is dead AND
                # no external consumer (OBS/VLC/browser) holds the node: the
                # slot is truly free, so it must NOT poison the exclude set
                # of _allocate_v4l2_slot — that was the root cause of the
                # "Enable all" failure with 2 phones ("già in uso 2 slot
                # su 10"): archived rows from a past transport switch kept
                # excluding two of the only kernel-owned slots forever.
                if not self._archive_slot_is_free(r):
                    kept[k] = r
                continue
            kept[k] = r
        rows = kept
        lines = [
            "\t".join(
                [
                    r.label,
                    str(r.video_nr),
                    str(r.port),
                    r.stream_url,
                    str(r.in_w),
                    str(r.in_h),
                    str(r.out_w),
                    str(r.out_h),
                    str(r.fps),
                    str(r.rotation),
                    # Loopback pixelformat chosen by prepare_loopback_format.
                    # Empty when never probed — load_state treats that as None.
                    r.loopback_pixelformat or "",
                    r.key or r.label,
                    "1" if r.consumer_attached else "0",
                ]
            )
            for r in sorted(rows.values(), key=lambda x: x.video_nr)
        ]
        STATE_FILE.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")

    # ── privileged helpers ──────────────────────────────────────

    def _run_root(self, args: list[str], timeout: float = 30.0) -> subprocess.CompletedProcess[str]:
        """Run one privileged command (single auth prompt for sudo/pkexec)."""
        if subprocess.run(["sudo", "-n", "true"], capture_output=True).returncode == 0:
            cmd = ["sudo", *args]
            return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
        # pkexec may wait indefinitely for a polkit dialog; keep a tighter ceiling.
        cmd = ["pkexec", *args]
        try:
            return subprocess.run(
                cmd, capture_output=True, text=True, timeout=min(timeout, 25.0)
            )
        except FileNotFoundError:
            raise RuntimeError(
                "pkexec non trovato. Installa polkitd o esegui "
                "`sudo -v` nel terminale prima di avviare la GUI."
            )
        except subprocess.TimeoutExpired as exc:
            raise RuntimeError(
                "Timeout sui privilegi root (pkexec). "
                "Esegui una volta `sudo -v` nel terminale, poi riprova."
            ) from exc

    def _run_root_shell(self, script: str, timeout: float = 30.0) -> subprocess.CompletedProcess[str]:
        """Run a small shell script as root in one privileged invocation."""
        return self._run_root(["/bin/sh", "-c", script], timeout=timeout)

    def _module_loaded(self) -> bool:
        try:
            text = Path("/proc/modules").read_text(encoding="utf-8")
        except OSError:
            return False
        return any(line.startswith("v4l2loopback ") for line in text.splitlines())

    def find_device_by_label(self, label: str) -> str | None:
        base = Path("/sys/class/video4linux")
        if not base.is_dir():
            return None
        for entry in sorted(base.glob("video*")):
            name_file = entry / "name"
            try:
                name = name_file.read_text(encoding="utf-8").strip()
            except OSError:
                continue
            if name == label:
                return f"/dev/{entry.name}"
        return None

    def _is_orphan_node(self, video_nr: int) -> bool:
        """True if ``/dev/videoN`` exists on disk but is NOT registered
        by any kernel driver — i.e. the ``mknod`` succeeded in user space
        but the kernel module never reserved the minor.

        An orphan node is useless to us: every ``v4l2-ctl`` call fails
        with "Unable to detect what device /dev/videoN is". When this
        happens we want to remove the orphan and try the next free slot
        instead of going through the candidate fourcc list and silently
        settling on "no pixelformat accepted".
        """
        path = Path(f"/dev/video{video_nr}")
        if not path.exists():
            return False
        sysfs = Path(f"/sys/class/video4linux/video{video_nr}")
        return not sysfs.is_dir()

    def _is_writable_loopback(self, video_nr: int) -> bool:
        """True if ``/dev/videoN`` is a v4l2loopback OUTPUT-capable device.

        This is the AUTHORITATIVE per-slot test that distinguishes a slot
        we can write a phone stream into from a real webcam (UVC,
        capture-only) or any non-v4l2 node. We use ``v4l2-ctl
        --get-fmt-video-out`` (a READ-ONLY probe that just queries the
        negotiated OUTPUT format): v4l2loopback advertises OUTPUT
        capability so this succeeds (exit 0); a real UVC webcam is
        CAPTURE-only and the kernel rejects ``--get-fmt-video-out`` with
        ``EINVAL`` ("invalid field".  This is more reliable than sysfs
        driver-symlink heuristics (which can be missing or transiently
        unreadable) and matches exactly the operation the bridge later
        performs (``v4l2-ctl --set-fmt-video-out`` + ffmpeg ``-f v4l2``
        output): if the probe cannot negotiate OUTPUT here, ffmpeg cannot
        write frames there.
        """
        path = Path(f"/dev/video{video_nr}")
        if not path.exists():
            return False
        try:
            proc = subprocess.run(
                ["v4l2-ctl", "-d", str(path), "--get-fmt-video-out"],
                capture_output=True,
                text=True,
                timeout=2.0,
            )
        except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
            return False
        return proc.returncode == 0

    def _cleanup_orphan_nodes(self, max_nr: int = 64) -> int:
        """Remove orphan ``/dev/videoN`` nodes whose kernel registration
        has disappeared (typically because the previous v4l2loopback
        module load reserved fewer devices).

        Returns the number of nodes removed. We only touch the
        ``/dev/videoN`` range [:data:`START_VIDEO_NR`..max_nr] to avoid
        clobbering unrelated device nodes created by other subsystems.

        All operations are best-effort: a failed rm (because the user
        has no sudo/pkexec session yet, or because the node is owned
        by another subsystem) is logged and skipped — never raised —
        so the watchdog loop can keep polling without surfacing
        privilege errors every iteration.
        """
        removed = 0
        for nr in range(START_VIDEO_NR, max_nr + 1):
            if not self._is_orphan_node(nr):
                continue
            path = Path(f"/dev/video{nr}")
            # Try plain unlink first: works when the GUI user owns the
            # node (typical when an earlier GUI invocation left the
            # orphan around). Only fall back to the privileged path
            # when plain unlink fails with EACCES.
            try:
                path.unlink()
                self.log(
                    f"Rimosso nodo orfano /dev/video{nr} "
                    "(non registrato dal kernel)."
                )
                removed += 1
                continue
            except PermissionError:
                pass
            except OSError as exc:
                self.log(
                    f"rm /dev/video{nr} fallito ({exc}); "
                    "lo slot verrà saltato dall'allocatore."
                )
                continue
            self.log(
                f"Nodo orfano /dev/video{nr} non rimovibile senza "
                "privilegi: lo slot verrà saltato dall'allocatore."
            )
        return removed

    def _find_working_video_nr(
        self,
        preferred: int,
        exclude: set[int] | None = None,
        max_nr: int = 64,
    ) -> int | None:
        """Return the first ``/dev/videoN`` index in [preferred..max_nr]
        that is registered by the kernel (i.e. has a sysfs entry) and
        NOT held by any consumer (external or our own bridge writers).

        Falls back to scanning the full range if the preferred index
        is unusable. ``None`` when no slot satisfies both constraints.
        Used as a recovery path when the previously-allocated index
        ended up in a broken state (orphan node, stale format, …).

        We start from :data:`START_VIDEO_NR` so on machines without
        Iriun or any other virtual camera the first free slot is the
        smallest index the allocator is allowed to return.

        We skip devices held by our own bridge writers (acus_driver,
        ffmpeg, curl) as well as external consumers (OBS, VLC, …).
        This handles the case where the Kotlin app stopped streaming
        but the old bridge process is still running: the allocator
        steps onto the next free slot instead of reusing the one
        still claimed by the orphaned writer.
        """
        exclude = exclude or set()
        owned = self._kernel_owned_slots()
        candidates = [preferred] + [
            n for n in range(START_VIDEO_NR, max_nr + 1) if n != preferred
        ]
        for nr in candidates:
            if nr in exclude:
                continue
            if nr < START_VIDEO_NR:
                continue
            # Only relocate onto slots whose driver is v4l2loopback: a
            # real webcam (uvcvideo) may satisfy the sysfs/name/locked
            # checks below but is NOT a candidate — v4l2-ctl would fail
            # with VIDIOC_G_FMT Invalid argument, exactly the bug fixed
            # in _kernel_owned_slots(). When 'owned' is empty the module
            # is not (or not properly) loaded: rather than fall back to
            # parking onto a real webcam, bail out cleanly so the caller
            # surfaces a clear error instead of a v4l2-ctl failure.
            if not owned or nr not in owned:
                continue
            path = Path(f"/dev/video{nr}")
            sysfs = Path(f"/sys/class/video4linux/video{nr}")
            if not path.exists() or not sysfs.is_dir():
                continue
            # Skip slots the kernel reserved but no driver claimed.
            if not (sysfs / "name").exists():
                continue
            # Skip slots held by any process — external consumers
            # (OBS, browser, …) or our own bridge writers (acus_driver,
            # ffmpeg, curl). When our own writer is still running it
            # means the old bridge wasn't cleaned up yet (e.g. Kotlin
            # side stopped streaming and the watchdog hasn't fired).
            # Stepping to the next free slot avoids the "first click
            # fails, second click works" problem.
            if self._device_is_locked(nr) or self._device_is_locked_external(nr):
                continue
            return nr
        return None

    def _is_acus(self, row: StateRow) -> bool:
        return row.stream_url.startswith("acus://")

    def _bridge_spawn_cmd(self, row: StateRow) -> list[str]:
        """Command argv to feed [row.device_path] from the phone stream.

        Behaviour depends on the negotiated stream format:
        * YUV (raw I420): existing curl|ffmpeg pipeline (`-f rawvideo -pix_fmt yuv420p`).
        * JPEG (compressed): same pipeline but ffmpeg demuxes a JPEG image stream.
        """
        if self._is_acus(row):
            parsed = acus_driver.parse_acus_url(row.stream_url)
            if parsed is None:
                raise RuntimeError(f"URL ACUS non valido: {row.stream_url}")
            serial, _host, port = parsed
            if not serial:
                raise RuntimeError("URL ACUS senza serial ADB")
            binary = acus_driver.ensure_built(log=self.log)
            return [
                str(binary),
                "--serial",
                serial,
                "--port",
                str(port),
                "--device",
                row.device_path,
            ]

        vf = self._vf(row.in_w, row.in_h, row.out_w, row.out_h, row.rotation)
        if row.stream_format.upper() == "JPEG":
            # JPEG / MJPEG demuxer: the Android HTTP server exposes a stream of
            # concatenated JPEGs which ffmpeg demuxes via the "mjpeg" demuxer.
            input_opts = f"-f mjpeg -re -framerate {row.fps} -i -"
        else:
            input_opts = (
                f"-f rawvideo -pix_fmt yuv420p -s {row.in_w}x{row.in_h} "
                f"-framerate {row.fps} -i -"
            )
        cmd = (
            f"curl -sN '{row.stream_url}' | ffmpeg -hide_banner -loglevel warning "
            f"{input_opts} "
            f"-an -vf '{vf}' "
            f"-f v4l2 -pix_fmt yuv420p -s {row.out_w}x{row.out_h} -r {row.fps} "
            f"'{row.device_path}'"
        )
        return ["bash", "-c", cmd]

    def is_bridge_running(self, row: StateRow) -> bool:
        if row.pid_file.is_file():
            try:
                pid = int(row.pid_file.read_text(encoding="utf-8").strip())
                os.kill(pid, 0)
                return True
            except (ValueError, OSError):
                pass
        # Fallback: ffmpeg or acus_driver writing to this device.
        for proc_dir in Path("/proc").iterdir():
            if not proc_dir.name.isdigit():
                continue
            try:
                exe = Path(os.readlink(proc_dir / "exe")).name
            except OSError:
                continue
            if exe not in ("ffmpeg", "acus_driver"):
                continue
            try:
                cmdline = (
                    (proc_dir / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
                )
            except OSError:
                continue
            if row.device_path in cmdline:
                return True
        return False

    def stop_bridge(self, row: StateRow) -> None:
        if row.pid_file.is_file():
            try:
                pid = int(row.pid_file.read_text(encoding="utf-8").strip())
                try:
                    os.killpg(pid, signal.SIGTERM)
                except ProcessLookupError:
                    try:
                        os.kill(pid, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
            except ValueError:
                pass
            row.pid_file.unlink(missing_ok=True)

        # Kill ffmpeg/curl/acus_driver writers tied to this device/URL.
        for proc_dir in Path("/proc").iterdir():
            if not proc_dir.name.isdigit():
                continue
            try:
                exe = Path(os.readlink(proc_dir / "exe")).name
            except OSError:
                continue
            if exe not in ("ffmpeg", "curl", "acus_driver"):
                continue
            try:
                cmdline = (
                    (proc_dir / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
                )
            except OSError:
                continue
            match = row.device_path in cmdline or row.stream_url in cmdline
            if not match and exe == "acus_driver":
                # Match by device path argument even if URL form differs.
                match = row.device_path in cmdline
            if not match:
                continue
            try:
                os.kill(int(proc_dir.name), signal.SIGTERM)
            except ProcessLookupError:
                pass
        time.sleep(0.3)

        # SIGKILL escalation: a writer stuck in a blocking USB read or
        # holding the V4L2 node open can ignore SIGTERM, which leaves the
        # loopback advertising the last negotiated frame ("frozen last
        # frame" / phantom feed in OBS even after the user clicks
        # "Disable"). Reap any survivor so the writer is truly gone; the
        # caller (_release_loopback_format, invoked at the end of
        # stop_bridge) then drops keep_format/sustain_framerate so
        # v4l2loopback reverts to the module default and the node stops
        # advertising a live feed. (The v4l2loopback card NAME shown by
        # v4l2-ctl --info — "Dummy video device (0x000N)" — is set at
        # modprobe time only and cannot be renamed at runtime on this
        # dkms: no v4l2 control and no writable sysfs file for it. OBS will
        # therefore always enumerate the node as "Dummy video device"; what
        # we CAN do is ensure no writer feeds it, so OBS shows black/No
        # signal when the phone is disabled.) Same writer-matching logic as
        # above; SIGKILL is safe here because we already gave SIGTERM a
        # 0.3 s grace — the standard "TERM then KILL" shutdown.
        for proc_dir in Path("/proc").iterdir():
            if not proc_dir.name.isdigit():
                continue
            try:
                exe = Path(os.readlink(proc_dir / "exe")).name
            except OSError:
                continue
            if exe not in ("ffmpeg", "curl", "acus_driver"):
                continue
            try:
                cmdline = (
                    (proc_dir / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
                )
            except OSError:
                continue
            match = row.device_path in cmdline or row.stream_url in cmdline
            if not match and exe == "acus_driver":
                match = row.device_path in cmdline
            if not match:
                continue
            try:
                os.kill(int(proc_dir.name), signal.SIGKILL)
            except ProcessLookupError:
                pass
        time.sleep(0.2)

        # Release the loopback output format so v4l2loopback drops the
        # last negotiated width/height/pixelformat (keep_format=0 reverts
        # to the module default). Without this, a writer gone but a node
        # OBS still enumerates keeps advertising the old format so OBS
        # shows a phantom "frozen last frame" or a still-active camera
        # even though no phone feeds it anymore. We only do this when no
        # EXTERNAL consumer (OBS/VLC/browser) holds the node: if OBS is
        # actively reading we must not yank the format out from under it.
        self._release_loopback_format(row)

    def _release_loopback_format(self, row: StateRow) -> None:
        """Best-effort release of v4l2loopback's negotiated OUTPUT format so
        the /dev/videoN slot stops advertising the phone's last frame after
        the bridge writer dies (no phantom feed in OBS).

        Sets ``keep_format=0`` and ``sustain_framerate=0`` — the inverse of
        the lock applied in :meth:`prepare_loopback_format`. Skipped when an
        EXTERNAL consumer (OBS/VLC/browser, per ``_device_is_locked_external``
        which ignores our own ffmpeg/curl/acus_driver) holds the node, so we
        never yank a format an app is actively reading. Best-effort: any
        v4l2-ctl failure (node gone, module unloaded, …) is swallowed.
        """
        path = Path(row.device_path)
        if not path.exists():
            return
        try:
            if self._device_is_locked_external(row.video_nr):
                return  # OBS still reads → do not disturb the format.
        except Exception:  # noqa: BLE001
            return  # /proc enumeration failed → conservative: leave format.
        for ctrl in ("keep_format=0", "sustain_framerate=0"):
            try:
                subprocess.run(
                    ["v4l2-ctl", "-d", str(path), f"--set-ctrl={ctrl}"],
                    capture_output=True,
                    text=True,
                    timeout=1.0,
                )
            except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
                pass


    def _vf(self, in_w: int, in_h: int, out_w: int, out_h: int, rotation: int) -> str:
        # Phone already emits upright I420 at the target size; avoid rescale when possible.
        parts: list[str] = []
        if rotation == 90:
            parts.append("transpose=1")
        elif rotation == 180:
            parts.append("transpose=1,transpose=1")
        elif rotation == 270:
            parts.append("transpose=2")
        if (in_w, in_h) != (out_w, out_h) or rotation in (90, 270):
            parts.append(
                f"scale={out_w}:{out_h}:force_original_aspect_ratio=decrease,"
                f"pad={out_w}:{out_h}:(ow-iw)/2:(oh-ih)/2"
            )
        parts.append("format=yuv420p")
        return ",".join(parts)

    def _release_device_readers(self, device_path: str) -> None:
        """Stop only our curl|ffmpeg writers on this node (never fuser -k / kill OBS)."""
        for proc_dir in Path("/proc").iterdir():
            if not proc_dir.name.isdigit():
                continue
            try:
                exe = Path(os.readlink(proc_dir / "exe")).name
            except OSError:
                continue
            if exe not in ("ffmpeg", "curl", "acus_driver"):
                continue
            try:
                cmdline = (
                    (proc_dir / "cmdline")
                    .read_bytes()
                    .replace(b"\0", b" ")
                    .decode(errors="replace")
                )
            except OSError:
                continue
            if device_path not in cmdline:
                continue
            try:
                os.kill(int(proc_dir.name), signal.SIGTERM)
            except ProcessLookupError:
                pass
        time.sleep(0.35)

    def prepare_loopback_format(self, row: StateRow) -> None:
        """
        Force v4l2loopback output to the phone size/fps before ffmpeg opens it.

        The negotiated format must be YUV420-planar so the ffmpeg pipeline
        (``-pix_fmt yuv420p``) matches what the loopback advertises to its
        consumers. The fourcc accepted by ``v4l2-ctl`` depends on the
        v4l2loopback module version: newer ones accept ``YU12`` (I420) and
        ``YV12`` (YV12) interchangeably, older ones only one of them. We
        try a small list of candidates in order and pick the first that
        succeeds; the chosen fourcc is also remembered on the StateRow so
        any subsequent ``--set-fmt-video-out`` call uses the same value.
        """
        if not Path(row.device_path).exists():
            return
        # Sanity-check that the kernel actually owns this node: an orphan
        # /dev/videoN from a previous mknod would make every v4l2-ctl call
        # below fail with "Unable to detect what device /dev/videoN is".
        # We surface a clear message instead of silently trying and
        # burning through the fourcc candidate list.
        sysfs = Path(f"/sys/class/video4linux/video{row.video_nr}")
        if not sysfs.is_dir():
            self.log(
                f"prepare_loopback_format: {row.device_path} esiste ma non "
                "è registrato dal kernel (orphan mknod). Ricarica il modulo "
                "v4l2loopback e riprova."
            )
            return
        self._release_device_readers(row.device_path)
        # Allow format changes when the phone switches portrait/landscape or fps.
        subprocess.run(
            ["v4l2-ctl", "-d", row.device_path, "--set-ctrl=keep_format=0"],
            capture_output=True,
            text=True,
        )
        subprocess.run(
            ["v4l2-ctl", "-d", row.device_path, "--set-ctrl=sustain_framerate=0"],
            capture_output=True,
            text=True,
        )
        # Try several YUV420-planar fourccs. v4l2loopback versions differ on
        # which alias they advertise; pick the first one that v4l2-ctl
        # accepts and reuse it for the rest of the session. This avoids
        # the "The pixelformat 'YU12' is invalid" failure on older kernels
        # where the loopback only exposes ``YV12``.
        candidates = [
            getattr(row, "loopback_pixelformat", None),
            "YU12",
            "YV12",
            "YUV420",
            "YU16",
        ]
        chosen = None
        for fourcc in candidates:
            if not fourcc:
                continue
            fmt = f"width={row.out_w},height={row.out_h},pixelformat={fourcc}"
            set_out = subprocess.run(
                ["v4l2-ctl", "-d", row.device_path, f"--set-fmt-video-out={fmt}"],
                capture_output=True,
                text=True,
            )
            if set_out.returncode == 0:
                chosen = fourcc
                break
            self.log(
                f"v4l2-ctl set-fmt-video-out fallito su {row.device_path} "
                f"con pixelformat={fourcc}: "
                f"{(set_out.stderr or set_out.stdout).strip()}"
            )
        if chosen is None:
            self.log(
                f"v4l2-ctl set-fmt-video-out: nessun fourcc YUV420-planar "
                f"accettato su {row.device_path}, il bridge si affiderà al "
                f"formato predefinito del modulo."
            )
        else:
            row.loopback_pixelformat = chosen
        subprocess.run(
            ["v4l2-ctl", "-d", row.device_path, f"--set-parm={row.fps}"],
            capture_output=True,
            text=True,
        )
        # Lock after negotiation so OBS reopen keeps the phone size/fps.
        subprocess.run(
            ["v4l2-ctl", "-d", row.device_path, "--set-ctrl=keep_format=1"],
            capture_output=True,
            text=True,
        )
        subprocess.run(
            ["v4l2-ctl", "-d", row.device_path, "--set-ctrl=sustain_framerate=1"],
            capture_output=True,
            text=True,
        )

    def _loopback_format(self, device_path: str) -> tuple[int, int, float] | None:
        proc = subprocess.run(
            ["v4l2-ctl", "-d", device_path, "--all"],
            capture_output=True,
            text=True,
        )
        if proc.returncode != 0:
            return None
        width = height = 0
        fps = 0.0
        section = ""
        for line in proc.stdout.splitlines():
            stripped = line.strip()
            if stripped.startswith("Format Video Output:"):
                section = "out"
                continue
            if stripped.startswith("Format Video Capture:"):
                section = "cap"
                continue
            if stripped.startswith("Streaming Parameters Video Output:"):
                section = "out_parm"
                continue
            if stripped.startswith("Streaming Parameters"):
                section = "other"
                continue
            if section == "out" and "Width/Height" in line:
                try:
                    wh = line.split(":")[-1].strip()
                    width_s, height_s = wh.split("/")
                    width, height = int(width_s), int(height_s)
                except ValueError:
                    pass
            if section == "out_parm" and "Frames per second" in line:
                try:
                    fps = float(line.split(":")[-1].strip().split()[0])
                except ValueError:
                    pass
        if width <= 0 or height <= 0:
            return None
        return width, height, fps

    def wait_for_live_stream(self, row: StateRow, timeout: float = 12.0) -> None:
        """Block until the phone publishes a stream.info with ready=True and a
        valid PNG/JPEG/I420 frame.

        Older phones that don't speak the current ACUS protocol are not
        supported: the loop will timeout and raise so the GUI reports
        "service not active".
        """
        if self._is_acus(row):
            self._wait_for_acus_stream(row, timeout=timeout)
            return
        info_url = row.stream_url.rsplit("/", 1)[0] + "/stream.info"
        deadline = time.time() + timeout
        last_err = "timeout"
        while time.time() < deadline:
            try:
                with urllib.request.urlopen(info_url, timeout=1.5) as resp:
                    data = json.loads(resp.read().decode("utf-8", errors="replace"))
                # Mandatory fields — if any is missing, treat as "phone too old".
                required = ("ready", "width", "height", "format", "jpegQuality",
                            "configuredFps", "rotation",
                            "outputWidth", "outputHeight")
                if any(key not in data for key in required):
                    last_err = "phone advertising an older protocol / missing fields"
                    time.sleep(0.4)
                    continue
                if not bool(data.get("ready")):
                    last_err = "ready=false da /stream.info"
                    time.sleep(0.4)
                    continue
                width = int(data.get("width") or 0)
                height = int(data.get("height") or 0)
                if width <= 0 or height <= 0:
                    last_err = f"width/height 0 ({width}x{height})"
                    time.sleep(0.4)
                    continue
                raw_format = str(data.get("format") or "").upper()
                if raw_format not in ("YUV", "JPEG"):
                    last_err = f"format={raw_format} non riconosciuto"
                    time.sleep(0.4)
                    continue
                jpeg_quality = int(data.get("jpegQuality") or 0)
                # JPEG quality is mandatory only for JPEG streams; a raw YUV
                # phone reports jpegQuality=0 and that's perfectly valid.
                if raw_format == "JPEG" and not (10 <= jpeg_quality <= 100):
                    last_err = f"jpegQuality={jpeg_quality} fuori range"
                    time.sleep(0.4)
                    continue
                if raw_format == "YUV":
                    jpeg_quality = 0

                row.in_w = width
                row.in_h = height
                row.out_w = int(data.get("outputWidth") or width)
                row.out_h = int(data.get("outputHeight") or height)
                row.fps = int(data.get("configuredFps") or 30)
                row.rotation = int(data.get("rotation") or 0)
                row.stream_format = raw_format
                row.jpeg_quality = jpeg_quality
                return
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, ValueError, OSError) as exc:
                last_err = str(exc)
            time.sleep(0.4)
        raise RuntimeError(
            f"Lo stream di «{row.label}» non pubblica un protocollo ACUS valido.\n"
            f"Aggiorna l'app Android all'ultima versione e riavvia il servizio "
            f"prima di riprovare.\n({last_err})"
        )

    def _wait_for_acus_stream(self, row: StateRow, timeout: float = 12.0) -> None:
        parsed = acus_driver.parse_acus_url(row.stream_url)
        if parsed is None:
            raise RuntimeError(f"URL ACUS non valido: {row.stream_url}")
        serial, _host, port = parsed
        if not serial:
            raise RuntimeError("URL ACUS senza serial ADB")
        deadline = time.time() + timeout
        last_err = "timeout"
        while time.time() < deadline:
            data = acus_driver.probe(serial, port, timeout=6.0)
            if data is not None:
                # acus_driver.probe() already enforces the required fields; just
                # make sure the values are in range before storing them.
                required = (
                    "width", "height", "fps", "format", "jpegQuality",
                    "protocol_version",
                    "jpeg_supported",
                )
                if any(key not in data for key in required):
                    last_err = "ACUS probe missing required fields"
                else:
                    width = int(data.get("width") or 0)
                    height = int(data.get("height") or 0)
                    jpeg_quality = int(data.get("jpegQuality") or 0)
                    raw_format = str(data.get("format") or "").upper()
                    if width <= 0 or height <= 0:
                        last_err = f"handshake senza dimensioni ({width}x{height})"
                    elif raw_format not in ("YUV", "JPEG"):
                        last_err = f"format={raw_format} non riconosciuto"
                    elif raw_format == "JPEG" and not (10 <= jpeg_quality <= 100):
                        last_err = f"jpegQuality={jpeg_quality} fuori range"
                    elif raw_format == "YUV" and jpeg_quality != 0:
                        last_err = f"jpegQuality={jpeg_quality} non zero in YUV"
                    else:
                        row.in_w = width
                        row.in_h = height
                        row.out_w = int(data.get("outputWidth") or width)
                        row.out_h = int(data.get("outputHeight") or height)
                        row.fps = int(data.get("fps") or 30)
                        row.rotation = int(data.get("rotation") or 0)
                        row.stream_format = raw_format
                        # Normalize: YUV carries no JPEG quality.
                        row.jpeg_quality = 0 if raw_format == "YUV" else jpeg_quality
                        return
            else:
                last_err = "handshake ACUS non disponibile"
            time.sleep(0.5)
        raise RuntimeError(
            f"Lo stream USB Device di «{row.label}» non publica frame ACUS.\n"
            f"Nell'app scegli USB Device, avvia il servizio e riprova.\n({last_err})"
        )

    def _wait_for_acus_stream_again(self, deadline: float) -> None:
        """No-op shim left in place for backwards-compatible call sites."""
        return None

    def _device_readers(self, device_path: str) -> list[str]:
        """Process names currently opening the V4L2 node (e.g. obs, ffmpeg)."""
        names: list[str] = []
        try:
            proc = subprocess.run(
                ["fuser", device_path],
                capture_output=True,
                text=True,
            )
        except FileNotFoundError:
            return names
        for pid_s in (proc.stdout or "").split():
            if not pid_s.strip().isdigit():
                continue
            try:
                names.append(Path(os.readlink(f"/proc/{pid_s}/exe")).name)
            except OSError:
                names.append(pid_s)
        return names

    def verify_capture(self, row: StateRow, timeout: float = 5.0) -> bool:
        """
        Try to grab one frame from the loopback.

        Returns False when another consumer (typically OBS) holds the device and a
        second reader cannot open it — that is not a stream failure.
        """
        check = STATE_DIR / f"{row.safe_name}-check.jpg"
        check.unlink(missing_ok=True)
        proc = subprocess.run(
            [
                "timeout",
                str(max(2, int(timeout))),
                "ffmpeg",
                "-y",
                "-hide_banner",
                "-loglevel",
                "error",
                "-f",
                "v4l2",
                "-i",
                row.device_path,
                "-frames:v",
                "1",
                str(check),
            ],
            capture_output=True,
            text=True,
        )
        if proc.returncode == 0 and check.is_file() and check.stat().st_size >= 100:
            return True
        return False

    def start_bridge(
        self,
        row: StateRow,
        pre_detached: list[str] | None = None,
    ) -> None:
        """Start (or restart) the bridge for [row]. No OBS WebSocket sync is
        performed — the only requirement is that the v4l2loopback node
        accepts the requested format, which may mean releasing other
        readers (browser, VLC, …) still attached to the device. The
        helper [prepare_loopback_format] handles the format negotiation;
        if a stubborn consumer still holds the node, we fall back to a
        v4l2loopback module reload."""
        self.stop_bridge(row)
        self.wait_for_live_stream(row)
        # Align in/out with live phone frames (portrait etc.).
        if row.in_w <= 0 or row.in_h <= 0:
            row.in_w, row.in_h = row.out_w, row.out_h
        if row.out_w <= 0 or row.out_h <= 0:
            row.out_w, row.out_h = row.in_w, row.in_h
        row.fps = max(1, int(row.fps or 30))

        # Release any external reader still attached to this node so
        # v4l2loopback can actually change width/height. We do NOT touch
        # OBS (the OBS WebSocket bridge was removed by request); we only
        # kill our own bridge + curl/acus_driver processes.
        self._release_device_readers(row.device_path)

        # Pre-flight: detect that the previously-allocated /dev/videoN
        # is unusable (orphan node, busy by an external consumer, or
        # still held by our own bridge writer that the watchdog
        # hasn't cleaned up yet). When that happens we move the row
        # onto a fresh slot — this is the exact "first click fails,
        # second click works" recovery the user asked us to automate:
        # after a Kotlin-side stop+start the old slot is typically
        # still around as an orphan or still claimed by the old
        # acus_driver process, and the only working remediation is
        # to step onto the next free index.
        # Pre-flight wrapped in try/except: a transient sysfs/udev race while
        # probing the slot must never abort the whole enable — if we can't
        # find a replacement we keep the original index and let the bridge
        # attempt run (prepare_loopback_format + the writer will surface a
        # concrete error if the slot is truly bad). "Continue on exception"
        # per the user's request: never raise here on a probe hiccup.
        try:
            slot_ok = self._slot_is_usable(row.video_nr)
        except Exception as exc:  # noqa: BLE001
            self.log(
                f"«{row.label}» slot /dev/video{row.video_nr} check sollevato "
                f"{exc!r}; provo comunque."
            )
            slot_ok = True  # assume usable; let the bridge attempt prove it
        if not slot_ok:
            try:
                replacement = self._find_working_video_nr(
                    preferred=START_VIDEO_NR,
                    exclude={row.video_nr},
                    max_nr=64,
                )
            except Exception as exc:  # noqa: BLE001
                self.log(
                    f"«{row.label}» ricerca slot alternativo sollevato "
                    f"{exc!r}; tengo /dev/video{row.video_nr}."
                )
                replacement = None
            if replacement is not None and replacement != row.video_nr:
                self.log(
                    f"«{row.label}» slot /dev/video{row.video_nr} non "
                    f"utilizzabile: passo a /dev/video{replacement}."
                )
                row.video_nr = replacement

        self.prepare_loopback_format(row)

        spawn_cmd = self._bridge_spawn_cmd(row)
        if self._is_acus(row):
            self.log(
                f"USB Device driver → {row.device_path} ({row.label}) "
                f"{row.out_w}x{row.out_h}@{row.fps}"
            )
        else:
            self.log(
                f"Bridge → {row.device_path} ({row.label}) "
                f"{row.out_w}x{row.out_h}@{row.fps}"
            )
        log_fh = row.log_file.open("w", encoding="utf-8")
        proc = subprocess.Popen(
            spawn_cmd,
            stdout=log_fh,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        row.pid_file.write_text(str(proc.pid), encoding="utf-8")
        time.sleep(1.5)

        alive = proc.poll() is None or self.is_bridge_running(row)
        if not alive:
            tail = ""
            if row.log_file.is_file():
                tail = row.log_file.read_text(encoding="utf-8", errors="replace")[-800:]
            raise RuntimeError(f"Bridge fallito per {row.label}.\n{tail}")

        fmt = self._loopback_format(row.device_path)
        if fmt is not None:
            fw, fh, ffps = fmt
            if (fw, fh) != (row.out_w, row.out_h):
                self.log(
                    f"Formato loopback {fw}x{fh} ≠ {row.out_w}x{row.out_h}, ritento…"
                )
                self.stop_bridge(row)
                self._release_device_readers(row.device_path)
                self.prepare_loopback_format(row)
                spawn_cmd = self._bridge_spawn_cmd(row)
                log_fh = row.log_file.open("w", encoding="utf-8")
                proc = subprocess.Popen(
                    spawn_cmd,
                    stdout=log_fh,
                    stderr=subprocess.STDOUT,
                    start_new_session=True,
                )
                row.pid_file.write_text(str(proc.pid), encoding="utf-8")
                time.sleep(1.5)
                fmt = self._loopback_format(row.device_path)
                if fmt is None or (fmt[0], fmt[1]) != (row.out_w, row.out_h):
                    got = f"{fmt[0]}x{fmt[1]}" if fmt else "?"
                    self.log(
                        f"Formato loopback {got} ≠ {row.out_w}x{row.out_h} "
                        f"(tentativo 2 fallito), forzo reload del modulo v4l2loopback…"
                    )
                    # Force a full v4l2loopback reset so that any remaining
                    # reader (e.g. browser, MPV) is released and the device
                    # can accept the new format.
                    rows = self.load_state()
                    rows_for_reload = {
                        k: v
                        for k, v in rows.items()
                        if v.device_path == row.device_path
                    }
                    if rows_for_reload:
                        self.load_v4l2(rows_for_reload)
                    time.sleep(0.5)
                    self.prepare_loopback_format(row)
                    spawn_cmd = self._bridge_spawn_cmd(row)
                    log_fh = row.log_file.open("w", encoding="utf-8")
                    proc = subprocess.Popen(
                        spawn_cmd,
                        stdout=log_fh,
                        stderr=subprocess.STDOUT,
                        start_new_session=True,
                    )
                    row.pid_file.write_text(str(proc.pid), encoding="utf-8")
                    time.sleep(1.5)
                    fmt = self._loopback_format(row.device_path)
                    if fmt is None or (fmt[0], fmt[1]) != (row.out_w, row.out_h):
                        got2 = f"{fmt[0]}x{fmt[1]}" if fmt else "?"
                        raise RuntimeError(
                            f"Loopback {row.device_path} è {got2} invece di "
                            f"{row.out_w}x{row.out_h} anche dopo il reload."
                        )
                    fw, fh, ffps = fmt
                fw, fh, ffps = fmt
            if ffps > 0 and ffps >= 1.0:
                if abs(ffps - row.fps) > 0.6:
                    self.log(
                        f"Avviso: loopback riporta {ffps:.0f} fps "
                        f"(attesi {row.fps}), aggiorno"
                    )
                    row.fps = int(round(ffps))
                self.log(f"Formato loopback OK: {fw}x{fh}@{row.fps}")

        if self.verify_capture(row):
            self.log(f"Capture OK su {row.device_path}")
            return
        if self.is_bridge_running(row):
            readers = ", ".join(self._device_readers(row.device_path)) or "sconosciuto"
            self.log(
                f"Bridge attivo su {row.device_path} "
                f"(verifica frame saltata: device già in uso da {readers})"
            )
            return
        raise RuntimeError(
            f"Il device {row.device_path} non fornisce frame e il bridge non è in esecuzione."
        )

    def load_v4l2(self, rows: dict[str, StateRow]) -> None:
        """Ensure the kernel owns enough v4l2loopback slots for the rows.

        The ``V4L2_SLOTS`` /dev/videoN slots are created at GUI startup by
        ``run.sh`` (with sudo) BEFORE the Python process starts. The GUI
        therefore NEVER ``modprobe -r``/reloads v4l2loopback at runtime —
        that fails the instant a consumer (OBS/VLC/browser) holds a node,
        which was the root cause of the multi-phone allocation failures.

        This function only:
          * verifies the module is loaded with >= V4L2_SLOTS kernel-owned
            slots;
          * reassigns any row sitting on a stale/orphan index onto a slot the
            kernel actually owns (no module reload);
          * surfaces a clear error when the module is missing or has too few
            slots (telling the user to launch via run-linux-gui.sh).
        """
        wanted = v4l2_slots()
        if not rows:
            return

        # Only active rows matter; archived rows are stale slots OBS still
        # holds from a past resolution change and are managed separately.
        active_rows = [
            r for r in rows.values()
            if "#archive" not in (r.key or r.label)
        ]
        ordered = sorted(active_rows, key=lambda r: r.video_nr)
        if len(ordered) > wanted:
            raise RuntimeError(
                f"V4L2_SLOTS={wanted} nel .env è inferiore al numero di "
                f"telefoni attualmente attivi ({len(ordered)}). Aumenta "
                f"V4L2_SLOTS nel file .env e riavvia la GUI."
            )

        if not self._module_loaded():
            raise RuntimeError(
                "Modulo v4l2loopback non caricato. Avvia la GUI tramite "
                "run-linux-gui.sh (crea gli slot /dev/videoN con sudo); "
                "altrimenti i telefoni non possono essere hostati."
            )

        loaded_devs = self._loaded_v4l2_device_count()
        if loaded_devs < wanted:
            raise RuntimeError(
                f"v4l2loopback caricato con {loaded_devs} device ma ne "
                f"servono {wanted} (V4L2_SLOTS nel .env). Lo script di "
                f"avvio non è riuscito a creare gli slot (un consumer "
                f"teneva un device?). Chiudi OBS/VLC e riavvia la GUI "
                f"tramite run-linux-gui.sh."
            )

        self.log(
            f"v4l2loopback caricato con {loaded_devs} slot "
            f"(ne servono {wanted}); non scarico/ricarico il modulo."
        )
        # Move any row sitting on an orphan (non-kernel-owned) index onto a
        # kernel-owned slot — no module reload required. If no slot is free
        # the reassignment returns False and we surface a clear error.
        if not self._reassign_into_free_slots(ordered):
            raise RuntimeError(
                "Impossibile riassegnare ogni telefono su uno slot "
                "kernel-ownato libero: slot v4l2loopback insufficienti. "
                "Aumenta V4L2_SLOTS nel .env e riavvia la GUI."
            )
        for row in ordered:
            if not self._ensure_v4l2_node(row.video_nr):
                raise RuntimeError(
                    f"Impossibile creare il nodo {row.device_path}: lo slot "
                    "non è kernel-ownato da v4l2loopback. Riavvia la GUI "
                    "tramite run-linux-gui.sh."
                )

    def _loaded_v4l2_device_count(self) -> int:
        """How many /dev/videoN devices v4l2loopback currently exposes.

        Two sources, in order:

          * ``/sys/module/v4l2loopback/parameters/devices`` (integer): exposed
            by some dkms versions and gives the module's own device count
            directly.
          * Otherwise count the ``/sys/class/video4linux/videoN`` nodes whose
            backing ``device/driver`` symlink resolves to ``v4l2loopback``.
            This is the source of truth with auto-assignment (``video_nr``
            all ``-1``), where it correctly distinguishes our loopback
            devices from real webcams (``uvcvideo`` etc.).

        The legacy ``parameters/video_nr`` branch was removed: on many dkms
        builds ``video_nr`` is a fixed-size array (here 8 entries) filled with
        ``-1`` under auto-assignment, so counting non-negative entries
        returned 0 even with 10 active devices.
        """
        param_devices = Path("/sys/module/v4l2loopback/parameters/devices")
        if param_devices.is_file():
            try:
                return int(param_devices.read_text(encoding="utf-8").strip() or "0")
            except ValueError:
                pass
        base = Path("/sys/class/video4linux")
        if not base.is_dir():
            return 0
        n = 0
        for entry in base.iterdir():
            if not entry.name.startswith("video"):
                continue
            try:
                drv = (entry / "device" / "driver").resolve()
            except OSError:
                drv = None
            if drv is not None and drv.name == "v4l2loopback":
                n += 1
        if n > 0:
            return n
        # Last resort: count nodes with a non-empty name (imprecise — may
        # include non-loopback devices — but better than 0).
        return sum(
            1 for entry in base.iterdir()
            if entry.name.startswith("video")
            and (entry / "name").read_text(errors="replace").strip() != ""
        )

    def _ensure_v4l2_node(self, video_nr: int) -> bool:
        """Make sure /dev/videoN exists; create it via mknod if missing.

        Returns True when the node is present and claimed by v4l2loopback
        after the call, False when mknod failed (typically because the
        kernel only reserved fewer slots than ``video_nr``).

        We can grow the device count by creating extra character-device
        nodes with the right major (81 for video4linux on Linux), but only
        when the kernel module is loaded with at least that many devices.
        Outside the loaded range mknod succeeds but the new node is NOT
        a v4l2loopback device — subsequent ``v4l2-ctl`` calls then fail
        with "Unable to detect what device /dev/videoN is". The caller
        is expected to fall back to a module reload in that case.
        """
        path = Path(f"/dev/video{video_nr}")
        if path.exists():
            # The node is present but might be a stale orphan from a
            # previous mknod call (kernel never claimed it). Verify it
            # by checking /sys/class/video4linux: the kernel only adds
            # the entry there once a real driver has claimed the minor.
            sysfs = Path(f"/sys/class/video4linux/video{video_nr}")
            if sysfs.is_dir():
                return True
            # Stale orphan: try to remove it (best-effort) and recreate
            # from scratch below.
            self._run_root(
                ["/bin/sh", "-c", f"rm -f {shlex.quote(str(path))}"],
                timeout=10,
            )
        # major number 81 is video4linux on every Linux we care about.
        result = self._run_root(
            ["/bin/sh", "-c", f"mknod {shlex.quote(str(path))} c 81 {video_nr}"],
            timeout=10,
        )
        if result.returncode != 0:
            return False
        # Wait briefly for the kernel to register the new node in
        # sysfs. udev usually picks up the mknod within a few ms but
        # on slower systems this can take longer.
        for _ in range(20):
            if Path(f"/sys/class/video4linux/video{video_nr}").is_dir():
                return True
            time.sleep(0.05)
        return False

    def _reassign_into_free_slots(self, ordered: list[StateRow]) -> bool:
        """Move rows that need a brand-new device into indices the kernel
        hasn't already handed out. Only used when we cannot unload the
        module because a third-party consumer is still attached.

        Returns True when every row ended up on a slot where ``mknod``
        succeeded (i.e. the kernel actually owns the node). Returns
        False when at least one row couldn't be assigned — the caller
        must then either fall back to a reload-with-spaces or surface a
        clear error to the user.

        Two cases are handled:
          * Rows whose video_nr is below the loaded range (the kernel
            already owns those slots, but our stale orphan at that index
            might prevent a fresh mknod). We DO NOT bump those rows: the
            old index is still valid as long as the kernel owns it.
          * Rows whose video_nr is above the loaded range. The kernel
            doesn't own those slots yet — the previous mknod left an
            orphan. We bump the row up to a fresh index, then mknod.
            If we run out of free indices above the loaded range we
            return False so the caller can decide between a reload and
            telling the user to free up /dev/videoN.
        """
        loaded = self._loaded_v4l2_device_count()
        owned = self._kernel_owned_slots()
        taken: set[int] = set()
        # Off-limits: every sysfs minor that is NOT a v4l2loopback slot
        # (real webcams — uvcvideo — and the acus driver), so a relocated
        # row is never parked onto the laptop's physical camera. Loopback
        # slots themselves are NOT pre-added here: they are candidates, and
        # the per-row ``new_nr in taken`` below only blocks slots already
        # reassigned earlier in this same loop or held by another row.
        for entry in Path("/sys/class/video4linux").glob("video*"):
            suffix = entry.name.removeprefix("video")
            if not suffix.isdigit():
                continue
            nr = int(suffix)
            if nr not in owned:
                taken.add(nr)
        # Anything reserved by us (so we don't assign two rows the same
        # slot).
        for row in ordered:
            taken.add(row.video_nr)

        ok = True
        for row in ordered:
            path = Path(row.device_path)
            sysfs = Path(f"/sys/class/video4linux/video{row.video_nr}")
            needs_move = (
                row.video_nr >= loaded
                or not path.exists()
                or not sysfs.is_dir()
            )
            if not needs_move:
                continue
            # Find the next free index the kernel actually owns (driver
            # v4l2loopback, sysfs entry present) so mknod succeeds and we
            # never land on a real webcam.
            new_nr = max(loaded, row.video_nr)
            while (
                new_nr in taken
                or new_nr not in owned
                or not Path(f"/sys/class/video4linux/video{new_nr}").is_dir()
            ):
                new_nr += 1
                if new_nr > 255:
                    self.log(
                        f"«{row.label}»: nessuno slot V4L2 libero oltre "
                        f"l'indice {loaded} (modulo non scaricabile)."
                    )
                    ok = False
                    break
            if not ok:
                break
            self.log(
                f"«{row.label}» spostato da /dev/video{row.video_nr} "
                f"a /dev/video{new_nr} (modulo non scaricabile)."
            )
            row.video_nr = new_nr
            taken.add(new_nr)
        return ok

    def restart_all(self, rows: dict[str, StateRow]) -> None:
        for row in sorted(rows.values(), key=lambda r: r.video_nr):
            if not Path(row.device_path).exists():
                self.log(f"Manca {row.device_path} per {row.label}")
                continue
            try:
                self.start_bridge(row)
            except Exception as exc:  # noqa: BLE001
                self.log(str(exc))

    def enable_phone(
        self, phone: PhoneDevice
    ) -> tuple[StateRow, PhoneDevice]:
        """Create the v4l2 device for [phone] and start the bridge.

        No OBS WebSocket sync is performed (that path was removed). The
        function returns the StateRow + the resolved phone (which may
        differ from the request after a fresh ACUS vs HTTP re-probe).

        Behaviour on a re-enable:
          * First time: a new /dev/videoN is allocated from the spare pool
            loaded into v4l2loopback at startup.
          * Same phone, no resolution change: reuse the existing device.
          * Same phone, resolution changed: the OLD device is left in
            place for OBS to release on its own; we allocate a NEW device
            with the new card_label, mark the old row as
            ``consumer_attached`` so future reloads preserve it.
        """
        # Always re-check ACUS vs HTTP: the tree may be stale after a mode switch.
        phone = resolve_active_protocol(phone, log=self.log)
        self.log(
            f"Abilito «{phone.display_name}» via "
            f"{'ACUS/USB Device' if phone.transport == 'acus' else 'HTTP'}"
        )
        label = sanitize_label(phone.info.device_name)
        key = phone_key(label, phone.port)
        rows = self.load_state()
        existing_row = rows.get(key)

        # Detect resolution/fps change for the same phone. When something
        # changes, the existing /dev/videoN may be locked by OBS and we
        # cannot re-program it — so we leave it behind and grab a new
        # device. The old row keeps the consumer_attached flag so future
        # reloads do not stomp on it.
        #
        # IMPORTANT: the old row must be re-keyed so the new row (which
        # shares the same ``phone_key``) does not overwrite it. Without
        # this re-keying the ``consumer_attached`` state is lost and a
        # later enable would yank the device out from under OBS. We
        # re-key by appending ``@<old video_nr>`` so the human-friendly
        # ``label``/``port`` lookup still works (rows are looked up by
        # ``key`` first; the legacy label-fallback only kicks in when no
        # key matches).
        #
        # The format-equality check also covers ``rotation``: the phone
        # may have been rotated (portrait ↔ landscape) without changing
        # the negotiated width/height, in which case the bridge's
        # ffmpeg ``-vf`` filter is updated but the /dev/videoN format
        # stays the same — so we should reuse the existing node.
        same_format = (
            existing_row is not None
            and existing_row.out_w == phone.info.output_width
            and existing_row.out_h == phone.info.output_height
            and existing_row.fps == phone.info.display_fps
            and existing_row.stream_format.upper() == (phone.info.stream_format or "YUV").upper()
            and existing_row.rotation == phone.info.rotation
            and existing_row.jpeg_quality == phone.info.jpeg_quality
            # Stream URL (encodes the transport: acus:// vs http://). A
            # transport switch (e.g. ACUS→HTTP fallback when ADB finds 0
            # devices mid-scan) must trigger re-evaluation even when width/
            # height/fps are unchanged: the running bridge was built for the
            # old transport, and start_bridge must rebuild it for the new one.
            and existing_row.stream_url == phone.stream_url
        )

        # Fast-path: the bridge for this exact phone is already up on
        # the exact same /dev/videoN with the exact same parameters.
        # Just re-verify the live stream (the phone might have just
        # restarted after a stop/start on the Kotlin side) and bail
        # out — do NOT touch v4l2loopback, do NOT call modprobe. The
        # previous implementation always rebuilt the bridge from
        # scratch here, which forced a modprobe -r/load round-trip
        # that fails the moment OBS holds the existing /dev/videoN.
        if (
            existing_row is not None
            and same_format
            and self.is_bridge_running(existing_row)
            and Path(existing_row.device_path).exists()
        ):
            self.log(
                f"«{existing_row.label}» bridge già attivo su "
                f"{existing_row.device_path}; salto reload di v4l2loopback."
            )
            # Best-effort re-verification that the phone is still
            # publishing frames; failures here fall through to the
            # full restart path below.
            try:
                self.wait_for_live_stream(existing_row, timeout=4.0)
                rows[key] = existing_row
                self.save_state(rows)
                return existing_row, phone
            except Exception as exc:  # noqa: BLE001
                self.log(
                    f"«{existing_row.label}» ri-verifica live fallita "
                    f"({exc}); riavvio il bridge da zero."
                )
                # Fall through to the full rebuild path.
        if existing_row is not None and not same_format:
            self.log(
                f"«{label}» cambiato: {existing_row.out_w}x{existing_row.out_h}@"
                f"{existing_row.fps} → {phone.info.output_width}x"
                f"{phone.info.output_height}@{phone.info.display_fps}; "
                f"alloco un nuovo /dev/videoN (il vecchio resta in uso)."
            )
            # Only archive the old row when its slot is genuinely still in use
            # (our writer alive OR an external consumer holds the node). When
            # the bridge writer is dead AND nothing external holds the node
            # the slot is truly free: we stop_bridge + release the readers
            # and DROP the row, then fall through to a fresh allocation that
            # can reuse the same slot. Archiving a dead row let it poison
            # _allocate_v4l2_slot's exclude set forever — the "già in uso 2
            # slot su 10" failure with 2 phones after a transport switch.
            bridge_dead = False
            try:
                bridge_dead = not self.is_bridge_running(existing_row)
            except Exception:  # noqa: BLE001
                bridge_dead = False
            external_hold = False
            try:
                external_hold = self._device_is_locked_external(existing_row.video_nr)
            except Exception:  # noqa: BLE001
                external_hold = False
            if bridge_dead and not external_hold:
                # Slot is free — release any leftover writer and drop the row.
                try:
                    self.stop_bridge(existing_row)
                except Exception as exc:  # noqa: BLE001
                    self.log(f"Watchdog: stop_bridge stale su {existing_row.label} — {exc}")
                rows.pop(existing_row.key or key, None)
                existing_row = None  # fresh allocation reuses the freed slot
            else:
                existing_row.consumer_attached = True
                # Move the old row under a separate key so the new row (which
                # shares the same phone_key) can be inserted without losing it.
                old_key = existing_row.key or existing_row.label
                archive_key = f"{old_key}#archive{existing_row.video_nr}"
                existing_row.key = archive_key
                rows[archive_key] = existing_row
                rows.pop(old_key, None)
                existing_row = None  # force a fresh allocation below

        if existing_row is not None:
            video_nr = existing_row.video_nr
        else:
            # Allocate a brand-new slot. The exclude set is the explicit
            # list of /dev/videoN indices already assigned to the OTHER
            # phones the GUI is currently managing (every active, non-
            # archived row whose key differs from this phone's). We never
            # try to bind a 2nd/3rd/Nth phone onto a slot already taken by
            # a sibling phone — which is the bug the user hit: when enabling
            # a 2nd phone the allocator walked onto the 1st phone's slot
            # (or onto an unreserved index) and mknod failed.
            #
            # Only active rows count: archived rows are stale slots OBS
            # still holds from a past resolution change, and they are free
            # to be reused once OBS releases them — but we still keep them
            # in the exclude set so two live bridges never collide.
            exclude: set[int] = set()
            for other_key, other_row in rows.items():
                if other_key == key:
                    continue
                if "#archive" in (other_row.key or other_key):
                    # Archived slot: only keep it excluded while it is still
                    # physically held (by our own writer or by an external
                    # consumer like OBS). When ``_archive_slot_is_free``
                    # reports the slot is free, we do NOT add it to exclude
                    # — defensive against a stale archive row whose
                    # consumer_attached flag was not pruned by save_state,
                    # which would otherwise poison the exclude set and make
                    # _allocate_v4l2_slot walk past a perfectly reusable
                    # kernel-owned slot to a higher index.
                    try:
                        if not self._archive_slot_is_free(other_row):
                            exclude.add(other_row.video_nr)
                    except Exception:  # noqa: BLE001
                        exclude.add(other_row.video_nr)
                    continue
                exclude.add(other_row.video_nr)
            video_nr = self._allocate_v4l2_slot(exclude)
            if video_nr is None:
                slots = v4l2_slots()
                raise RuntimeError(
                    f"Nessuno slot /dev/videoN libero per «{label}»: sono "
                    f"già in uso {len(exclude)} slot su {slots} totali. "
                    f"Aumenta V4L2_SLOTS nel file .env (poi riavvia la GUI) "
                    f"o scollega uno dei telefoni già abilitati."
                )

        # The new label reflects the resolution so OBS users can tell two
        # webcams with the same phone name apart ("AndroidCamera123" vs
        if existing_row is None:
            new_label = self._format_card_label(label, phone)
        else:
            new_label = existing_row.label

        # ── Slot retry loop ─────────────────────────────────────────────
        # When start_bridge() fails (e.g. /dev/videoN still locked by OBS/VLC
        # and the format cannot be negotiated), instead of failing permanently
        # we try the NEXT available slot. We keep trying until the bridge
        # succeeds on some slot, or until every slot has been tried exactly
        # once. When all slots are exhausted, the error is returned to
        # the caller which shows it in a messagebox and stops.
        tried_slots: set[int] = set()
        final_error: Exception | None = None
        slot_attempts = 0

        while True:
            slot_attempts += 1
            tried_slots.add(video_nr)

            row = StateRow(
                label=new_label,
                key=key,
                video_nr=video_nr,
                port=phone.port,
                stream_url=phone.stream_url,
                in_w=phone.info.width,
                in_h=phone.info.height,
                out_w=phone.info.output_width,
                out_h=phone.info.output_height,
                fps=phone.info.display_fps,
                rotation=phone.info.rotation,
                stream_format=phone.info.stream_format or "YUV",
                jpeg_quality=phone.info.jpeg_quality,
            )
            rows[key] = row
            self.save_state(rows)

            # Decide whether we need to ensure the slot's /dev/videoN node.
            # The module is loaded (with V4L2_SLOTS kernel-owned slots) by
            # run.sh at GUI startup, so we never modprobe -r/reload here.
            # We only need a (best-effort) mknod when the chosen
            # kernel-owned slot has no /dev/videoN node on disk yet.
            need_ensure = (
                not Path(row.device_path).exists()
                or video_nr not in self._kernel_owned_slots()
            )

            if need_ensure:
                self.load_v4l2(rows)

            # Always (re)start this phone.
            try:
                self.start_bridge(row)
                # Success! Log and break out of the retry loop.
                if slot_attempts > 1:
                    self.log(
                        self.strings.log_slot_retry_success.format(
                            video_nr=video_nr, attempts=slot_attempts,
                        )
                    )
                break
            except Exception as exc: # noqa: BLE001
                self.log(
                    self.strings.log_slot_failed_retry.format(
                        video_nr=video_nr, error=exc,
                    )
                )
                final_error = exc
                # Release the failed slot so it can be reused.
                try:
                    self.stop_bridge(row)
                except Exception: # noqa: BLE001
                    pass
                # Remove the failed row from state so the slot is freed.
                rows.pop(key, None)
                self.save_state(rows)
                # Try the next available slot.
                next_slot = self._allocate_v4l2_slot(exclude | tried_slots)
                if next_slot is None:
                    # No more slots to try.
                    self.log(self.strings.log_slot_retry_exhausted)
                    break
                video_nr = next_slot

        rows[key] = row

        # If all slots were exhausted, propagate the last error.
        if final_error is not None:
            raise final_error

        # Make sure every other still-enabled phone keeps running. We
        # do this whenever we touched v4l2loopback because the bridge
        # for a sibling phone may have been killed in the process.
        for other_key, other in list(rows.items()):
            if other_key == key:
                continue
            try:
                if "#archive" in other_key:
                    # Archived rows (an old /dev/videoN the user kept
                    # around while OBS still owns it) MUST NOT be
                    # touched — start_bridge would kill any leftover
                    # bridge and force a re-allocation that OBS would
                    # never see (it points at the old card_label).
                    continue
                if other.consumer_attached and Path(other.device_path).exists():
                    # Don't touch devices we marked as "OBS still owns
                    # this one" — start_bridge would kill the bridge but
                    # OBS would keep the old frames flowing anyway, and
                    # we'd waste time re-creating something OBS won't
                    # see because it points at the old card_label.
                    continue
                self.start_bridge(other)
                rows[other_key] = other
            except Exception as exc:  # noqa: BLE001
                self.log(str(exc))

        self.save_state(rows)

        if not Path(row.device_path).exists():
            raise RuntimeError(
                f"Dispositivo {row.device_path} assente. È installato v4l2loopback?"
            )
        return row, phone

    def _format_card_label(self, label: str, phone: PhoneDevice) -> str:
        """Compose the v4l2 card_label for a phone.

        Includes the resolution so the user can tell two webcams with
        the same device name apart when plugging multiple phones. The
        label is trimmed to 31 characters (v4l2 CARD_MAX_LEN) and the
        resolution suffix is preserved as much as possible.
        """
        suffix = (
            f" {phone.info.output_width}x{phone.info.output_height}"
        )
        base = label[: 31 - len(suffix)] if len(label) + len(suffix) > 31 else label
        return f"{base}{suffix}"

    def _device_is_locked(self, video_nr: int) -> bool:
        """True if the loopback node ``/dev/videoN`` is currently held by
        an external consumer (OBS, VLC, browser, …).

        Used during phone allocation: we must not reuse a slot that an
        external consumer has already opened, because re-creating the
        node would yank the camera out from under OBS.
        """
        path = f"/dev/video{video_nr}"
        if not Path(path).exists():
            return False
        try:
            proc = subprocess.run(
                ["fuser", path],
                capture_output=True,
                text=True,
                timeout=2.0,
            )
        except (FileNotFoundError, subprocess.TimeoutExpired):
            return False
        return bool((proc.stdout or "").strip())

    def _device_is_locked_external(self, video_nr: int) -> bool:
        """True if ``/dev/videoN`` is held by a process other than our
        own bridge writers (acus_driver / ffmpeg / curl).

        Unlike ``_device_is_locked`` which uses ``fuser`` and sees every
        opener, this method enumerates ``/proc`` and only flags external
        processes (OBS, VLC, browser, Teams, …). Our own writers are
        expected to be running — we only need to know whether *someone
        else* is holding the node so we can avoid yanking it from them.
        """
        path = f"/dev/video{video_nr}"
        if not Path(path).exists():
            return False
        for proc_dir in Path("/proc").iterdir():
            if not proc_dir.name.isdigit():
                continue
            try:
                exe = Path(os.readlink(proc_dir / "exe")).name
            except OSError:
                continue
            # Our own writers — these always open the device, ignore them.
            if exe in ("acus_driver", "ffmpeg", "curl"):
                continue
            try:
                cmdline = (
                    (proc_dir / "cmdline").read_bytes()
                    .replace(b"\0", b" ")
                    .decode(errors="replace")
                )
            except OSError:
                continue
            if path in cmdline:
                return True
        return False

    def _archive_slot_is_free(self, row: StateRow) -> bool:
        """True if an archived row's slot is genuinely free to reuse.

        An archived row should keep excluding its slot from allocation only
        while someone (OBS/VLC, or our own bridge writer) is still holding
        the node. When the bridge writer is dead AND no external consumer
        holds the node, the slot is free and the archived row must be pruned
        — otherwise it poisons ``_allocate_v4l2_slot``'s exclude set forever
        (the "Enable all" failure: "già in uso 2 slot su 10" with 2 phones
        after a transport switch that archived two rows on the only
        kernel-owned slots). Used by ``save_state``.

        Conservative default: on any error return False (keep the row) so
        we never free a slot that might still be in use.

        Special case: when our bridge writer (our own bridge) is dead but an
        external consumer (e.g. OBS, VLC, browser) still has the node open,
        we consider the slot free because a new writer can simply overwrite
        the feed — OBS will see the new feed. This is the case when
        ``consumer_attached=True`` and the bridge is dead.
        """
        if not Path(row.device_path).exists():
            return True  # node gone → slot is definitely free
        try:
            if self.is_bridge_running(row):
                return False  # our writer still alive (bridge still running)
            if self._device_is_locked_external(row.video_nr):
                # External consumer (OBS/VLC/browser) holds the node.
                # Our bridge writer is dead, so we can reuse the slot
                # (the new writer will overwrite the feed; OBS will see
                # the new feed). Only the case where our bridge writer is
                # still running makes it NOT free.
                return True
        except Exception:  # noqa: BLE001
            return False
        return True

    def _slot_is_usable(self, video_nr: int) -> bool:
        """Quick sanity check for a freshly-allocated /dev/videoN.

        Returns False when:
          * the node is missing or orphan (no sysfs entry),
          * v4l2-ctl cannot introspect it (kernel never claimed the minor).

        True when the slot is registered by a V4L2 driver and answers
        ``v4l2-ctl --all``. We deliberately keep the test cheap so the
        watchdog can call it for every row on every iteration.
        """
        path = Path(f"/dev/video{video_nr}")
        if not path.exists():
            return False
        sysfs = Path(f"/sys/class/video4linux/video{video_nr}")
        if not sysfs.is_dir():
            return False
        # Hold by an external consumer? Even our own bridge writers are
        # OK — we will replace them on the next start_bridge call.
        try:
            proc = subprocess.run(
                ["v4l2-ctl", "-d", str(path), "--all"],
                capture_output=True,
                text=True,
                timeout=2.0,
            )
        except (FileNotFoundError, subprocess.TimeoutExpired):
            return False
        return proc.returncode == 0

    def _kernel_owned_slots(self) -> set[int]:
        """Indices of ``/dev/videoN`` slots reserved by v4l2loopback.

        Source of truth is the ``device/driver`` symlink of each
        ``/sys/class/video4linux/videoN`` node: a slot is "ours" iff its
        backing driver resolves to ``v4l2loopback``. This correctly
        excludes real webcams (``uvcvideo`` & co.) and the acus USB driver,
        so the allocator never lands a phone onto the laptop's physical
        camera.

        We deliberately do NOT parse ``parameters/video_nr`` anymore. Under
        auto-assignment (modprobe ``devices=N`` with NO ``video_nr``
        pinning — the only mode that works for N>8 on the dkms in use, whose
        ``video_nr`` array is fixed at 8 entries) the kernel writes
        ``-1,-1,…,-1`` into ``video_nr``; filtering ``nr >= 0`` then yields
        an EMPTY set, and the previous fallback globbed every
        ``/sys/class/video4linux/video*`` node — webcam minors included —
        which is how a phone got assigned to /dev/video1 (a real UVC cam)
        and ffmpeg failed with ``VIDIOC_G_FMT Invalid argument``.

        The driver-symlink pattern mirrors the ALREADY-correct count in
        :meth:`_loaded_v4l2_device_count` so the allocator and the count
        cannot disagree.

        Last-resort fallback: if NO slot matched via the driver symlink
        (older v4l2loopback builds where ``/sys/class/video4linux/videoN/
        device/driver`` is not exposed, or a transient permissions race
        during a udev settle) we fall back to every ``videoN`` with a
        non-empty ``name`` — imprecise (may include non-loopback devices)
        but better than allocating NOTHING on such systems, mirroring the
        same fallback in :meth:`_loaded_v4l2_device_count`. On the dkms in
        use on the developer machine the driver symlink is present, so this
        branch never runs in practice.
        """
        owned: set[int] = set()
        base = Path("/sys/class/video4linux")
        if not base.is_dir():
            return owned
        for entry in base.glob("video*"):
            token = entry.name.removeprefix("video")
            if not token.isdigit():
                continue
            try:
                drv = (entry / "device" / "driver").resolve()
            except OSError:
                drv = None
            if drv is not None and drv.name == "v4l2loopback":
                owned.add(int(token))
        if owned:
            return owned
        # Last resort: every videoN with a non-empty name. Imprecise but
        # better than an empty set (which would surface a spurious "raise
        # V4L2_SLOTS" error) on builds without the driver symlink.
        for entry in base.glob("video*"):
            token = entry.name.removeprefix("video")
            if not token.isdigit():
                continue
            try:
                if (entry / "name").read_text(errors="replace").strip():
                    owned.add(int(token))
            except OSError:
                continue
        return owned

    def _owned_slot_upper_bound(self) -> int:
        """Highest ``/dev/videoN`` index the kernel reserved for v4l2loopback.

        Bounds the allocator to the *real* slot range instead of a fixed
        ``256``: we only ever hand out an index the kernel module actually
        owns, so we can never land a phone on a real webcam packed above
        the loopback range. The bound is ``max(_kernel_owned_slots())`` (the
        highest minor whose driver is ``v4l2loopback``). Falls back to
        :data:`_MAX_VIDEO_NR` (255, the videodev minor space) when no
        loopback slot is detected — the caller's ``owned`` set is the real
        guard, this is just the scan ceiling.
        """
        owned = self._kernel_owned_slots()
        if owned:
            return max(owned)
        return _MAX_VIDEO_NR

    def _allocate_v4l2_slot(self, exclude: set[int]) -> int | None:
        """Pick the first free ``/dev/videoN`` slot we can write a phone
        stream into — the "try every slot one by one" allocator.

        Scan linearly from /dev/video0 upward up to the last slot run.sh
        could have created (``_owned_slot_upper_bound``). For each candidate:
          * skip if it is in ``exclude`` (already assigned to another
            active phone the GUI is managing right now — we never overlay
            a 2nd writer on a slot a live sibling phone is already feeding);
          * skip if it is an orphan node (sysfs entry missing);
          * AUTHORITATIVE TEST: ``_is_writable_loopback(nr)`` — run
            ``v4l2-ctl --get-fmt-video-out`` (read-only). v4l2loopback is
            OUTPUT-capable so the probe succeeds (exit 0); a real UVC
            webcam is CAPTURE-only and the kernel rejects it with
            ``EINVAL``. This is the test that actually matches the
            operation the bridge later performs (ffmpeg ``-f v4l2`` output),
            so a slot that fails here can never host a phone — we move on.

        NOTABLY we do NOT skip a slot merely because a consumer (OBS/VLC/
        browser) has it OPEN: v4l2loopback allows a writer (ffmpeg/
        acus_driver) to coexist with a reader (OBS) on the same
        ``/dev/videoN`` — the new writer simply replaces the live feed any
        reader sees. The user confirmed empirically that an OBS-held node
        is still writable (running ``acus_driver --device /dev/video0``
        manually worked). So an OBS-held DISABLED slot is reusable for a
        new phone, and skipping it would leak slots upward forever (the
        "Disable all then re-Enable walked to /dev/video6,7,8" bug). The
        only slots that MUST stay exclusive are those a LIVE sibling phone
        is feeding — covered by the ``exclude`` set built in
        :meth:`enable_phone`.

        Every per-slot probe is wrapped in try/except: if probing one
        candidate raises (transient sysfs/udev race, permission hiccup,
        ``v4l2-ctl`` not installed, …) we LOG the exception and CONTINUE
        to the next slot instead of aborting the whole allocation. This
        "continue on exception, up to the last slot" behaviour was requested
        explicitly so a single bad slot never blocks enabling the other
        phones. If we exhaust every slot we return ``None`` and the caller
        surfaces a clear "raise V4L2_SLOTS / close consumers / disconnect a
        phone" error.

        ``owned`` (``_kernel_owned_slots``) is used only as a HINT to skip
        slot indices that are clearly not v4l2loopback (so we don't spawn
        ``v4l2-ctl`` for every real webcam in the box): when the driver
        symlink is unreliable the hint is empty and we fall back to probing
        every candidate — the ``_is_writable_loopback`` test is the real
        authority either way.
        """
        self._cleanup_orphan_nodes(max_nr=64)
        owned = self._kernel_owned_slots()
        upper = self._owned_slot_upper_bound()
        # Linear retry from /dev/video0 upward. Lower indices win; the only
        # hard skip is the exclude set (a slot a live sibling phone is
        # feeding). Orphans fail the sysfs check; non-loopback slots fail
        # the _is_writable_loopback probe. An OBS-held (but bridge-dead)
        # slot is NOT skipped: v4l2loopback lets a new writer overlay the
        # node, so the slot is reusable.
        for nr in range(0, upper + 1):
            if nr in exclude:
                continue
            # Hint: skip indices clearly not owned by v4l2loopback when the
            # driver-symlink scan is available. When 'owned' is empty we
            # probe every candidate (rely on _is_writable_loopback alone).
            if owned and nr not in owned:
                continue
            try:
                if self._is_orphan_node(nr):
                    continue
                #if self._device_is_locked(nr):
                #    continue
                if not self._is_writable_loopback(nr):
                    # /dev/videoN is not OUTPUT-capable: a real webcam, an
                    # orphan, or a node the kernel didn't reserve for
                    # v4l2loopback. Move on to the next slot.
                    continue
            except Exception as exc:  # noqa: BLE001
                self.log(
                    f"Slot /dev/video{nr} check sollevato {exc!r}; salto."
                )
                continue
            return nr
        return None

    def disable_all_phones(self) -> int:
        """Stop every active bridge and FREE the slots it held, returning the
        number of phones stopped.

        For each row we stop_bridge (kills the ffmpeg/acus_driver writer and
        releases the loopback format). Then:
          * if no EXTERNAL consumer (OBS/VLC/browser) holds the /dev/videoN
            node, we DROP the row from state — so its ``video_nr`` leaves
            the exclude set of ``_allocate_v4l2_slot`` and the slot is
            reused on the next "Enable". This was the root cause of the
            "Disable all then re-Enable walked to /dev/video6,7,8 instead
            of reusing 0,3,4" bug: the old code left every row in state
            (never called save_state) so the exclude set kept the slots
            reserved forever.
          * if OBS still holds the node we KEEP the row (marking it
            consumer_attached) so a subsequent Enable does not yank a node
            a consumer is actively reading; the row is pruned later by
            save_state once OBS releases it (``_archive_slot_is_free``).
        """
        rows = self.load_state()
        stopped = 0
        for key in list(rows.keys()):
            row = rows[key]
            try:
                self.stop_bridge(row)
                stopped += 1
            except Exception as exc:  # noqa: BLE001
                self.log(f"disable_all: stop {row.label} fallita — {exc}")
                continue
            # Decide whether the slot is genuinely free (no external reader)
            # and can be released for reuse, or is still held by a consumer
            # (OBS/VLC/browser) and must stay reserved.
            try:
                held = self._device_is_locked_external(row.video_nr)
            except Exception:  # noqa: BLE001
                held = True  # conservative: keep the row, never yank.
            if held:
                row.consumer_attached = True
                rows[key] = row
                self.log(
                    self.strings.log_disable_phone_held.format(
                        name=row.label, device=row.device_path
                    )
                )
            else:
                rows.pop(key, None)
                self.log(
                    self.strings.log_disable_phone_released.format(
                        device=row.device_path
                    )
                )
        self.save_state(rows)
        return stopped

    def disable_phone(self, label_or_key: str) -> None:
        """Stop the bridge and drop the phone from state. Does not touch modprobe.

        Accepts either the human-readable card_label (the column shown in
        the GUI tree) or the stable key (``label@port``). We resolve the
        key first so the user can disable a specific phone even when two
        phones share the same name on different transports.

        After stopping the bridge, checks whether an external consumer
        (OBS, VLC, browser, …) still holds the node. If so, the slot
        cannot be released and a specific message is logged; otherwise
        the slot is released for reuse.
        """
        rows = self.load_state()
        # Try the key first; fall back to the label for the legacy path.
        row = rows.pop(label_or_key, None)
        if row is None:
            # Maybe the caller passed the human label, not the key.
            for k, r in list(rows.items()):
                if r.label == label_or_key or k.startswith(f"{label_or_key}@"):
                    row = rows.pop(k, None)
                    break
        if row is not None:
            self.stop_bridge(row)
            # Decide whether the slot is genuinely free or still held
            # by an external consumer (OBS/VLC/browser).
            try:
                held = self._device_is_locked_external(row.video_nr)
            except Exception:  # noqa: BLE001
                held = True  # conservative: keep the row, never yank
            if held:
                row.consumer_attached = True
                rows[label_or_key] = row  # type: ignore[name-defined]  # noqa: F821
                self.log(
                    self.strings.log_disable_phone_held.format(
                        name=row.label, device=row.device_path
                    )
                )
            else:
                self.log(
                    self.strings.log_disable_phone_released.format(
                        device=row.device_path
                    )
                )
            self.save_state(rows)
        if not rows:
            self.log("Nessuna webcam attiva nello state.")

    def reload_devices(self) -> None:
        """
        Rebuild v4l2loopback from current state without disturbing any
        consumer-attached device.

        The contract: we MUST NOT yank a /dev/videoN out from under OBS
        (or any other consumer). Rows tagged ``consumer_attached`` are
        preserved with their existing node; only rows that nobody is
        using get re-created. We only unload v4l2loopback when:
          * no row in state is currently consumer-attached, AND
          * all our own writers can be stopped, AND
          * the module was previously loaded with fewer devices than we
            need now.
        """
        rows = self.load_state()
        if not rows:
            if self._module_loaded():
                unload = self._run_root(
                    ["modprobe", "-r", "v4l2loopback"], timeout=20
                )
                if unload.returncode != 0:
                    raise RuntimeError(
                        "Impossibile scaricare v4l2loopback (dispositivo occupato).\n"
                        "Chiudi o stacca i consumatori V4L2 ancora collegati e riprova.\n"
                        + (unload.stderr or unload.stdout or "")
                    )
                self.log("Modulo v4l2loopback scaricato.")
            else:
                self.log("Nessuna webcam nello state e modulo già assente.")
            return

        # Refresh the consumer_attached flag by checking the live
        # /sys/class/video4linux tree; we cannot trust the persisted
        # value across an OBS restart.
        for row in rows.values():
            row.consumer_attached = self._device_is_locked(row.video_nr)

        # Free our writers (do not touch OBS). Devices marked as
        # consumer-attached get a brand new /dev/videoN allocated at a
        # different index when we cannot reach their current slot —
        # the original keeps serving OBS until it releases the source.
        for row in rows.values():
            if not row.consumer_attached:
                self._release_device_readers(row.device_path)

        # Decide whether a real unload is possible. We avoid it whenever
        # any row is still consumer-attached.
        any_attached = any(r.consumer_attached for r in rows.values())
        if not any_attached:
            self.load_v4l2(rows)
        else:
            self.log(
                "Alcuni device sono ancora in uso da un consumer esterno "
                "(OBS/browser/VLC): salto il reload del modulo e creo solo "
                "i /dev/videoN mancanti."
            )
            for row in rows.values():
                self._ensure_v4l2_node(row.video_nr)

        # Restart bridges for every active row. consumer_attached rows
        # keep the bridge alive on the SAME node so OBS keeps seeing
        # frames on its current source.
        for row in sorted(rows.values(), key=lambda r: r.video_nr):
            try:
                self.start_bridge(row)
            except Exception as exc:  # noqa: BLE001
                self.log(str(exc))
        self.save_state(rows)

    def status_for(self, label: str) -> tuple[bool, str]:
        """Look up the bridge status for a phone.

        The GUI passes a sanitised human label (the device name shown in
        the tree). With multiple phones per machine we may have several
        rows whose label starts with the same prefix (e.g. ``AndroidCamera``
        and ``AndroidCamera 1024x768``). We pick the row whose key/port
        best matches — and fall back to a prefix search if the caller
        does not know the port.

        Returns ``(False, "")`` when no bridge is currently running for
        this label so callers can distinguish "no bridge" from "bridge
        running on /dev/videoN". An older version returned the device
        path unconditionally, which made the InAppPreviewWindow try to
        read frames from a stale /dev/videoN even when no bridge was
        active — producing a black canvas with no error.
        """
        rows = self.load_state()
        target = sanitize_label(label)
        # Direct key lookup first: caller passed an exact ``label@port``.
        row = rows.get(target)
        if row is not None:
            running = self.is_bridge_running(row)
            if running and Path(row.device_path).exists():
                return True, row.device_path
            return False, ""
        # Otherwise, find the first row whose label matches the requested
        # base. Prefer the row without a resolution suffix ("AndroidCamera"
        # over "AndroidCamera 1024x768") because that's what the GUI
        # tree usually displays.
        matches = [r for r in rows.values() if r.label.startswith(target)]
        if not matches:
            return False, ""
        matches.sort(key=lambda r: (len(r.label), r.video_nr))
        row = matches[0]
        running = self.is_bridge_running(row)
        if running and Path(row.device_path).exists():
            return True, row.device_path
        return False, ""

    # ── streaming-stop watchdog ────────────────────────────────────

    def is_phone_streaming(self, row: StateRow) -> bool:
        """Return True if the phone backing ``row`` is currently streaming.

        Polls /stream.info (HTTP transport) or the ACUS probe (USB Device
        transport) with a tight timeout. The watchdog uses this to
        detect that the user stopped the stream from the Kotlin app and
        react by running ``disable_phone`` automatically.
        """
        if not row.stream_url:
            return False
        try:
            if row.stream_url.startswith("acus://"):
                # ACUS transport: use the binary probe.
                from . import acus_driver
                parsed = acus_driver.parse_acus_url(row.stream_url)
                if parsed is None:
                    return False
                serial, _host, port = parsed
                if not serial:
                    return False
                data = acus_driver.probe(serial, port, timeout=1.0)
                return data is not None
            # HTTP transport: hit /stream.info and check ``ready``.
            import urllib.request
            import json as _json
            from urllib.error import URLError
            info_url = row.stream_url.rsplit("/", 1)[0] + "/stream.info"
            with urllib.request.urlopen(info_url, timeout=1.0) as resp:
                payload = _json.loads(resp.read().decode("utf-8", errors="replace"))
            return bool(payload.get("ready"))
        except Exception:
            return False

    def watchdog_disable_if_stopped(
        self,
        key: str,
        rows: dict[str, StateRow],
        offline_since: dict[str, float | None] | None = None,
        grace_period: float = 0.0,
    ) -> bool:
        """If the phone backing ``key`` stopped streaming on the Kotlin
        side, drop the corresponding row and return True. The caller
        (the GUI watchdog loop) is then expected to refresh the tree.

        When ``offline_since`` is provided (a mutable dict shared across
        iterations of the watchdog loop) and ``grace_period > 0``, the
        first offline detection is recorded and the phone is NOT disabled
        until ``grace_period`` seconds have passed — a brief network
        glitch (WiFi re-association, transient TCP timeout) does not
        kill the stream.

        We also stop our own bridge writers so the /dev/videoN node
        becomes free for the next "Enable webcam" click. We deliberately
        do NOT unload v4l2loopback: a subsequent re-enable will re-bind
        the same node (or a new one, if the user changed resolution).

        Liveness gate: a transient probe failure (the ADB/USB server
        hiccups during the GUI's periodic device scan — visible in the
        log as ``ADB devices found: 0``) makes ``is_phone_streaming`` return
        False even though our own ``acus_driver``/``ffmpeg`` writer is still
        alive and feeding the loopback. When that happens we treat the
        silence as a *probe* failure, NOT a stream failure: the grace timer
        is reset and the row is kept. The phone is only disabled when the
        probe fails AND no bridge writer is running.
        """
        row = rows.get(key)
        if row is None:
            return False
        # Archive rows are already retired — never re-test them.
        if "#archive" in (row.key or row.label):
            return False
        # Phone still streaming: nothing to do (reset offline tracking).
        if self.is_phone_streaming(row):
            if offline_since is not None:
                offline_since.pop(key, None)
            # Clear the rate-limit marker so the next probe-hiccup logs again.
            seen = getattr(type(self).watchdog_disable_if_stopped, "_bridge_alive_logged", set())
            if key in seen:
                seen = set(seen) - {key}
                type(self).watchdog_disable_if_stopped._bridge_alive_logged = seen  # type: ignore[attr-defined]
            return False
        # Probe said "not streaming". Before deciding the phone is really
        # gone, check the bridge writer is actually dead: if our own
        # acus_driver/ffmpeg is still alive and writing frames to the
        # loopback the phone IS still being served — the probe failure is a
        # transient ADB/USB hiccup (e.g. mid device-scan), not a stop. Reset
        # the grace timer and keep the row; never disable a live bridge.
        bridge_alive = False
        try:
            bridge_alive = self.is_bridge_running(row)
        except Exception:  # noqa: BLE001
            # If we cannot determine liveness, do NOT assume the phone is
            # dead — keep the row and let the next iteration re-probe.
            bridge_alive = True
        if bridge_alive:
            if offline_since is not None and key in offline_since:
                offline_since.pop(key, None)
            # Rate-limit: log the "probe dirty but bridge alive" line once
            # per entering of this state, not on every watchdog tick.
            seen = getattr(type(self).watchdog_disable_if_stopped, "_bridge_alive_logged", set())
            if key not in seen:
                self.log(
                    f"Watchdog: «{row.label}» probe sporca ma bridge vivo "
                    f"({row.stream_url}); non disable."
                )
                seen = set(seen) | {key}
                type(self).watchdog_disable_if_stopped._bridge_alive_logged = seen  # type: ignore[attr-defined]
            return False
        # Phone did not respond and the bridge writer is not running. If we
        # have a grace period, check how long it has been offline.
        if offline_since is not None and grace_period > 0.0:
            t0 = offline_since.get(key)
            now = time.monotonic()
            if t0 is None:
                # First time we notice the phone is offline — start the
                # grace timer and do NOT disable it yet.
                offline_since[key] = now
                self.log(
                    f"Watchdog: «{row.label}» non risponde — "
                    f"aspetto {grace_period:.0f} s prima di disabilitare."
                )
                return False
            elapsed = now - t0
            if elapsed < grace_period:
                # Still within the grace window — keep waiting.
                return False
        # Phone went away (or grace period expired): stop the bridge
        # and drop the row.
        self.log(
            f"Watchdog: «{row.label}» non sta più streaming sull'app "
            f"({row.stream_url}); eseguo disable automatico."
        )
        try:
            self.stop_bridge(row)
        except Exception as exc:  # noqa: BLE001
            self.log(f"Watchdog: stop_bridge fallita per {row.label} — {exc}")
        rows.pop(key, None)
        self.save_state(rows)
        return True
