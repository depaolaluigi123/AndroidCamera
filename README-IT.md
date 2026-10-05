# AndroidCamera

Progetto per usare le telecamere di uno o più telefoni Android come webcam V4L2 su Linux (OBS).

```
AndroidCamera/                 ← questa cartella (repo genitore)
├── AndroidCamera/             # app Android
├── AndroidCameraLinuxGui/     # GUI Python per abilitare i telefoni in OBS
├── AndroidCameraUsbDriver/    # driver userspace USB Device (ACUS → V4L2)
├── .env                       # ACUS_MAX_RETRIES
├── run-linux-gui.sh
└── README.md                  # questo file
```

### Modalità di collegamento (app Android)

| Modalità | Trasporto | Note |
| --- | --- | --- |
| **USB Tethering** | HTTP YUV su IP RNDIS | Serve tethering USB attivo |
| **USB Device** | Protocollo binario ACUS via `adb forward` | Solo debug USB; il driver `acus_driver` scrive su `/dev/videoN` |
| **IP (LAN)** | HTTP YUV su Wi‑Fi | Stessa rete del PC |

- App: vedi [AndroidCamera/README.md](AndroidCamera/README.md)
- GUI: `./run-linux-gui.sh` (o vedi [AndroidCameraLinuxGui/README.md](AndroidCameraLinuxGui/README.md))

## Pacchetti necessari (PC Linux)

 Sul computer servono questi componenti di sistema (Debian/Ubuntu e derivate):

| Pacchetto | A cosa serve |
| --- | --- |
| `v4l2loopback-dkms` | Modulo kernel che crea le webcam virtuali `/dev/videoN` |
| `v4l2loopback-utils` | Utility opzionali (`v4l2loopback-ctl`, ecc.) |
| `ffmpeg` | Bridge dallo stream YUV del telefono verso il device V4L2 |
| `curl` | Scarica lo stream HTTP dal telefono e lo passa a `ffmpeg` |
| `adb` / platform-tools | Scopre i telefoni in USB debug (la GUI cerca anche `~/Android/Sdk/platform-tools/adb`) |
| `python3` + `python3-tk` | GUI in `AndroidCameraLinuxGui` |
| header/kernel per DKMS | Compilare `v4l2loopback` (di solito `linux-headers-$(uname -r)`) |

### Script di installazione

Dalla cartella genitore:

```bash
./install-deps.sh              # pacchetti base (+ adb da apt)
```

In alternativa, a mano:

```bash
sudo apt update
sudo apt install -y \
  v4l2loopback-dkms v4l2loopback-utils \
  ffmpeg curl \
  python3 python3-tk \
  adb \
  linux-headers-$(uname -r)
```

Se hai già Android SDK, la GUI usa anche `~/Android/Sdk/platform-tools/adb` (vedi `AndroidCameraLinuxGui/run.sh`).

OBS Studio è opzionale ma è il target tipico (`obs-studio` + source **Video Capture Device (V4L2)**).

La GUI Python non ha dipendenze pip obbligatorie (solo libreria standard + `tkinter`). Per aggiornare automaticamente risoluzione/FPS sulle source V4L2 già presenti in OBS: `pip install --user obsws-python` (OBS con WebSocket attivo).

## Modulo kernel `v4l2loopback`

**Non** viene caricato automaticamente a ogni avvio del PC. Il pacchetto `v4l2loopback-dkms` lo installa/compila; il caricamento avviene ad **ogni avvio della GUI** a cura di `run.sh` (vedi sotto), non da un service systemd.

Al **riavvio del computer** il modulo viene scaricato insieme al resto del kernel: i `/dev/videoN` creati in sessione precedente spariscono, e anche `/tmp/androidcamera-v4l2/` (state + PID bridge) viene svuotato. Non c'è un demone che "riaccende" da solo le webcam Android: dopo il boot basta rilanciare la GUI (`./run-linux-gui.sh`).

### Caricamento degli slot da `run.sh`

`run-linux-gui.sh` (che richiama `AndroidCameraLinuxGui/run.sh`) carica `v4l2loopback` con **almeno** `V4L2_SLOTS` slot (letto dal file `.env`) **prima** di far partire la GUI Python:

- se il modulo ha già ≥ N slot **nostri** (driver `v4l2loopback` in `/sys/class/video4linux`; webcam reali non contano) → non fa nulla (avvio immediato, nessuna password);
- se non è caricato → `modprobe v4l2loopback devices=N`;
- se ne ha meno di N → lo scarica e lo ricarica con N slot;
- se lo scaricamento fallisce (OBS/VLC tengono aperto un `/dev/videoN`) → **blocca** la GUI con un errore chiaro.

`run.sh` usa **auto-assignment** (nessun `video_nr`/`card_label`):

```bash
sudo modprobe v4l2loopback devices=N exclusive_caps=0 max_buffers=8
```

Parametri importanti:

- `devices` – quanti nodi virtuali creare (=`V4L2_SLOTS`); gli indici `/dev/videoN` sono scelti dal kernel tra i minor liberi
- `exclusive_caps=0`, `max_buffers=8` – impostazioni testate col driver ACUS e la GUI

**Perché non pinniamo `video_nr`/`card_label`.** Sul dkms in uso l'array `video_nr` ha dimensione **fissa (8 entry)**: passare una lista di N>8 elementi fa fallire `modprobe` con `EINVAL` (verificato con `V4L2_SLOTS=10`). Con auto-assignment il modulo accetta N>8. Il lato negativo è che non controlliamo quali `/dev/videoN` vengono assegnati (può essere incluso `/dev/video0`): non è un problema, perché la GUI alloca/riassegna i `/dev/videoN` per-telefono leggendo `/sys/class/video4linux` e facendo `mknod`, e riapplica i `card_label` a runtime via `v4l2-ctl`. Per questo `V4L2_SLOTS` va tenuto ≤ 8 se il modulo espone solo 8 entry in `parameters/video_nr` (valori più alti falliscono).

Per ottenere i privilegi di root necessari a `modprobe`, `run.sh` richiede il ticket sudo in anticipo (`sudo -v`): se non esiste già un ticket valido, chiede la password **una sola volta** all'inizio, poi riusa il ticket per i `modprobe` successivi. In alternativa puoi lanciare `sudo ./run-linux-gui.sh`.

### Come si avvia di solito

1. Collega il telefono al PC con il cavo USB e **attiva manualmente USB Tethering** sul telefono  
   (Impostazioni → Rete / Connessioni → Hotspot e tethering → **USB Tethering** / Tethering USB).  
   Serve per la connessione via cavo (alternativa alla Wi‑Fi / modalità IP LAN).
2. Nell'app Android avvia il servizio webcam in modalità **USB Tethering**.
3. Sul PC: `./run-linux-gui.sh` (carica gli slot V4L2, poi apre la GUI).  
   Nella GUI premi **Abilita per OBS** per hostare il telefono come `/dev/videoN`.  
   Sequenza completa del pulsante:  
   [AndroidCameraLinuxGui/README.md — Cosa fa il pulsante Abilita per OBS](AndroidCameraLinuxGui/README.md#cosa-fa-il-pulsante-abilita-per-obs).

**Disabilita** ferma solo il bridge e aggiorna lo state: non scarica/ricarica il modulo. Per ricostruire i nodi (es. dopo aver disabilitato alcuni telefoni) usa **Ricarica dispositivi** nella GUI — vedi [AndroidCameraLinuxGui/README.md — Pulsanti principali](AndroidCameraLinuxGui/README.md#pulsanti-principali).

Verifica che il modulo sia caricato:

```bash
lsmod | grep v4l2loopback
v4l2-ctl --list-devices   # se installato v4l2-utils
cat /sys/module/v4l2loopback/parameters/devices   # slot effettivi
```

Scaricare il modulo (solo se nessun programma lo sta usando, es. OBS chiuso):

```bash
sudo modprobe -r v4l2loopback
```

### Modificare il numero di slot

Edita `V4L2_SLOTS` nel file `.env` (intero >= 1, default 10, consigliato >= 10), poi:

1. **chiudi OBS/VLC** e gli altri programmi che usano una webcam (altrimenti `run.sh` non può scaricare `v4l2loopback` per ricreare gli slot);
2. rilancia `./run-linux-gui.sh`.

Non serve né un service systemd né riavviare il computer: gli slot vengono
ricreati ad ogni avvio della GUI da `run.sh`.

> ⚠️ Non creare manualmente `/etc/modules-load.d/v4l2loopback.conf`: quel file
> causerebbe un caricamento "nudo" di `v4l2loopback` al boot con i parametri
> di **default**, che poi impedirebbe a `run.sh` di scaricare il modulo per
> ricreare gli slot col numero richiesto.

## Stato runtime sul PC: `/tmp/androidcamera-v4l2/`

Quando abiliti un telefono (GUI **Abilita per OBS**, sul computer viene usata questa cartella temporanea — **non** `/etc/`.

| Percorso | Ruolo |
| --- | --- |
| `/tmp/androidcamera-v4l2/` | Cartella di stato del bridge V4L2 |
| `/tmp/androidcamera-v4l2/devices.tsv` | Elenco telefoni abilitati (nome → `/dev/videoN`, URL stream, risoluzione, FPS, …) |
| `/tmp/androidcamera-v4l2/<nome>.pid` | PID del processo bridge (`curl` \| `ffmpeg`) |
| `/tmp/androidcamera-v4l2/<nome>.log` | Log del bridge |

Le webcam virtuali compaiono come nodi kernel:

- `/dev/videoN` (es. `/dev/video2`, `/dev/video11`)
- Nome in OBS = nome telefono impostato nell’app (`card_label` di `v4l2loopback`)

### Formato di `devices.tsv`

Una riga per telefono, campi separati da tab:

```text
label  video_nr  port  stream_url  in_w  in_h  out_w  out_h  fps  rotation
```

Esempio:

```text
android1	11	8080	http://192.168.95.71:8080/stream.yuv	640	480	640	480	25	0
```

### Come viene scelto `N` in `/dev/videoN`

1. Se esiste già un loopback con lo stesso nome → si riusa quel numero  
2. Altrimenti, se il nome è già in `devices.tsv` → si riusa il `video_nr` salvato  
3. Altrimenti → primo libero tra **0 e 255** che non sia già presente in `/sys/class/video4linux` né nello state (così non si collide con la webcam del PC, ecc.)

La cartella è sotto `/tmp`: si svuota al riavvio del PC; in quel caso basta riabilitare i telefoni dalla GUI.