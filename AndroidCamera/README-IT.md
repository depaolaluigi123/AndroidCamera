# AndroidCamera Webcam

Trasforma le telecamere del telefono in webcam **YUV** OBS-ready tramite **USB** o **IP (LAN)**.

## Funzionalità

- Modalità Tethering USB + webcam virtuale V4L2 (consigliata per OBS)
- Modalità IP / LAN usando l'indirizzo Wi‑Fi
- Telecamera posteriore o anteriore
- Risoluzione, FPS (15–60) e orientamento configurabili
- Nome telefono personalizzato mostrato in OBS (supporta più telefoni contemporaneamente)
- Anteprima live nell'app durante lo streaming
- Tema chiaro/scuro e lingua inglese/italiano
- Notifica in primo piano durante lo streaming

## Architettura

- `ui` – MainActivity e binding del layout
- `service` – servizio in primo piano `CameraStreamService` + notifica
- `camera` – acquisizione CameraX (YUV I420)
- `streaming` – server HTTP raw YUV (`/stream.yuv`, `/stream.info`)
- `connection` – risoluzione endpoint USB / IP
- `theme` / `locale` – ricarica di colori e stringi da XML
- `data` – preferenze

## Configurazione OBS (USB Tethering)

1. Collega il telefono al PC con un cavo USB e **attiva manualmente il Tethering USB** sul telefono  
   (Impostazioni → Rete / Connessioni → Hotspot & tethering → **USB Tethering**).  
   Questo è richiesto per l'uso con cavo (come alternativa a Wi‑Fi / modalità IP LAN).
2. Nell'app imposta un **nome telefono** univoco, risoluzione e FPS, quindi avvia il servizio (**modalità USB Tethering**).
3. Sul PC apri `AndroidCameraLinuxGui` e clicca **Abilita per OBS**:

```bash
cd AndroidCameraLinuxGui && ./run.sh
```

4. In OBS: **Sources → Video Capture Device (V4L2)** → seleziona il **nome del telefono**.

La GUI crea un device loopback v4l2 etichettato con quel nome (es. `/dev/video10+`) alla risoluzione/FPS dell'app. Abilita una volta per telefono per usare più telefoni insieme.

## Build & install

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:installDebug
```