# AndroidCamera Linux GUI

Interfaccia grafica Linux per collegare i telefoni **AndroidCamera Webcam** a OBS.

Si trova accanto al progetto Android:

```
Progetti GitHub/
├── AndroidCamera/           # app Android
└── AndroidCameraLinuxGui/   # questa GUI
```

## Cosa fa

1. Elenca i telefoni collegati via USB (ADB) con il **servizio webcam attivo**
2. Mostra il **nome** assegnato nell'app, risoluzione e FPS
3. Con **Abilita per OBS** crea una webcam V4L2 con quel nome:
   - **USB Tethering / IP**: `curl` + `ffmpeg` dallo stream HTTP YUV
   - **USB Device**: driver `AndroidCameraUsbDriver` (`acus_driver`) via `adb forward` (niente HTTP)
4. Con **Disabilita** ferma il bridge di quel telefono (senza password / senza `modprobe`)
5. Con **Ricarica dispositivi** ricostruisce i nodi `/dev/video*` dallo state (password root)
6. In OBS: **Sources → Video Capture Device (V4L2)** → seleziona il nome del telefono

## Requisiti

- Python 3.10+ con `tkinter` (`python3-tk` su Debian/Ubuntu)
- `adb` (Android platform-tools)
- `v4l2loopback-dkms`, `ffmpeg`, `curl`
- Permessi root quando serve `modprobe` (**Abilita per OBS** se i nodi vanno creati, oppure **Ricarica dispositivi**) via `sudo` o `pkexec`

## Avvio

Dalla radice del monorepo (carica anche `.env`):

```bash
./run-linux-gui.sh
```

oppure dalla sottocartella:

```bash
cd AndroidCameraLinuxGui
./run.sh
```

oppure:

```bash
python3 -m android_camera_gui
```

## Uso

1. Sul telefono: apri AndroidCamera Webcam, imposta un nome univoco, scegli la modalità
   (**USB Tethering**, **USB Device** o **IP**) e avvia il servizio
2. Collega il telefono in debug USB e autorizza il PC  
   (per **USB Device** non serve tethering né Wi‑Fi)
3. Nella GUI premi **Aggiorna**, seleziona il telefono, **Abilita per OBS**
4. In OBS scegli la webcam con il nome del telefono (source **Video Capture Device (V4L2)**)

**Anteprima live:** con un telefono selezionato, il pulsante **Anteprima in-app** apre una finestra di anteprima direttamente dentro la GUI (Pillow). Non serve più un server Vue / browser esterno.

Puoi abilitare più telefoni contemporaneamente (ognuno con un nome diverso).

Dopo uno stop/start del servizio sul telefono, o dopo un cambio di risoluzione/FPS/orientamento nell'app, premi di nuovo **Abilita per OBS**.

### Pulsanti principali

| Pulsante | Cosa fa | Password root? |
| --- | --- | --- |
| **Abilita per OBS** | Crea/riusa il nodo V4L2 e avvia `curl \| ffmpeg` | Solo se il nodo va creato/ricreato |
| **Disabilita** | Ferma il bridge e toglie il telefono dallo state (`devices.tsv`). Il `/dev/videoN` può restare idle | No |
| **Ricarica dispositivi** | `modprobe -r` + `modprobe` (stesso comando root → una sola password) con le webcam ancora nello state, poi riavvia i bridge. Se lo state è vuoto, prova solo a scaricare il modulo | Sì (una volta) |
| **Anteprima live** | Avvia (una volta) il server Vue e apre il browser | No |

**Quando usare Ricarica dispositivi:** dopo aver disabilitato uno o più telefoni e vuoi ripulire i nodi orfani / allineare `v4l2loopback` allo state; oppure se i `/dev/video*` sono in uno stato inconsistente. Se OBS tiene aperti i device, stacca o chiudi le source V4L2 prima di premere il pulsante.

### Cosa fa il pulsante **Abilita per OBS**

Sequenza eseguita dalla GUI (WebSocket OBS se disponibile, tipicamente porta `4455`, password da `~/.config/obs-studio/global.ini`):

1. **Legge lo stream del telefono** (`/stream.info`) e aspetta frame YUV reali (non solo il server HTTP acceso).
2. **Assegna / riusa** un nodo `/dev/videoN`: il kernel sceglie l'indice del minor libero (auto-assignment — non viene pinnato `video_nr` né `card_label`). Aggiorna lo state in `/tmp/androidcamera-v4l2/devices.tsv`.
3. **Se il nodo non esiste** (o va ricreato): stacca temporaneamente le source V4L2 OBS da quei device, poi esegue `modprobe v4l2loopback` (può chiedere la password root). Se il nodo con quel nome c'è già, questo passo si salta.
4. **Rilascia il device per il cambio formato** (OBS WebSocket, senza cancellare le source):
   - disabilita le scene item che usano quel `/dev/videoN`;
   - svuota temporaneamente `device_id` sulle source V4L2 collegate (così OBS chiude il file descriptor e `v4l2loopback` può cambiare larghezza/altezza/FPS).
5. **Imposta il formato** sul loopback e avvia il bridge `curl | ffmpeg` → `/dev/videoN` con risoluzione e FPS dell'app.
6. **Verifica** che il loopback riporti davvero `WxH` attesi.
7. **Aggiorna OBS** (preferito: senza rimuovere la source):
   - ripristina `device_id` + risoluzione + FPS + pixel format sulla source già presente;
   - riabilita le scene item.
8. **Fallback** (solo se l'aggiornamento in-place non basta, es. OBS continua a mostrare la vecchia size): rimuove quella source da tutte le scene che la contenevano e la **ricrea nelle stesse scene** con i nuovi dati (e, se possibile, gli stessi transform).
9. Controlla che `/dev/videoN` produca frame (o che il bridge sia attivo e il device già in uso da OBS).

**Cosa non fa il pulsante:** non cambia la scena attiva di OBS, non crea scene nuove, non inventa source V4L2 se non ne esiste già una collegata a quel device (in quel caso va aggiunta una volta a mano in OBS; i click successivi aggiornano i dati). Non ricarica il modulo solo perché ne hai disabilitato un altro: per quello c'è **Ricarica dispositivi**.

Per lo step OBS serve il pacchetto opzionale `obsws-python` (`pip install --user obsws-python`). Senza WebSocket / con OBS chiuso il bridge V4L2 parte comunque; in OBS andrà aggiornata o aggiunta la source a mano.

## Lingua

Pulsanti **IT** / **EN** in alto a destra. Le stringhe sono in moduli separati:

- `android_camera_gui/i18n/it.py`
- `android_camera_gui/i18n/en.py`
- contratto: `android_camera_gui/i18n/strings_base.py`

La scelta è salvata in `~/.config/androidcamera-gui/config.json`.