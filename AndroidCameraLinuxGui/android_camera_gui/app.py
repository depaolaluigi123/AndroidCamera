from __future__ import annotations

import subprocess
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox
from typing import Callable
from pathlib import Path

from .adb_tools import AdbClient, ensure_adb_available
from .bridge import BridgeManager, WATCHDOG_POLL_S
from .discovery import DEFAULT_PORTS, discover_phones, fetch_stream_info
from .i18n import Strings, get_strings, load_saved_language, save_language
from .in_app_preview import InAppPreviewWindow
from .models import PhoneDevice


class _EndpointUnreachableError(Exception):
    """Raised when a selected endpoint is no longer reachable."""

    def __init__(self, endpoint: str):
        self.endpoint = endpoint


class AndroidCameraGui(tk.Tk):
    def __init__(self) -> None:
        super().__init__()
        self.s: Strings = get_strings(load_saved_language())
        self.minsize(900, 520)
        self.geometry("1100x600")

        self.bridge = BridgeManager(log=self._append_log, strings=self.s)
        self.phones: list[PhoneDevice] = []
        # Manual IP sources added by user: set of (host, port) tuples
        self.manual_endpoints: set[tuple[str, int]] = set()
        self._busy = False
        # Reference to the modal in-app preview window (only one at a time).
        self._preview_window: InAppPreviewWindow | None = None

        self._build_ui()
        self._apply_strings()

        # Ensure adb is available (download if needed) AFTER UI is built so log works
        self.after(100, self._ensure_adb_then_refresh)

        # Watchdog: every WATCHDOG_POLL_S seconds, check whether every
        # currently-enabled row in state is still being streamed by the
        # Kotlin app. When the user stops the service from the app the
        # /stream.info endpoint goes silent; the watchdog then runs
        # ``disable_phone`` automatically so the user does not have to
        # click "Disable" by hand. Run on a dedicated background thread
        # so the Tk main loop is never blocked.
        self._watchdog_stop = threading.Event()
        self._watchdog_thread = threading.Thread(
            target=self._watchdog_loop, name="phone-watchdog", daemon=True
        )
        self._watchdog_thread.start()

    def _ensure_adb_then_refresh(self) -> None:
        """Ensure adb is available, then start initial device scan."""
        try:
            ensure_adb_available(log=self._append_log)
            self.adb = AdbClient()
        except Exception as e:
            messagebox.showerror(
                self.s.dialog_error,
                f"Impossibile ottenere adb:\n{e}\n\nL'applicazione verrà chiusa."
            )
            self.destroy()
            return
        self.refresh_async()

    def _watchdog_loop(self) -> None:
        """Background loop: detect phones whose stream has gone silent.

        Runs every WATCHDOG_POLL_S seconds. For every row in the bridge
        state file we ask the bridge whether the phone is still
        streaming; when a phone stops responding we give it a 5-second
        grace period before disabling it — a brief network glitch (e.g.
        WiFi re-association) should not kill the stream.

        The watchdog deliberately lives on its own thread (not on the
        Tk ``after`` queue) so a slow /stream.info probe cannot block
        the GUI: Tk ``after`` runs on the main loop and a 1 s timeout
        per row would freeze the tree for tens of seconds when several
        phones are enabled.
        """
        # Grace period: track when each phone first appeared offline.
        # Key = phone key, value = monotonic time when the offline
        # state was first detected (None = still online).
        _offline_since: dict[str, float | None] = {}
        # Stagger the first iteration a bit so we don't race with the
        # initial ``refresh_async`` that just populated the tree.
        if self._watchdog_stop.wait(timeout=2.0):
            return
        while not self._watchdog_stop.is_set():
            try:
                rows = self.bridge.load_state()
                changed = False
                for key in list(rows.keys()):
                    try:
                        result = self.bridge.watchdog_disable_if_stopped(
                            key, rows, offline_since=_offline_since,
                            grace_period=5.0,
                        )
                        if result:
                            # Phone was disabled — remove from tracking.
                            _offline_since.pop(key, None)
                            changed = True
                        else:
                            # Phone is either still online or within the
                            # grace period — keep it.
                            pass
                    except Exception as exc:  # noqa: BLE001
                        self._append_log(
                            f"Watchdog errore su {key}: {exc}"
                        )
                if changed:
                    # Refresh the device tree on the main thread so the
                    # user sees the disabled row drop out.
                    self.after(0, self._fill_tree)
            except Exception as exc:  # noqa: BLE001
                # Never let a transient failure kill the watchdog.
                self._append_log(f"Watchdog loop error: {exc}")
            # Use Event.wait so KeyboardInterrupt / app shutdown can
            # cancel the sleep instantly instead of waiting for the
            # next tick.
            if self._watchdog_stop.wait(timeout=WATCHDOG_POLL_S):
                return

    def _build_ui(self) -> None:
        style = ttk.Style(self)
        if "clam" in style.theme_names():
            style.theme_use("clam")

        main = ttk.Frame(self, padding=14)
        main.pack(fill=tk.BOTH, expand=True)

        header = ttk.Frame(main)
        header.pack(fill=tk.X)
        self.lbl_header = ttk.Label(header, font=("Sans", 14, "bold"))
        self.lbl_header.pack(side=tk.LEFT)
        self.status_var = tk.StringVar(master=self)
        ttk.Label(header, textvariable=self.status_var).pack(side=tk.RIGHT)

        lang_bar = ttk.Frame(main)
        lang_bar.pack(fill=tk.X, pady=(6, 0))
        self.btn_lang_en = ttk.Button(
            lang_bar, width=4, command=lambda: self.set_language("en")
        )
        self.btn_lang_en.pack(side=tk.RIGHT)
        self.btn_lang_it = ttk.Button(
            lang_bar, width=4, command=lambda: self.set_language("it")
        )
        self.btn_lang_it.pack(side=tk.RIGHT, padx=(0, 6))

        self.lbl_hint = ttk.Label(main, wraplength=860)
        self.lbl_hint.pack(fill=tk.X, pady=(8, 10))

        tree_wrap = ttk.Frame(main)
        tree_wrap.pack(fill=tk.BOTH, expand=True)

        columns = (
            "name", "model", "endpoint", "resolution",
            "fps", "format", "quality", "obs",
        )
        self.tree = ttk.Treeview(
            tree_wrap,
            columns=columns,
            show="headings",
            selectmode="browse",
            height=6,
        )
        self.tree.column("name", width=140, anchor=tk.W)
        self.tree.column("model", width=120, anchor=tk.W)
        self.tree.column("endpoint", width=180, anchor=tk.W)
        self.tree.column("resolution", width=100, anchor=tk.CENTER)
        self.tree.column("fps", width=50, anchor=tk.CENTER)
        self.tree.column("format", width=70, anchor=tk.CENTER)
        self.tree.column("quality", width=70, anchor=tk.CENTER)
        self.tree.column("obs", width=120, anchor=tk.W)

        scroll = ttk.Scrollbar(tree_wrap, orient=tk.VERTICAL, command=self.tree.yview)
        self.tree.configure(yscrollcommand=scroll.set)
        self.tree.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scroll.pack(side=tk.RIGHT, fill=tk.Y)

        buttons = ttk.Frame(main)
        buttons.pack(fill=tk.X, pady=(10, 6))
        self.btn_refresh = ttk.Button(buttons, command=self.refresh_async)
        self.btn_refresh.pack(side=tk.LEFT)
        # Single enable button: hosts the phone as /dev/videoN (no OBS coupling).
        self.btn_enable = ttk.Button(buttons, command=self.enable_selected)
        self.btn_enable.pack(side=tk.LEFT, padx=(8, 0))
        self.btn_disable = ttk.Button(buttons, command=self.disable_selected)
        self.btn_disable.pack(side=tk.LEFT, padx=(8, 0))
        # Enable-all: enables every row in the current table.
        # Placed between "Enable webcam" and "Disable Webcam" / disable-all.
        self.btn_enable_all = ttk.Button(buttons, command=self.enable_all)
        self.btn_enable_all.pack(side=tk.LEFT, padx=(8, 0))
        # Disable-all: stops every active bridge without forgetting state.
        self.btn_disable_all = ttk.Button(buttons, command=self.disable_all)
        self.btn_disable_all.pack(side=tk.LEFT, padx=(8, 0))
        # Reload devices (v4l2) button: COMMENTED OUT to avoid accidental clicks.
        # The code for reload_devices() is kept — it may be useful in future.
        # self.btn_reload_devices = ttk.Button(buttons, command=self.reload_devices)
        # self.btn_reload_devices.pack(side=tk.LEFT, padx=(8, 0))
        self.btn_add_ip_source = ttk.Button(buttons, command=self._add_ip_source)
        self.btn_add_ip_source.pack(side=tk.LEFT, padx=(8, 0))
        # In-app preview (replaces the Vue.js "Live preview" browser).
        self.btn_in_app_preview = ttk.Button(buttons, command=self.in_app_preview_selected)
        self.btn_in_app_preview.pack(side=tk.LEFT, padx=(8, 0))
        self.btn_exit = ttk.Button(buttons, command=self.destroy)
        self.btn_exit.pack(side=tk.RIGHT)

        # Ports hint label shown after refresh
        self.ports_hint_var = tk.StringVar()
        self.lbl_ports_hint = ttk.Label(main, textvariable=self.ports_hint_var, wraplength=860)
        self.lbl_ports_hint.pack(fill=tk.X, pady=(4, 8))

        self.log_frame = ttk.LabelFrame(main, padding=6)
        self.log_frame.pack(fill=tk.BOTH, expand=False, pady=(0, 0))
        self.log = tk.Text(self.log_frame, height=10, wrap=tk.WORD, state=tk.DISABLED)
        self.log.pack(fill=tk.BOTH, expand=True)

    def _apply_strings(self) -> None:
        s = self.s
        self.title(s.window_title)
        self.lbl_header.configure(text=s.header_title)
        self.lbl_hint.configure(text=s.hint)
        self.status_var.set(s.status_ready)
        self.tree.heading("name", text=s.col_name)
        self.tree.heading("model", text=s.col_model)
        self.tree.heading("endpoint", text=s.col_endpoint)
        self.tree.heading("resolution", text=s.col_resolution)
        self.tree.heading("fps", text=s.col_fps)
        self.tree.heading("format", text=s.col_format)
        self.tree.heading("quality", text=s.col_quality)
        self.tree.heading("obs", text=s.col_obs)
        self.btn_refresh.configure(text=s.btn_refresh)
        # btn_enable doubles as the "Enable webcam" button (OBS sync gone).
        self.btn_enable.configure(text=s.btn_enable_webcam)
        self.btn_disable.configure(text=s.btn_disable)
        self.btn_disable_all.configure(text=s.btn_disable_all)
        # btn_reload_devices is commented out; the code is kept for future use.
        # self.btn_reload_devices.configure(text=s.btn_reload_devices)
        self.btn_add_ip_source.configure(text=s.btn_add_ip_source)
        self.btn_in_app_preview.configure(text=s.btn_in_app_preview)
        self.btn_enable_all.configure(text=s.btn_enable_all)
        self.btn_exit.configure(text=s.btn_exit)
        self.btn_lang_en.configure(text=s.btn_lang_en)
        self.btn_lang_it.configure(text=s.btn_lang_it)
        self.ports_hint_var.set(s.ports_hint)
        self.log_frame.configure(text=s.log_title)
        self._fill_tree()

    def _module_loaded(self, module_name: str) -> bool:
        """Check if a kernel module is currently loaded."""
        try:
            text = Path("/proc/modules").read_text(encoding="utf-8")
            return any(line.startswith(f"{module_name} ") for line in text.splitlines())
        except OSError:
            return False

    def set_language(self, language_code: str) -> None:
        self.s = get_strings(language_code)
        save_language(self.s.language_code)
        self.bridge.strings = self.s
        self._apply_strings()
        self._append_log(self.s.log_lang_changed)

    def _append_log(self, message: str) -> None:
        def ui() -> None:
            self.log.configure(state=tk.NORMAL)
            self.log.insert(tk.END, message.rstrip() + "\n")
            self.log.see(tk.END)
            self.log.configure(state=tk.DISABLED)

        self.after(0, ui)

    def _set_busy(self, busy: bool, status: str | None = None) -> None:
        self._busy = busy
        state = tk.DISABLED if busy else tk.NORMAL
        self.btn_refresh.configure(state=state)
        self.btn_enable.configure(state=state)
        self.btn_disable.configure(state=state)
        # btn_reload_devices is commented out; keep the function code for future use.
        # self.btn_reload_devices.configure(state=state)
        self.btn_add_ip_source.configure(state=state)
        self.btn_in_app_preview.configure(state=state)
        self.btn_enable_all.configure(state=state)
        self.btn_disable_all.configure(state=state)
        if status is not None:
            self.status_var.set(status)

    def _selected_phone(self) -> PhoneDevice | None:
        sel = self.tree.selection()
        if not sel:
            return None
        idx = int(sel[0])
        if 0 <= idx < len(self.phones):
            return self.phones[idx]
        return None

    def _fill_tree(self) -> None:
        s = self.s
        self.tree.delete(*self.tree.get_children())
        for i, phone in enumerate(self.phones):
            running, device = self.bridge.status_for(phone.label)
            if running and device:
                obs = s.obs_active.format(device=device)
            elif device:
                obs = s.obs_node.format(device=device)
            else:
                obs = s.obs_disabled
            if phone.transport == "acus":
                endpoint = s.endpoint_usb_device.format(port=phone.port)
            else:
                endpoint = phone.endpoint_label or f"{phone.host}:{phone.port}"

            # Format / quality — show JPEG or YUV with negotiated options.
            fmt_label = (
                s.format_label_jpeg if phone.info.is_jpeg else s.format_label_yuv
            )
            quality_value = (
                f"{phone.info.jpeg_quality}%"
                if phone.info.is_jpeg
                else "—"
            )
            self.tree.insert(
                "",
                tk.END,
                iid=str(i),
                values=(
                    phone.display_name,
                    phone.model,
                    endpoint,
                    f"{phone.info.output_width}×{phone.info.output_height}",
                    s.fps_unlimited if phone.info.display_fps == 0 else str(phone.info.display_fps),
                    fmt_label,
                    quality_value,
                    obs,
                ),
            )

    def refresh_async(self) -> None:
        if self._busy:
            return
        self._set_busy(True, self.s.status_scanning)
        self._append_log(self.s.log_refresh)
        # Show the ports being scanned
        ports_str = ", ".join(str(p) for p in DEFAULT_PORTS)
        self._append_log(self.s.log_ports_scanned.format(ports=ports_str))
        self._append_log(self.s.log_ports_manual_entry)

        def work() -> None:
            try:
                # Discover USB devices via ADB
                phones = discover_phones(self.adb, log=self._append_log, strings=self.s)

                # Build a map of existing manual phones to preserve them
                existing_manual: dict[tuple[str, int], PhoneDevice] = {}
                for p in self.phones:
                    if p.serial.startswith("manual-"):
                        existing_manual[(p.host, p.port)] = p

                # Verify manual IP endpoints
                manual_phones: list[PhoneDevice] = []
                dead_endpoints: set[tuple[str, int]] = set()

                for host, port in self.manual_endpoints:
                    info = fetch_stream_info(host, port, timeout=0.3)
                    if info is not None:
                        serial = f"manual-{host}-{port}"
                        model = info.device_name
                        phone = PhoneDevice(
                            serial=serial,
                            model=model,
                            host=host,
                            port=port,
                            info=info,
                            transport="http",
                            endpoint_label=f"Wi-Fi ({host})",
                        )
                        # Preserve the existing manual phone entry (so the
                        # user's selected row stays selected across Refresh
                        # clicks), but ALWAYS update its `info` with the
                        # freshly probed values: resolution, fps, jpeg
                        # quality and stream format can all change between
                        # Refreshes once the user tweaked them on the phone.
                        if (host, port) in existing_manual:
                            existing = existing_manual[(host, port)]
                            refreshed = PhoneDevice(
                                serial=existing.serial,
                                model=existing.model,
                                host=existing.host,
                                port=existing.port,
                                info=info,
                                transport=existing.transport,
                                endpoint_label=existing.endpoint_label,
                            )
                            manual_phones.append(refreshed)
                        else:
                            manual_phones.append(phone)
                            self._append_log(
                                self.s.log_wifi_found.format(
                                    name=phone.display_name,
                                    width=info.output_width,
                                    height=info.output_height,
                                    fps=info.display_fps,
                                    host=host,
                                    port=port,
                                )
                            )
                    else:
                        dead_endpoints.add((host, port))
                        self._append_log(
                            self.s.log_manual_endpoint_removed.format(host=host, port=port)
                        )

                # Remove dead endpoints from tracking
                self.manual_endpoints -= dead_endpoints

                # Combine USB and manual phones
                all_phones = phones + manual_phones
                err: Exception | None = None
            except Exception as exc:  # noqa: BLE001
                all_phones = []
                err = exc

            def done() -> None:
                if err is not None:
                    self._append_log(self.s.log_error.format(error=err))
                    messagebox.showerror(self.s.dialog_error, str(err))
                self.phones = all_phones
                self._fill_tree()
                if len(all_phones) == 1:
                    status = self.s.status_phones_one.format(n=1)
                else:
                    status = self.s.status_phones_many.format(n=len(all_phones))
                self._set_busy(False, status)

            self.after(0, done)

        threading.Thread(target=work, daemon=True).start()

    def _run_job(self, title: str, fn: Callable[[], None]) -> None:
        if self._busy:
            return
        self._set_busy(True, title)

        def work() -> None:
            err: Exception | None = None
            try:
                fn()
            except Exception as exc:  # noqa: BLE001
                err = exc

            def done() -> None:
                if err is not None:
                    self._append_log(self.s.log_error.format(error=err))
                    messagebox.showerror(self.s.dialog_error, str(err))
                else:
                    self._append_log(self.s.log_done)
                self._fill_tree()
                self._set_busy(False, self.s.status_ready)

            self.after(0, done)

        threading.Thread(target=work, daemon=True).start()

    def enable_selected(self) -> None:
        """Create the v4l2 device for the selected phone and start the bridge.

        The phone becomes available as /dev/videoN. Any V4L2-compatible
        application (browser, VLC, MPV, Teams, OBS itself) can then open it
        without any GUI coupling. No OBS WebSocket sync is performed.

        If the phone is already enabled and bound to a /dev/videoN slot, the
        operation is skipped (no re-enable) and a log line is emitted.
        """
        phone = self._selected_phone()
        if phone is None:
            messagebox.showinfo(
                self.s.dialog_selection_title, self.s.dialog_selection_body
            )
            return

        def job() -> None:
            # Skip if the phone is already bound to a slot by a running bridge.
            running, device = self.bridge.status_for(phone.label)
            if running:
                self._append_log(
                    self.s.log_enable_skipped_already_enabled.format(
                        name=phone.display_name,
                        device=device,
                    )
                )
                return
            row, live = self.bridge.enable_phone(phone)
            for i, item in enumerate(self.phones):
                if item.serial == live.serial:
                    self.phones[i] = live
                    break
            self._append_log(
                self.s.log_enable_plain_done.format(
                    label=row.label,
                    device=row.device_path,
                    width=row.out_w,
                    height=row.out_h,
                    fps=row.fps,
                )
            )

        self._run_job(self.s.status_enabling.format(name=phone.display_name), job)

    def enable_all(self) -> None:
        """Enable every phone in the current table.

        If the same phone appears twice (e.g. via "USB Tethering" and
        "IP (LAN)"), the preferred transport is USB Tethering — the IP
        row is skipped.
        """
        if self._busy:
            return
        if not self.phones:
            self._append_log(self.s.log_enable_all_none)
            return

        # Deduplicate by serial: prefer USB/ACUS transport over IP.
        serial_phones: dict[str, PhoneDevice] = {}
        for phone in self.phones:
            existing = serial_phones.get(phone.serial)
            if existing is not None:
                # Both the existing and current phone share the same
                # serial. Prefer USB (acus) over the IP fallback.
                if phone.transport == "acus" and existing.transport != "acus":
                    serial_phones[phone.serial] = phone
                elif existing.transport == "acus" and phone.transport != "acus":
                    pass  # keep the existing ACUS entry
                else:
                    # Same transport or both non-ACUS — keep the first one.
                    pass
            else:
                serial_phones[phone.serial] = phone

        targets = list(serial_phones.values())
        total = len(targets)

        def job() -> None:
            ok = 0
            skipped = 0
            for phone in targets:
                try:
                    # Skip phones already bound to a running bridge/slot.
                    running, device = self.bridge.status_for(phone.label)
                    if running:
                        self._append_log(
                            self.s.log_enable_skipped_already_enabled.format(
                                name=phone.display_name,
                                device=device,
                            )
                        )
                        skipped += 1
                        continue
                    row, live = self.bridge.enable_phone(phone)
                    for i, item in enumerate(self.phones):
                        if item.serial == live.serial:
                            self.phones[i] = live
                            break
                    self._append_log(
                        self.s.log_enable_plain_done.format(
                            label=row.label,
                            device=row.device_path,
                            width=row.out_w,
                            height=row.out_h,
                            fps=row.fps,
                        )
                    )
                    ok += 1
                except Exception as exc:  # noqa: BLE001
                    self._append_log(
                        self.s.log_enable_all_failed.format(
                            name=phone.display_name,
                            error=exc,
                        )
                    )
                    skipped += 1
            if ok > 0:
                self._append_log(
                    self.s.log_enable_all_done.format(enabled=ok, total=total)
                )
            elif total > 0:
                self._append_log(
                    self.s.log_enable_all_failed_all.format(total=total)
                )

        self._run_job(self.s.status_enabling.format(name=f"{total} dispositivi"), job)

    def disable_selected(self) -> None:
        phone = self._selected_phone()
        if phone is None:
            messagebox.showinfo(
                self.s.dialog_selection_title, self.s.dialog_selection_body
            )
            return
        if not messagebox.askyesno(
            self.s.dialog_disable_title,
            self.s.dialog_disable_body.format(name=phone.display_name)
        ):
            return

        def job() -> None:
            self.bridge.disable_phone(phone.label)

        self._run_job(self.s.status_disabling.format(name=phone.display_name), job)

    def disable_all(self) -> None:
        """Stop every active bridge. Phones stay in the state list.

        The user can re-enable them later with 'Enable webcam' without
        having to refresh the discovery scan. The /dev/videoN nodes are
        not torn down (use 'Reload devices' for that) — the goal is just
        to release the loopback device for other consumers (browser,
        VLC, …) while keeping the configuration around.
        """
        if self._busy:
            return
        if not messagebox.askyesno(
            self.s.dialog_disable_all_title,
            self.s.dialog_disable_all_body,
        ):
            return

        def job() -> None:
            n = self.bridge.disable_all_phones()
            if n <= 0:
                self._append_log(self.s.log_disable_all_empty)
            else:
                self._append_log(self.s.log_disable_all_done.format(n=n))

        self._run_job(self.s.status_disabling.format(name=""), job)

    def reload_devices(self) -> None:
        if not messagebox.askyesno(
            self.s.dialog_reload_title,
            self.s.dialog_reload_body
        ):
            return

        def job() -> None:
            self.bridge.reload_devices()

        self._run_job(self.s.status_reloading_devices, job)

    def in_app_preview_selected(self) -> None:
        """Open the in-app live preview window for the selected phone.

        Before opening, checks whether the device is already associated
        with a /dev/videoN node. If it is, the preview opens immediately.
        If not, the phone is first enabled via ``enable_selected()``; the
        preview window is opened only on success, otherwise the error
        dialog already shown by ``enable_selected()`` is kept and no
        preview window is opened.

        Only one preview window at a time: a second click while a window
        is open just re-focuses the existing one (and refreshes the
        proxy so the latest negotiated WxH / fps shows up immediately).
        """
        if self._preview_window is not None and self._preview_window.win.winfo_exists():
            # Just bring it forward instead of opening a duplicate.
            self._preview_window.win.lift()
            self._preview_window.win.focus_force()
            return

        phone = self._selected_phone()
        if phone is None:
            messagebox.showinfo(
                self.s.dialog_selection_title, self.s.dialog_selection_body
            )
            return

        # Pillow is required to decode JPEG frames inside Tk. Without it
        # the window would only show a black canvas.
        from .in_app_preview import has_pillow
        if not has_pillow():
            messagebox.showwarning(
                self.s.in_app_preview_title.format(name=phone.display_name),
                self.s.in_app_preview_pillow_missing,
            )
            return

        # Check whether the device is already associated with a /dev/videoN.
        running, _ = self.bridge.status_for(phone.label)
        if running:
            self._open_preview_window(phone)
            return

        # Device not yet enabled — enable it first, then open the preview
        # only if enable succeeds.
        self._set_busy(True, self.s.status_enabling.format(name=phone.display_name))

        def work() -> None:
            err: Exception | None = None
            live_phone: PhoneDevice | None = None
            try:
                row, live = self.bridge.enable_phone(phone)
                for i, item in enumerate(self.phones):
                    if item.serial == live.serial:
                        self.phones[i] = live
                        break
                live_phone = live
                self._append_log(
                    self.s.log_enable_plain_done.format(
                        label=row.label,
                        device=row.device_path,
                        width=row.out_w,
                        height=row.out_h,
                        fps=row.fps,
                    )
                )
            except Exception as exc:  # noqa: BLE001
                err = exc

            def done() -> None:
                self._set_busy(False, self.s.status_ready)
                if err is not None:
                    self._append_log(self.s.log_error.format(error=err))
                    messagebox.showerror(self.s.dialog_error, str(err))
                    return
                self._fill_tree()
                self._append_log(self.s.log_done)
                assert live_phone is not None
                self._open_preview_window(live_phone)

            self.after(0, done)

        threading.Thread(target=work, daemon=True).start()

    def _open_preview_window(self, phone: PhoneDevice) -> None:
        """Open the in-app preview window for [phone] and block until closed."""
        self._append_log(
            self.s.log_in_app_preview_opened.format(name=phone.display_name)
        )
        self._preview_window = InAppPreviewWindow(
            self, phone, self.s, self._append_log
        )
        # Use wait_window so the main UI is modal-blocked until the user
        # closes the preview window.
        self._preview_window.show()
        self._preview_window = None

    def _add_ip_source(self) -> None:
        """Open dialog to add a manual IP source."""
        if self._busy:
            return
        dialog = tk.Toplevel(self)
        dialog.title(self.s.dialog_add_ip_title)
        dialog.resizable(False, False)
        dialog.grab_set()  # Modal dialog

        # Parse current entry if available
        host_var = tk.StringVar(value="192.168.1.")
        port_var = tk.StringVar(value="2743")

        # Host entry
        ttk.Label(dialog, text=self.s.dialog_add_ip_host_label).grid(
            row=0, column=0, padx=10, pady=(10, 2), sticky=tk.W
        )
        host_entry = ttk.Entry(dialog, textvariable=host_var, width=20)
        host_entry.grid(row=0, column=1, padx=10, pady=(10, 2))
        host_entry.focus()

        # Port entry
        ttk.Label(dialog, text=self.s.dialog_add_ip_port_label).grid(
            row=1, column=0, padx=10, pady=(2, 10), sticky=tk.W
        )
        port_entry = ttk.Entry(dialog, textvariable=port_var, width=6)
        port_entry.grid(row=1, column=1, padx=10, pady=(2, 10))

        # Button frame
        btn_frame = ttk.Frame(dialog)
        btn_frame.grid(row=2, column=0, columnspan=2, pady=(0, 10))

        def on_add() -> None:
            host = host_var.get().strip()
            port_str = port_var.get().strip()

            try:
                port = int(port_str)
                if not (1 <= port <= 65535):
                    raise ValueError()
            except ValueError:
                messagebox.showerror(
                    self.s.dialog_error,
                    self.s.dialog_add_ip_error_invalid,
                )
                return

            if not host:
                messagebox.showerror(
                    self.s.dialog_error,
                    self.s.dialog_add_ip_error_invalid,
                )
                return

            # Check if already exists
            if (host, port) in self.manual_endpoints:
                messagebox.showinfo(
                    self.s.dialog_add_ip_title,
                    self.s.dialog_add_ip_already_exists,
                )
                return

            # Verify the endpoint immediately first
            info = fetch_stream_info(host, port, timeout=0.3)
            if info is not None:
                # Endpoint is reachable - add to tracking
                self.manual_endpoints.add((host, port))
                self._append_log(self.s.log_manual_endpoint_added.format(host=host, port=port))

                serial = f"manual-{host}-{port}"
                # Check if already in the list (avoid duplicates)
                for p in self.phones:
                    if p.serial == serial and p.host == host and p.port == port:
                        break
                else:
                    model = info.device_name
                    phone = PhoneDevice(
                        serial=serial,
                        model=model,
                        host=host,
                        port=port,
                        info=info,
                        transport="http",
                        endpoint_label=f"Wi-Fi ({host})",
                    )
                    self.phones.append(phone)
                    self._append_log(
                        self.s.log_wifi_found.format(
                            name=phone.display_name,
                            width=info.output_width,
                            height=info.output_height,
                            fps=info.display_fps,
                            host=host,
                            port=port,
                        )
                    )
                    self._fill_tree()
            else:
                # Endpoint not reachable - do NOT add it
                self._append_log(self.s.log_endpoint_unreachable.format(host=host, port=port))
                messagebox.showwarning(
                    self.s.dialog_endpoint_unreachable_title,
                    self.s.dialog_endpoint_unreachable_body.format(endpoint=f"{host}:{port}"),
                )

            dialog.destroy()

        ttk.Button(btn_frame, text=self.s.btn_add, command=on_add).pack(side=tk.LEFT, padx=5)
        ttk.Button(btn_frame, text=self.s.btn_cancel, command=dialog.destroy).pack(side=tk.LEFT, padx=5)

        dialog.bind("<Return>", lambda e: on_add())
        dialog.bind("<Escape>", lambda e: dialog.destroy())


def main() -> None:
    app = AndroidCameraGui()
    try:
        app.mainloop()
    finally:
        # Signal the watchdog thread to exit; join it so we don't leak
        # a daemon thread when the user closes the window.
        try:
            app._watchdog_stop.set()
        except Exception:
            pass
