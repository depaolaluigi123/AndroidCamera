from .strings_base import Strings

STRINGS = Strings(
 language_code="it",
 language_name="Italiano",
 window_title="AndroidCamera – Collegamento OBS",
 header_title="Telefoni con servizio attivo",
 hint=(
 "Avvia il servizio webcam nell'app Android, poi aggiorna l'elenco. "
 "«Abilita webcam» crea un device V4L2 (/dev/videoN) con il nome del telefono. "
 "«Disabilita webcam» ferma solo il bridge (senza password). "
 "«Disabilita tutto» ferma tutti i bridge attivi. "
 "«Ricarica dispositivi» ricostruisce i nodi v4l2loopback (password root; "
 "chiudi/stacca i consumatori V4L2 se il modulo è in uso). "
 "«Anteprima in-app» apre una finestra di anteprima live (senza browser)."
 ),
 status_ready="Pronto",
 status_scanning="Scansione in corso…",
 status_phones_one="{n} telefono con servizio attivo",
 status_phones_many="{n} telefoni con servizio attivo",
 status_enabling="Abilitazione «{name}»…",
 status_disabling="Disabilitazione «{name}»…",
 status_reloading_devices="Ricarica dispositivi V4L2…",
 status_opening_preview="Apertura anteprima live di «{name}»…",
 col_name="Nome (app)",
 col_model="Modello",
 col_endpoint="Endpoint",
 col_resolution="Risoluzione",
 col_fps="FPS",
 col_obs="OBS",
 col_format="Formato",
 col_quality="JPEG q",
 fps_unlimited="Illimitati",
 obs_active="Attivo ({device})",
 obs_node="Nodo {device}",
 obs_disabled="Non abilitato",
 btn_refresh="Aggiorna",
 # "Abilita per OBS" rimosso: "Abilita webcam" è ora l'unica azione e
 # crea solo un nodo /dev/videoN (nessuna sincronizzazione con OBS).
 btn_enable="Abilita webcam",
 ports_hint="Porta predefinita: 2743. Nell'app android usa questa porta per il riconoscimento automatico dal computer, oppure inserisci una porta personalizzata (rilevamento manuale).",
 btn_disable="Disabilita Webcam",
 btn_reload_devices="Ricarica dispositivi (v4l2)",
 btn_add_ip_source="Aggiungi sorgente IP",
 # "Anteprima live" via browser (Vue.js) rimosso; rimane solo l'anteprima in-app.
 btn_web_preview="Anteprima in-app",
 btn_exit="Esci",
 btn_lang_en="EN",
 btn_lang_it="IT",

 # Abilita webcam (no OBS, solo /dev/videoN) — il bottone "Apri in VLC/MPV"
 # è stato rimosso come richiesto.
 btn_enable_webcam="Abilita webcam",
 btn_open_viewer="Apri in VLC/MPV",
 dialog_open_viewer_title="Apri viewer esterno",
 dialog_open_viewer_body="Scegli dove aprire lo stream live:",
 btn_viewer_vlc="VLC",
 btn_viewer_mpv="MPV",
 log_external_viewer_unavailable=(
 "Viewer esterno non disponibile: installa vlc o mpv e riprova."
 ),
 log_external_viewer_opened=(
 "Aperto {viewer} per «{label}» ({device}, {width}x{height}@{fps})"
 ),
 log_enable_plain_done=(
 "Webcam abilitata per «{label}» ({device}, {width}x{height}@{fps}). "
 "Apri il device da qualsiasi app compatibile V4L2 (VLC, MPV, …)."
 ),

 # Finestra di anteprima in-app (Toplevel modale, basata su Pillow)
 btn_in_app_preview="Anteprima in-app",
 in_app_preview_title="Anteprima live — {name}",
 in_app_preview_resolution_label="Risoluzione: {value}",
 in_app_preview_resolution_pending="Risoluzione: …",
 in_app_preview_fps_label="FPS: {value}",
 in_app_preview_fps_pending="FPS: …",
 in_app_preview_pillow_missing=(
 "Pillow non installato: l'anteprima in-app non può decodificare i frame. "
 "Installa con: pip install --user Pillow"
 ),
 log_in_app_preview_opened="Anteprima in-app aperta per «{name}»",

 # Disabilita tutto: ferma ogni bridge attivo senza dimenticare lo state
 btn_disable_all="Disabilita tutto",
 dialog_disable_all_title="Disabilita tutte le webcam",
 dialog_disable_all_body=(
 "Ferma tutti i bridge attivi e libera i nodi /dev/video*?\n\n"
 "I telefoni restano nell'elenco. Riabilitali con "
 '"Abilita webcam" quando vuoi.'
 ),
 log_disable_all_done="Tutte le webcam disabilitate ({n} fermate).",
 log_disable_all_empty="Nessuna webcam attiva da disabilitare.",
 log_disable_phone_released=(
 "Slot {device} rilasciato (nessun consumer attivo). "
 "Riutilizzabile al prossimo Enable."
 ),
 log_disable_phone_held=(
 "Collegamento con il telefono chiuso, lo slot {device} "
 "non può essere rilasciato perché è ancora utilizzato da un consumer."
 ),

 # Enable-all button
 btn_enable_all="Abilita tutte",
 log_enable_all_none="Nessun dispositivo da abilitare.",
 log_enable_all_done="{enabled}/{total} webcam abilitate.",
 log_enable_all_failed='Abilitazione di "{name}" fallita: {error}',
 log_enable_all_failed_all="Tutti i {total} dispositivi hanno fallito l'abilitazione.",
 log_title="Log",
 log_refresh="— Aggiornamento telefoni —",
 log_done="Operazione completata.",
 log_error="Errore: {error}",
 log_obs_hint=(
 "Webcam V4L2 pronta: «{label}» ({device}, {width}x{height}@{fps}). "
 "Se la source esiste già in OBS, risoluzione e fps vengono aggiornati lì."
 ),
 log_lang_changed="Lingua impostata su italiano.",
 dialog_error="Errore",
 dialog_selection_title="Selezione",
 dialog_selection_body="Seleziona un telefono dall'elenco.",
 dialog_disable_title="Disabilita",
 dialog_disable_body=(
 "Disabilitare «{name}»?\n\n"
 "Verrà fermato solo il bridge ffmpeg. "
 "I nodi /dev/video* non vengono ricreati (usa «Ricarica dispositivi» se serve)."
 ),
 dialog_reload_title="Ricarica dispositivi",
 dialog_reload_body=(
 "Ricostruire i nodi v4l2loopback dalle webcam ancora abilitate?\n\n"
 "Serve la password root. Se qualche consumatore tiene aperti i "
 "device, chiudilo e riprova."
 ),
 obs_pkg_missing=(
 "Pacchetto Python «obsws-python» assente. "
 "Installa con: pip install --user obsws-python"
 ),
 obs_ws_not_configured=(
 "OBS WebSocket non configurato (atteso in {path}). "
 "Abilita Strumenti → Server WebSocket."
 ),
 obs_ws_connect_failed=(
 "connessione WebSocket a OBS fallita ({host}:{port}): {why}. "
 "Verifica che OBS sia aperto e che Strumenti → Server WebSocket sia attivo."
 ),
 obs_ws_refused=(
 "connessione rifiutata (OBS chiuso, WebSocket disabilitato o porta diversa)"
 ),
 obs_ws_timeout="timeout in attesa della risposta",
 obs_pause_skipped="OBS: pausa source saltata — {error}",
 obs_paused="OBS: messe in pausa {n} source su {device} (solo per cambio formato)",
 obs_detached=(
 "OBS: staccate temporaneamente {n} source da {device} "
 "(rilascio device per nuovo formato)"
 ),
 obs_resumed="OBS: riattivate {n} source",
 obs_update_skipped="OBS: aggiornamento risoluzione/fps saltato — {error}",
 obs_source_updated=(
 "OBS: source «{name}» aggiornata a {width}x{height}@{fps}"
 ),
 obs_source_update_failed=(
 "OBS: source «{name}» non aggiornata "
 "(ottenuto res={got_res} fps={got_fps}, attesi {width}x{height}@{fps})"
 ),
 obs_source_update_error="OBS: errore aggiornando «{name}»: {error}",
 obs_no_source_for_device=(
 "OBS: nessuna source V4L2 già collegata a {device} "
 "(aggiungila manualmente una volta in OBS)"
 ),
 obs_fallback_recreate=(
 "OBS: aggiornamento in-place insufficiente — "
 "ricreo le source nelle stesse scene"
 ),
 obs_source_removed_recreate="OBS: rimossa source «{name}» per ricrearla",
 obs_source_recreated=(
 "OBS: source «{name}» ricreata a {width}x{height}@{fps} "
 "(stesse scene di prima)"
 ),
 dialog_endpoint_unreachable_title="Endpoint non raggiungibile",
 dialog_endpoint_unreachable_body=(
 "L'endpoint {endpoint} non è raggiungibile.\n\n"
 "Premi «Aggiorna» per ricaricare i dispositivi collegati."
 ),

 # Endpoint labels
 endpoint_usb_device="Dispositivo USB (acus :{port})",
 log_wifi_found=" trovato Wi-Fi «{name}» {width}x{height}@{fps} → {host}:{port}",
 log_endpoint_unreachable=" endpoint {host}:{port} non raggiungibile",
 log_preview_endpoint_unreachable="Endpoint non raggiungibile: {endpoint}",

 # Add IP source dialog buttons
 btn_add="Aggiungi",
 btn_cancel="Annulla",
 btn_close="Chiudi",

 # Aggiungi sorgente IP
 dialog_add_ip_title="Aggiungi sorgente IP",
 dialog_add_ip_body=(
 "Inserisci l'indirizzo IP e la porta di un servizio\n"
 "AndroidCamera webcam sul tuo telefono.\n\n"
 "Esempio: 192.168.1.100:2743"
 ),
 dialog_add_ip_host_label="Indirizzo IP",
 dialog_add_ip_port_label="Porta",
 dialog_add_ip_error_invalid="Inserisci un indirizzo IP e una porta validi (es. 192.168.1.100:2743).",
 dialog_add_ip_success="Aggiunto: {host}:{port}",
 dialog_add_ip_already_exists="Questo endpoint è già nell'elenco.",
 log_manual_endpoint_added="Aggiunto endpoint manuale: {host}:{port}",
 log_manual_endpoint_removed="Rimosso endpoint manuale: {host}:{port} (non raggiungibile)",

 # Log strings for discovery.py
 log_probe_http_tether=" provo USB Tethering + HTTP…",
 log_probe_acus=" provo USB Device / ACUS…",
 log_probe_http_multi=" provo USB Tethering + HTTP (multi-endpoint)…",
 log_endpoint_unreachable_acus=" endpoint {endpoint} non raggiungibile (ACUS)",
 log_endpoint_unreachable_http=" endpoint {endpoint} non raggiungibile (HTTP)",
 log_endpoint_reachable_acus=" endpoint {endpoint} raggiungibile (ACUS)",
 log_endpoint_reachable_http=" endpoint {endpoint} raggiungibile (HTTP)",
 log_adb_error="Errore ADB: {error}",
 log_scanning_device="Scansione {model} ({serial})…",
 log_service_inactive=" servizio non attivo su {serial} (avvia lo stream nell'app)",
 log_found_usb_device=" trovato USB Device «{name}» {width}x{height}@{fps} → acus://{serial}@127.0.0.1:{port}",
 log_ports_scanned="Porte scansionate: {ports}",
 log_ports_manual_entry="Usa le porte sopra, oppure inserisci manualmente l'indirizzo e la porta del dispositivo.",

 # Additional log strings for enhanced debugging
 log_scan_start="--- Inizio scansione dispositivi ADB ---",
 log_scan_end="--- Scansione completata: {count} dispositivi trovati ---",
 log_adb_devices_found="Dispositivi ADB trovati: {count}",
 log_authorized_devices="Dispositivi autorizzati: {count}",
 log_no_authorized="Nessun dispositivo autorizzato (state='device')",
 log_scanning_endpoint=" Scansione endpoint per {model} ({serial})...",
 log_http_endpoints_found=" Trovati {count} endpoint HTTP",
 log_acus_detected=" ACUS rilevato su porta {port}: {name} {width}x{height}@{fps}",
 log_acus_invalid_data=" ACUS dati non validi su porta {port}",
 log_no_endpoint_found=" Nessun endpoint trovato per {serial}",
 log_subnet_scan_start="Scansione subnet {subnet} sulla porta {port}…",
 log_subnet_scan_end="Scansione subnet completata: {count} dispositivi trovati",

 # Etichette formato / qualità
 format_label_yuv="YUV",
 format_label_jpeg="JPEG",
 quality_label="qualità",
 log_format_negotiated=(
 " formato stream: {format} · qualità: {quality}%"
 ),
 log_protocol_unsupported=(
 "Versione protocollo ACUS {version} non supportata; "
 "aggiorna AndroidCamera per il supporto JPEG"
 ),
 log_jpeg_hint="",

    # Slot retry log strings
    log_slot_failed_retry="Slot /dev/video{video_nr} non riuscito ({error}); provo con lo slot successivo…",
    log_slot_retry_exhausted="Tutti gli slot /dev/videoN sono stati provati senza successo. Impossibile stabilire la connessione.",
    log_slot_retry_success="Riuscito su /dev/video{video_nr} dopo {attempts} tentativo/i.",

    # Skip log (già abilitato)
    log_enable_skipped_already_enabled=(
        "Saltato «{name}»: già abilitato su {device}."
    ),
)
