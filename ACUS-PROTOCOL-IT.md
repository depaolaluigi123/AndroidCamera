# Specifica del Protocollo ACUS

## Indice

1. [Cosa è ACUS?](#cosa-è-acus)
2. [Architettura e Topologia di Connessione](#architettura-e-topologia-di-connessione)
3. [Formato del Protocollo](#formato-del-protocollo)
   - [Stabilimento della Connessione e Handshake](#stabilimento-della-connessione-e-handshake)
   - [Dati per Frame](#dati-per-frame)
4. [Implementazione Android](#implementazione-android)
5. [Implementazione del Driver Linux](#implementazione-del-driver-linux)
6. [Modulo di Supporto ACUS per Python](#modulo-di-supporto-acus-per-python)
7. [Proxy di Anteprima ACUS (Bridge HTTP/WebSocket)](#proxy-di-anteprima-acus-httpwebsocket-bridge)
8. [Integrazione nella Scoperta](#integrazione-nella-scoperta)
9. [Decisioni Chiave di Progettazione](#decisioni-chiave-di-progettazione)
10. [Requisiti di Build](#requisiti-di-build)
11. [Estensionioni del Protocollo](#estensionioni-del-protocollo)
    - [Estensione Compres compressione JPEG](#estensione-compres-compressione-jpeg)
    - [Estensione Batch di Frame](#estensione-batch-di-frame)

---

## Cosa è ACUS?

**ACUS** sta per **Android Camera USB**. È un protocollo binario leggero e a bassa latenza, progettato per trasmettere frame dalle telecamere di un telefono Android a un PC Linux tramite una connessione USB di debug, senza richiedere HTTP, WiFi o alcuna connettività di rete.

Il caso d'uso principale è trasformare un telefono in modalità USB Device in una webcam V4L2 (Video4Linux2) su Linux, utilizzabile da applicazioni come OBS. Il telefono esegue un server ACUS che trasmette frame I420 (YUV420) grezzi; un driver Linux li riceve tramite `adb forward` e li scrive su un nodo device loopback V4L2.

---

## Architettura e Topologia di Connessione

```
┌─────────────────────────┐          adb forward           ┌──────────────────────┐
│  Telefono Android       │  USB debug  tcp:PORT  tcp:PORT │  PC Linux            │
│                         │ ◄─────────────────────────────────►                 │
│  ┌───────────────────┐  │                                   │  ┌─────────────┐  │
│  │ UsbDeviceStream   │  │                                   │  │ acus_driver │  │
│  │ Server (Kotlin)   │  │         Stream binario ACUS        │  │ (binario C)  │  │
│  │ 127.0.0.1:PORT    │─────── localhost TCP (nessuna rete) ──► │             │  │
│  └───────────────────┘  │                                   │  └──────┬──────┘  │
│         │               │                                   │  V4L2 loopback    │
│         │ YUV I420      │                                   │  /dev/videoN      │
│         ▼               │                                   │         ▼          │
│  ┌───────────────────┐  │                                   │  OBS / ffmpeg      │
│  │  FrameBroker      │  │                                   │  Acquisizione Video│
│  │  (frame dalla camera)│  │                                   └────────────────────┘
│  └───────────────────┘  │
└─────────────────────────┘
```

**Catena di trasporto:**
1. Telefono: `FrameBroker` acquisisce frame dalla camera in formato I420.
2. Telefono: `UsbDeviceStreamServer` si lega a `127.0.0.1:PORT` (solo localhost) e trasmette frame ACUS grezzi su TCP.
3. USB: `adb forward tcp:PORT tcp:PORT` tunnella la connessione TCP sul bridge USB di debug.
4. Linux: `acus_driver` si connette a `localhost:PORT`, riceve frame ACUS, e scrive dati I420 sul device loopback V4L2 (`/dev/videoN`).
5. Linux: le applicazioni (OBS, ffmpeg, ecc.) consumano il device come una webcam V4L2 standard.

---

## Formato del Protocollo

Tutti gli interi multi-byte sono in **big-endian** (ordine di rete). Il protocollo opera su un socket TCP grezzo.

### Stabilimento della Connessione e Handshake

L'handshake viene inviato **una volta** dal server Android all'inizio di ogni connessione. Viene inviato **dopo** che il primo frame dalla camera è stato acquisito, in modo che i campi `width`, `height`, e `fps` riflettano i parametri effettivi della camera live anziché valori placeholder.

```
┌────────────┬─────────────┬───────────────────────────────┐
│ Offset     │ Dimensione  │Campo                          │
├────────────┼─────────────┼───────────────────────────────┤
│ 0          │ 4 byte      │ Byte magici: "ACUS" (0x41 0x43 0x55 0x53) │
│ 4          │ 1 byte      │ Versione protocollo = 1           │
│ 5          │ 1 byte      │ Flag (bitmask):               │
│            │             │   bit 0: supporto JPEG (1 = supportato) │
│            │             │   bit 1–7: Riservati            │
│ 6–7        │ 2 byte      │ Larghezza frame in pixel (u16 BE) │
│ 8–9        │ 2 byte      │ Altezza frame in pixel (u16 BE)│
│ 10–11      │ 2 byte      │ FPS target (u16 BE)            │
│ 12–13      │ 2 byte      │ Rotazione del dispositivo (u16 BE, gradi, 0/90/180/270) │
│ 14         │ 1 byte      │ Lunghezza del nome del dispositivo in byte (u8)│
│ 15 … 15+name_len-1 │ name_len byte │ Nome del dispositivo (UTF-8)          │
│ 15+name_len│ 1 byte      │ Qualità JPEG (u8, 10–100, solo se bit 0 dei flag è 1) │
└────────────┴─────────────┴───────────────────────────────┘
```

**Dimensione dell'header:** 15 o 16 byte + `name_len` (il byte di qualità JPEG è presente
**se e solo se** il bit flag JPEG è impostato — non è una coppia fissa).

Il server risponde sempre con Protocol v1; i flag riflettono ciò che l'utente ha
effettivamente selezionato nell'app Android:

| Selezione utente nell'app Android | Byte dei flag    | Byte aggiuntivi dopo il nome           |
|----------------------------------|------------------|----------------------------------------|
| YUV                              | `0x00`           | nessuno                                |
| JPEG                             | `0x01` (JPEG)    | `jpeg_quality` (1 byte)                |

Poiché i byte aggiuntivi sono determinati dai flag, il client (driver C, `acus_driver.probe` Python, proxy di anteprima `_read_handshake`) deve leggere esattamente il numero di byte che i flag dichiarano — non leggere mai 1 byte fissato.

---

### Dati per Frame

Ogni frame in entrata dalla camera è avvolto in un'astrazione ACUS:

```
┌────────────┬─────────────┬────────────────────────────────┐
│ Offset     │ Dimensione  │ Campo                          │
├────────────┼─────────────┼────────────────────────────────┤
│ 0          │ 4 byte      │ Magic frame: "FRME" (0x46 0x52 0x4D 0x45) │
│ 4–7        │ 4 byte      │ Numero di sequenza (u32 BE, inizia a 1) │
│ 8–11       │ 4 byte      │ Dimensione dati frame in byte (u32 BE)     │
│ 12 … 12+size-1 │ size byte │ Dati frame (formato per handshake) │
└────────────┴─────────────┴────────────────────────────────┘
```

**Formato dati frame** (per handshake):
- **Versione 1 / I420 (YUV420) formato planare** per un frame `width × height`:
  - Piano Y: `width × height` byte (risoluzione completa)
  - Piano U: `(width/2) × (height/2)` byte (risoluzione ridotta a un quarto)
  - Piano V: `(width/2) × (height/2)` byte (risoluzione ridotta a un quarto)
  - **Dimensione totale:** `width × height × 3 / 2` byte.

- **Versione 1 / formato JPEG compresso** (quando bit 0 dei flag è impostato):
  - Un singolo frame JPEG compresso (dimensione variabile per frame)
  - Il numero di sequenza incrementa comunque per ogni frame

---

## Implementazione Android

**File sorgente:** `AndroidCamera/app/src/main/java/com/androidcamera/webcam/streaming/UsbDeviceStreamServer.kt`

Il server è implementato in Kotlin come un server TCP basato su coroutine:

```kotlin
const val PROTOCOL_VERSION: Byte = 1             // ACUS Protocol v1
const val FLAG_JPEG: Int = 0x01                  // bit 0: trasporto JPEG
val MAGIC_ACUS: ByteArray = byteArrayOf(0x41, 0x43, 0x55, 0x53) // "ACUS"
val MAGIC_FRME: ByteArray = byteArrayOf(0x46, 0x52, 0x4D, 0x45) // "FRME"
```

**Dettagli chiave dell'implementazione:**

- **Indirizzo di binding:** `127.0.0.1` solo — il server non è mai legato a un IP accessibile dalla rete. È raggiungibile esclusivamente attraverso `adb forward`.
- **TCP_NODELAY:** Attivato sul socket per disabilitare l'algoritmo di Nagle, minimizzando la latenza.
- **Handshake differito:** Il server attende il primo frame dal `FrameBroker` (nel formato selezionato dall'utente) prima di inviare l'handshake. Questo garantisce che l'handshake riporti la risoluzione/live camera effettiva, non valori placeholder.
- **Flag determinati dal formato:** Il byte dei flag è costruito da `StreamConfig.streamFormat` (JPEG → `FLAG_JPEG`); non è un valore hardcoded. Un utente YUV ottiene `flags=0x00` e nessun byte extra — la GUI mostra quindi `format=YUV` e `JPEG q=—`.
- **Client concorrenti:** Supportati tramite `CopyOnWriteArrayList`. Tutti i client connessi ricevono ogni frame.
- **Definizione:** Dopo l'handshake, il server scrive ogni frame come: header da 12 byte + dati grezzi del frame. Il payload del frame è ciò che il `FrameBroker` ha pubblicato (I420 per utenti YUV, byte JPEG per utenti JPEG). Il client è responsabile della lettura della dimensione esatta del payload annunciato prima di leggere il prossimo header.
- **Compressione JPEG:** Quando `StreamConfig.streamFormat = JPEG`, il `CameraStreamController` codifica ogni frame I420 in JPEG alla qualità configurata **prima** di pubblicarlo sul broker; il server inoltra quei byte verbatim. Non avviene alcuna ricodifica a livello di streaming.

---

## Implementazione del Driver Linux

**File sorgente:** `AndroidCameraUsbDriver/src/acus_driver.c`

Il driver è un singolo file C che implementa due modalità operative:

### Modalità Probe (`--probe`)

Esegue solo lo scambio dell'handshake, stampa i metadati dello stream come JSON su stdout, quindi termina. Utilizzato dal sistema di scoperta per rilevare se un telefono connesso parla ACUS.

```bash
./acus_driver --probe --device /dev/videoX --serial 0x1234 --port 2743
```

> **Riferimento parole chiave:**
> - `--probe` — Attiva la modalità "probe": esegue solo l'handshake con il dispositivo ACUS, stampa i metadati dello stream come JSON su stdout e termina. Utilizzato dal sistema di scoperta per verificare se un telefono connesso supporta ACUS.
> - `--device /dev/videoX` — Specifica il percorso del device V4L2 loopback su cui scrivere i frame (necessario anche in modalità probe per stabilire il contesto del device).
> - `--serial 0x1234` — Identificatore seriale del dispositivo ADB (ottenibile con `adb devices -l`). Permette al driver di comunicare con il telefono specifico quando più dispositivi sono connessi.
> - `--port 2743` — Porta TCP su cui il server ACUS (lato Android) è in ascolto. Il valore predefinito e consigliato è `2743`.

Esempio di output (YUV — l'utente ha scelto I420 grezzo):
```json
{"deviceName":"Pixel 7","width":1920,"height":1080,"outputWidth":1920,"outputHeight":1080,"fps":30,"rotation":0,"pixelFormat":"yuv420p","format":"YUV","ready":true,"transport":"acus","protocol_version":1,"jpeg_supported":false,"jpeg_quality":0}
```

Esempio di output (JPEG q75):
```json
{"deviceName":"Pixel 7","width":1920,"height":1080,"outputWidth":1920,"outputHeight":1080,"fps":30,"rotation":0,"pixelFormat":"mjpeg","format":"JPEG","ready":true,"transport":"acus","protocol_version":1,"jpeg_supported":true,"jpeg_quality":75}
```

Il livello di scoperta Python accetta entrambe le forme snake_case (`jpeg_quality`, come emette il probe C) e camelCase (`jpegQuality`, come emette l'endpoint HTTP `/stream.info`).

### Modalità Device (`--device /dev/videoN`)

Modalità di streaming completa:

1. Configura `adb forward` per tunnare `tcp:PORT` su localhost verso `localhost:PORT` sul dispositivo.
2. Apre un socket TCP verso `127.0.0.1:PORT` con `TCP_NODELAY`.
3. Legge e valida l'handshake ACUS: controlla la magic "ACUS" e accetta **solo versione 1**. Qualsiasi altra cosa è rifiutata (restituisce un errore / JSON probe assente). I byte finali determinati dai flag vengono letti esattamente come i flag dichiarano (nessuno o 1 byte), mai come una coppia fissa.
4. Apre il device loopback V4L2 e usa ioctl `VIDIOC_S_FMT` per impostare il formato di acquisizione su `V4L2_PIX_FMT_YUV420` (utenti YUV grezzi) o `V4L2_PIX_FMT_MJPEG` con fallback a `V4L2_PIX_FMT_JPEG` (utenti JPEG) alla risoluzione annunciata.
5. Legge le astrazioni in un ciclo: legge l'header da 12 byte, valida la magic "FRME", legge il numero di byte annunciato, scrive sul device V4L2.
   - Per i frame JPEG, passa attraverso V4L2 (se il formato è JPEG) o decomprime a YUV420.
6. Installa gestori per `SIGINT` e `SIGTERM` per un arrestro pulito.

```bash
./acus_driver --device /dev/videoX --serial 0x1234 --port 2743
```

> **Nota:** La modalità device è la modalità completa di streaming. Il driver configura automaticamente il forward ADB, si connette al server ACUS e scrive i frame I420 sul device V4L2 specificato. Per i dettagli su singole parole chiave, vedere la sezione [Modalità Probe](#modalità-probe---probe) sopra.

---

## Modulo di Supporto ACUS per Python

**File sorgente:** `AndroidCameraLinuxGui/android_camera_gui/acus_driver.py`

Un wrapper Python che fornisce:

- **`probe(adb_serial, port)`** — Apre una connessione ACUS, esegue l'handshake, restituisce un dizionario con `width`, `height`, `fps`, `rotation`, `name`, `protocol_version`, `jpeg_supported`, `jpeg_quality`. Utilizzato durante la scoperta del dispositivo.
- **`driver_path()`** — Individua il binario C `acus_driver` compilato.
- **`ensure_built()`** — Compila il binario C tramite `make` se non è già stato compilato.
- **`parse_acus_url(url)`** — Analizza URL nella forma `acus://SERIAL@HOST:PORT` (utilizzato internamente dalla GUI per identificare il trasporto ACUS).

---

## Proxy di Anteprima ACUS (Bridge HTTP/WebSocket)

**File sorgente:** `AndroidCameraLinuxGui/android_camera_gui/acus_preview_proxy.py`

Un server HTTP/WebSocket locale che bridge il flusso binario ACUS ai client di anteprima basati su browser. Quest evita la necessità di un'app nativa per visualizzare il feed della camera.

**Endpoint HTTP** (serviti su `127.0.0.1` solo):

| Endpoint          | Descrizione                                              |
|--------------------|----------------------------------------------------------|
| `GET /stream.info` | Restituisce metadati JSON: `{width, height, fps, name, protocol_version, jpeg_supported}` |
| `GET /stream.yuv`  | Restituisce byte I420 grezzi per il frame corrente (un tentativo)  |
| `GET /stream.mjpeg`| Restituisce lo stream MJPEG per anteprima nel browser                 |
| `GET /acus/ws`     | Endpoint WebSocket per l'anteprima in tempo reale            |

**Architettura:**

- Un thread di background `_AcusHub` gestisce la sottostante connessione TCP ACUS con logica di retry automatico e gestione dei timeout.
- Più istanze proxy possono coesistere, identificate per `(adb_serial, phone_port)`.
- I client WebSocket ricevono aggiornamenti dei frame in tempo reale dall'hub, permettendo un'anteprima nel browser in tempo reale con latenza minima.

---

## Integrazione nella Scoperta

**File sorgente:** `AndroidCameraLinuxGui/android_camera_gui/discovery.py`

Il sistema di scoperta del dispositivo sonda i telefoni in un ordine specifico, provando prima i trasporti più veloci/fidabili:

1. **ACUS (modalità USB Device)** — `adb forward` + ACUS. Provato per primo perché funziona senza WiFi e con solo debug USB. Se la chiamata `acus_driver --probe` ha successo, il dispositivo viene contrassegnato con l'etichetta di trasporto `"USB Device (ACUS)"`.
2. **USB Tethering + HTTP** — Se ACUS fallisce, prova l'endpoint HTTP del server del telefono tramite un tunnel HTTP `adb forward`, con fallback all'HTTP diretto su USB tethering.

Questo ordinamento di priorità significa che un telefono collegato via USB userà sempre il trasporto ACUS se disponibile, facendo fallback all'HTTP solo se ACUS non è in esecuzione sul telefono.

---

## Decisioni Chiave di Progettazione

### Perché un protocollo binario personalizzato invece di HTTP/RTSP?

ACUS dà priorità a **bassa latenza e overhead minimo**. HTTP aggiunge overhead significativo per ogni frame (header, codifica a blocchi) e tipicamente richiede uno stack server più complesso. RTSP/RTP aggiunge complessità del protocollo di rete ed è progettato per reti multi-hop, non per un tunnel diretto localhost-to-USB. ACUS è ottimizzato per il percorso diretto e a bassa latenza tra il broker della camera e il tunnel USB.

### Perché I420 (YUV420) e non un formato compresso come JPEG o H.264?

Il telefono sta già acquisendo in formato YUV dall'API della camera. Trasmettere YUV grezzo evita il costo CPU della codifica e la latenza della compressione. Il tunnel `adb forward` ha sufficiente larghezza di banda per 1080p@30fps YUV grezzo (`1920×1080×1.5 = ~3MB per frame ≈ 720 Mbps`), che rientra nella capacità di USB 2.0 High Speed per brevi burst. Per risoluzioni più alte o frame rate più alti su USB 2.0, il driver e il broker supportano il drop selettivo di frame.

**Protocol v1** supporta inoltre opzionalmente la compressione JPEG, utile per lo streaming WiFi/LAN dove la larghezza di banda è più vincolata. Questo permette di scambiare qualità con frame rate in base alle condizioni di rete.

### Perché il binding solo su localhost sull'Android?

Legare il server ACUS a `127.0.0.1` significa che è **completamente inaccessibile dalla rete**. L'unico modo per raggiungerlo è attraverso il tunnel `adb forward`, che richiede l'autorizzazione del debug USB. Questo fornisce sicurezza attraverso l'isolamento di rete: anche se l'indirizzo IP del telefono è noto, il server ACUS non può essere contattato senza accesso USB fisico e autorizzazione adb.

### Perché il loopback V4L2?

Le applicazioni video Linux (OBS, ffmpeg, Cheese, ecc.) sono basate sull'API V4L2. Fornire un nodo dispositivo loopback `/dev/videoN` fa apparire la camera Android come una webcam standard con zero sforzo di integrazione — nessun SDK o plugin personalizzato necessario sul lato consumatore.

### Perché ordine big-endian?

ACUS è stato progettato per rispecchiare le convenzioni dei protocolli di rete (big-endian / ordine di rete). Quest rende il formato del protocollo compatibile con il modo in cui la maggior parte degli strumenti e delle librerie di rete gestisce gli interi multi-byte, semplificando il debug con strumenti come Wireshark o `nc`.

### Perché adb forward invece che un gadget USB raw?

Utilizzare `adb forward` sfrutta l'infrastruttura esistente di debug USB di Android, che è:
- Già autorizzata quando il debug USB è accettato.
- Stabile attraverso le versioni di Android (nessun driver USB gadget per dispositivo necessario).
- Indipendente dal tethering USB essere abilitato.

 Il compromesso è che `adb` aggiunge un piccolo overhead per frame, ma è trascurabile.

---

## Estensionioni del Protocollo

### Estensione Compres compressione JPEG (Flag bit 0)

Quando il flag JPEG è impostato nell'handshake:
- I frame vengono trasmessi come immagini JPEG comprimate invece di I420 grezzi.
- L'handshake include l'impostazione della qualità JPEG (10-100).
- Questo riduce significativamente la larghezza di banda a costo di CPU per la codifica e un certo perdita di qualità.
- Utile per lo streaming WiFi/LAN dove la larghezza di banda è limitata.

---

## Requisiti di Build

### Lato Android
- API camera2 di Android (API 21+)
- Nessuna dipendenza nativa aggiuntiva

### Lato Linux
- Un compilatore C (gcc o clang)
- `make`
- Header kernel V4L2 (`linux/videodev2.h`)
- `adb` in PATH
- Opzionale: modulo kernel `v4l2loopback` (`modprobe v4l2loopback`)

### Lato GUI Python
- Python 3
- `pyzmq` (per il proxy WebSocket)
- Android `adb` binding Python (tramite `adb_shell` o simile)