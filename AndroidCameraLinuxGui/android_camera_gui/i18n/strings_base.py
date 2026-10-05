"""String table contract for the Linux GUI (no UI if/else on language)."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class Strings:
    """All user-visible strings. Concrete languages live in en.py / it.py."""

    language_code: str
    language_name: str

    window_title: str
    header_title: str
    hint: str
    status_ready: str
    status_scanning: str
    status_phones_one: str
    status_phones_many: str
    status_enabling: str
    status_disabling: str
    status_reloading_devices: str
    status_opening_preview: str

    col_name: str
    col_model: str
    col_endpoint: str
    col_resolution: str
    col_fps: str
    col_obs: str
    col_format: str
    col_quality: str
    fps_unlimited: str

    obs_active: str
    obs_node: str
    obs_disabled: str

    btn_refresh: str
    btn_enable: str
    btn_disable: str  # "Disable Webcam" — stop one phone's bridge
    btn_reload_devices: str
    btn_add_ip_source: str
    btn_web_preview: str
    btn_exit: str
    btn_lang_en: str
    btn_lang_it: str
    ports_hint: str

    # Enable button (no OBS, for VLC/MPV/web browsers) + viewer (VLC/MPV) + cleanup log strings
    btn_enable_webcam: str
    btn_open_viewer: str
    dialog_open_viewer_title: str
    dialog_open_viewer_body: str
    btn_viewer_vlc: str
    btn_viewer_mpv: str
    log_external_viewer_unavailable: str
    log_external_viewer_opened: str
    log_enable_plain_done: str

    # In-app preview window (modal Toplevel, Pillow-backed)
    btn_in_app_preview: str
    in_app_preview_title: str
    in_app_preview_resolution_label: str
    in_app_preview_resolution_pending: str
    in_app_preview_fps_label: str
    in_app_preview_fps_pending: str
    in_app_preview_pillow_missing: str
    log_in_app_preview_opened: str

    # Disable-all button: stop every active bridge without forgetting the state
    btn_disable_all: str
    dialog_disable_all_title: str
    dialog_disable_all_body: str
    log_disable_all_done: str
    log_disable_all_empty: str

    # Disable-one: slot released vs held by consumer
    log_disable_phone_released: str
    log_disable_phone_held: str

    # Enable-all button + log strings
    btn_enable_all: str
    log_enable_all_none: str
    log_enable_all_done: str
    log_enable_all_failed: str
    log_enable_all_failed_all: str

    log_title: str
    log_refresh: str
    log_done: str
    log_error: str
    log_obs_hint: str
    log_lang_changed: str

    dialog_error: str
    dialog_selection_title: str
    dialog_selection_body: str
    dialog_disable_title: str
    dialog_disable_body: str
    dialog_reload_title: str
    dialog_reload_body: str

    # OBS WebSocket: update existing V4L2 source format only
    obs_pkg_missing: str
    obs_ws_not_configured: str
    obs_ws_connect_failed: str
    obs_ws_refused: str
    obs_ws_timeout: str
    obs_pause_skipped: str
    obs_paused: str
    obs_detached: str
    obs_resumed: str
    obs_update_skipped: str
    obs_source_updated: str
    obs_source_update_failed: str
    obs_source_update_error: str
    obs_no_source_for_device: str
    obs_fallback_recreate: str
    obs_source_removed_recreate: str
    obs_source_recreated: str
    dialog_endpoint_unreachable_title: str
    dialog_endpoint_unreachable_body: str

    # OBS column label kept as-is (the column still shows /dev/videoN status)
    # but the OBS WebSocket sync code was removed. The label is generic.

    # Endpoint labels
    endpoint_usb_device: str
    log_wifi_found: str
    log_endpoint_unreachable: str
    log_preview_endpoint_unreachable: str

    # Add IP source dialog buttons
    btn_add: str
    btn_cancel: str
    btn_close: str

    # Add IP source dialog
    dialog_add_ip_title: str
    dialog_add_ip_body: str
    dialog_add_ip_host_label: str
    dialog_add_ip_port_label: str
    dialog_add_ip_error_invalid: str
    dialog_add_ip_success: str
    dialog_add_ip_already_exists: str
    log_manual_endpoint_added: str
    log_manual_endpoint_removed: str

    # Log strings for discovery.py
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
    log_ports_scanned: str
    log_ports_manual_entry: str

    # Additional log strings for enhanced debugging
    log_scan_start: str
    log_scan_end: str
    log_adb_devices_found: str
    log_authorized_devices: str
    log_no_authorized: str
    log_scanning_endpoint: str
    log_http_endpoints_found: str
    log_acus_detected: str
    log_acus_invalid_data: str
    log_no_endpoint_found: str
    log_subnet_scan_start: str
    log_subnet_scan_end: str

    # Format / quality labels
    format_label_yuv: str
    format_label_jpeg: str
    quality_label: str
    log_format_negotiated: str
    log_protocol_unsupported: str
    log_jpeg_hint: str

    # Slot retry log strings
    log_slot_failed_retry: str
    log_slot_retry_exhausted: str
    log_slot_retry_success: str

    # Skip log strings (already enabled)
    log_enable_skipped_already_enabled: str
