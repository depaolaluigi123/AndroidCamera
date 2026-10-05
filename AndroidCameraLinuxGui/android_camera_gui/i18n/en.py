from .strings_base import Strings

STRINGS = Strings(
 language_code="en",
 language_name="English",
 window_title="AndroidCamera - OBS link",
 header_title="Phones with active service",
 hint=(
 "Start the webcam service in the Android app, then refresh the list. "
 '"Enable webcam" creates a V4L2 device (/dev/videoN) named after the phone. '
 '"Disable webcam" only stops the bridge (no password). '
 '"Disable all" stops every active bridge. '
 '"Reload devices" rebuilds the v4l2loopback nodes (root password; '
 "close/detach any V4L2 consumers if the module is busy). "
 '"In-app preview" opens a live preview window (no browser required).'
 ),
 status_ready="Ready",
 status_scanning="Scanning...",
 status_phones_one="{n} phone with active service",
 status_phones_many="{n} phones with active service",
 status_enabling='Enabling "{name}"...',
 status_disabling='Disabling "{name}"...',
 status_reloading_devices="Reloading V4L2 devices...",
 status_opening_preview='Opening live preview for "{name}"...',
 col_name="Name (app)",
 col_model="Model",
 col_endpoint="Endpoint",
 col_resolution="Resolution",
 col_fps="FPS",
 col_obs="OBS",
 col_format="Format",
 col_quality="JPEG q",
 fps_unlimited="Unlimited",
 obs_active="Active ({device})",
 obs_node="Node {device}",
 obs_disabled="Not enabled",
 btn_refresh="Refresh",
 # "Enable for OBS" button removed: "Enable webcam" is now the only
 # action and only creates a /dev/videoN node (no OBS sync).
 btn_enable="Enable webcam",
 ports_hint="Default port: 2743. In the android app use this port for automatic computer detection, or enter a custom port (manual detection).",
 btn_disable="Disable Webcam",
 btn_reload_devices="Reload devices (v4l2)",
 btn_add_ip_source="Add IP source",
 # "Live preview" button removed (Vue.js browser preview gone).
 btn_web_preview="In-app preview",
 btn_exit="Exit",
 btn_lang_en="EN",
 btn_lang_it="IT",

 # Enable webcam (no OBS, just /dev/videoN) — VLC/MPV button removed.
 # The "Open in VLC/MPV" feature was dropped by request.
 btn_enable_webcam="Enable webcam",
 btn_open_viewer="Open in VLC/MPV",
 dialog_open_viewer_title="Open external viewer",
 dialog_open_viewer_body="Choose where to open the live stream:",
 btn_viewer_vlc="VLC",
 btn_viewer_mpv="MPV",
 log_external_viewer_unavailable=(
 "External viewer not available: install vlc or mpv and try again."
 ),
 log_external_viewer_opened=(
 "Opened {viewer} for «{label}» ({device}, {width}x{height}@{fps})"
 ),
 log_enable_plain_done=(
 "Webcam enabled for «{label}» ({device}, {width}x{height}@{fps}). "
 "Use any V4L2-compatible app (VLC, MPV, …) to open it."
 ),

 # In-app preview window (modal Toplevel, Pillow-backed)
 btn_in_app_preview="In-app preview",
 in_app_preview_title="Live preview — {name}",
 in_app_preview_resolution_label="Resolution: {value}",
 in_app_preview_resolution_pending="Resolution: …",
 in_app_preview_fps_label="FPS: {value}",
 in_app_preview_fps_pending="FPS: …",
 in_app_preview_pillow_missing=(
 "Pillow is not installed; the in-app preview cannot decode frames. "
 "Install with: pip install --user Pillow"
 ),
 log_in_app_preview_opened="In-app preview opened for «{name}»",

 # Disable-all button: stop every active bridge without forgetting state
 btn_disable_all="Disable all",
 dialog_disable_all_title="Disable all webcams",
 dialog_disable_all_body=(
 "Stop every active bridge and free the /dev/video* nodes?\n\n"
 "The phones stay in the list. Re-enable them with "
 '"Enable webcam" when you want them back.'
 ),
 log_disable_all_done="All webcams disabled ({n} stopped).",
 log_disable_all_empty="No active webcams to disable.",
 log_disable_phone_released=(
     "Slot {device} released (no active consumer). "
     "Available for reuse at next Enable."
 ),
 log_disable_phone_held=(
     "Connection to the phone is closed, but the slot {device} "
     "cannot be released because it is still in use by a consumer."
 ),

 # Enable-all button
 btn_enable_all="Enable all",
 log_enable_all_none="No devices to enable.",
 log_enable_all_done="{enabled}/{total} webcams enabled.",
 log_enable_all_failed='Failed to enable "{name}": {error}',
 log_enable_all_failed_all="All {total} devices failed to enable.",
 log_title="Log",
 log_refresh="-- Refreshing phones --",
 log_done="Done.",
 log_error="Error: {error}",
 log_obs_hint=(
 'V4L2 webcam ready: "{label}" ({device}, {width}x{height}@{fps}). '
 "If the source already exists in OBS, its resolution and fps are updated there."
 ),
 log_lang_changed="Language set to English.",
 dialog_error="Error",
 dialog_selection_title="Selection",
 dialog_selection_body="Select a phone from the list.",
 dialog_disable_title="Disable",
 dialog_disable_body=(
 'Disable "{name}"?\n\n'
 "Only the ffmpeg bridge will be stopped. "
 '/dev/video* nodes are not rebuilt (use "Reload devices" if needed).'
 ),
 dialog_reload_title="Reload devices",
 dialog_reload_body=(
 "Rebuild the v4l2loopback nodes from the phones still enabled?\n\n"
 "Root password required. If a consumer still holds the devices "
 "open, close it and try again."
 ),
 obs_pkg_missing=(
 'Python package "obsws-python" is missing. '
 "Install with: pip install --user obsws-python"
 ),
 obs_ws_not_configured=(
 "OBS WebSocket is not configured (expected in {path}). "
 "Enable Tools -> WebSocket Server."
 ),
 obs_ws_connect_failed=(
 "OBS WebSocket connection failed ({host}:{port}): {why}. "
 "Make sure OBS is open and Tools -> WebSocket Server is enabled."
 ),
 obs_ws_refused=(
 "connection refused (OBS closed, WebSocket disabled, or wrong port)"
 ),
 obs_ws_timeout="timed out waiting for a response",
 obs_pause_skipped="OBS: source pause skipped -- {error}",
 obs_paused="OBS: paused {n} source(s) on {device} (format change only)",
 obs_detached=(
 "OBS: temporarily detached {n} source(s) from {device} "
 "(release device for new format)"
 ),
 obs_resumed="OBS: re-enabled {n} source(s)",
 obs_update_skipped="OBS: resolution/fps update skipped -- {error}",
 obs_source_updated=(
 'OBS: source "{name}" updated to {width}x{height}@{fps}'
 ),
 obs_source_update_failed=(
 'OBS: source "{name}" not updated '
 "(got res={got_res} fps={got_fps}, expected {width}x{height}@{fps})"
 ),
 obs_source_update_error='OBS: error updating "{name}": {error}',
 obs_no_source_for_device=(
 "OBS: no V4L2 source already linked to {device} "
 "(add it once manually in OBS)"
 ),
 obs_fallback_recreate=(
 "OBS: in-place update not enough -- "
 "recreating source(s) in the same scenes"
 ),
 obs_source_removed_recreate='OBS: removed source "{name}" to recreate it',
 obs_source_recreated=(
 'OBS: source "{name}" recreated at {width}x{height}@{fps} '
 "(same scenes as before)"
 ),
 dialog_endpoint_unreachable_title="Endpoint unreachable",
 dialog_endpoint_unreachable_body=(
 "The endpoint {endpoint} is not reachable.\n\n"
 'Please press "Refresh" to reload connected devices.'
 ),

 # Endpoint labels
 endpoint_usb_device="USB Device (acus :{port})",
 log_wifi_found=" found Wi-Fi «{name}» {width}x{height}@{fps} → {host}:{port}",
 log_endpoint_unreachable=" endpoint {host}:{port} not reachable",
 log_preview_endpoint_unreachable="Endpoint not reachable: {endpoint}",

 # Add IP source dialog buttons
 btn_add="Add",
 btn_cancel="Cancel",
 btn_close="Close",

 # Add IP source dialog
 dialog_add_ip_title="Add IP Source",
 dialog_add_ip_body=(
 "Enter the IP address and port of an AndroidCamera\n"
 "webcam service running on your phone.\n\n"
 "Example: 192.168.1.100:2743"
 ),
 dialog_add_ip_host_label="IP address",
 dialog_add_ip_port_label="Port",
 dialog_add_ip_error_invalid="Please enter a valid IP address and port (e.g., 192.168.1.100:2743).",
 dialog_add_ip_success="Added: {host}:{port}",
 dialog_add_ip_already_exists="This endpoint is already in the list.",
 log_manual_endpoint_added="Added manual endpoint: {host}:{port}",
 log_manual_endpoint_removed="Removed manual endpoint: {host}:{port} (not reachable)",

 # Log strings for discovery.py
 log_probe_http_tether=" trying USB Tethering + HTTP...",
 log_probe_acus=" trying USB Device / ACUS...",
 log_probe_http_multi=" trying USB Tethering + HTTP (multi-endpoint)...",
 log_endpoint_unreachable_acus=" endpoint {endpoint} not reachable (ACUS)",
 log_endpoint_unreachable_http=" endpoint {endpoint} not reachable (HTTP)",
 log_endpoint_reachable_acus=" endpoint {endpoint} reachable (ACUS)",
 log_endpoint_reachable_http=" endpoint {endpoint} reachable (HTTP)",
 log_adb_error="ADB error: {error}",
 log_scanning_device="Scanning {model} ({serial})...",
 log_service_inactive=" service not active on {serial} (start the stream in the app)",
 log_found_usb_device=" found USB Device «{name}» {width}x{height}@{fps} → acus://{serial}@127.0.0.1:{port}",
 log_ports_scanned="Scanning ports: {ports}",
 log_ports_manual_entry="Use the ports above, or enter the device address and port manually.",

 # Additional log strings for enhanced debugging
 log_scan_start="--- Starting device scan ---",
 log_scan_end="--- Scan complete: {count} device(s) found ---",
 log_adb_devices_found="ADB devices found: {count}",
 log_authorized_devices="Authorized devices: {count}",
 log_no_authorized="No authorized devices (state='device')",
 log_scanning_endpoint=" Scanning endpoint for {model} ({serial})...",
 log_http_endpoints_found=" Found {count} HTTP endpoint(s)",
 log_acus_detected=" ACUS detected on port {port}: {name} {width}x{height}@{fps}",
 log_acus_invalid_data=" ACUS invalid data on port {port}",
 log_no_endpoint_found=" No endpoint found for {serial}",
 log_subnet_scan_start="Scanning subnet {subnet} on port {port}…",
 log_subnet_scan_end="Subnet scan complete: {count} device(s) found",

 # Format / quality labels
 format_label_yuv="YUV",
 format_label_jpeg="JPEG",
 quality_label="quality",
 log_format_negotiated=(
 " stream format: {format} · quality: {quality}%"
 ),
 log_protocol_unsupported=(
 "Unsupported ACUS protocol version {version}; "
 "update AndroidCamera for JPEG support"
 ),
 log_jpeg_hint="",

    # Slot retry log strings
    log_slot_failed_retry="Slot /dev/video{video_nr} failed ({error}); trying next slot…",
    log_slot_retry_exhausted="All /dev/videoN slots were tried without success. Cannot establish the connection.",
    log_slot_retry_success="Succeeded on /dev/video{video_nr} after {attempts} attempt(s).",

    # Skip log (already enabled)
    log_enable_skipped_already_enabled=(
        "Skipped «{name}»: already enabled on {device}."
    ),
)
