# AndroidCamera Webcam

Turns the phone cameras into an OBS-ready **YUV** webcam over **USB** or **IP (LAN)**.

## Features

- USB Tethering mode + virtual V4L2 webcam (recommended for OBS)
- IP / LAN mode using the Wi‑Fi address
- Rear or front camera
- Configurable resolution, FPS (15–60), and orientation
- Custom phone name shown in OBS (supports multiple phones at once)
- Live preview in the app while streaming
- Light / dark theme and English / Italian
- Foreground notification while streaming

## Architecture

- `ui` – MainActivity and layout binding
- `service` – foreground `CameraStreamService` + notification
- `camera` – CameraX capture (YUV I420)
- `streaming` – raw YUV HTTP server (`/stream.yuv`, `/stream.info`)
- `connection` – USB / IP endpoint resolution
- `theme` / `locale` – reload colors and strings from XML
- `data` – preferences

## OBS setup (USB Tethering)

1. Connect the phone to the PC with a USB cable and **manually enable USB Tethering** on the phone  
   (Settings → Network / Connections → Hotspot & tethering → **USB Tethering**).  
   This is required for cable use (as an alternative to Wi‑Fi / IP LAN mode).
2. In the app set a unique **phone name**, resolution and FPS, then start the service (**USB Tethering** mode).
3. On the PC open `AndroidCameraLinuxGui` and click **Enable for OBS**:

```bash
cd AndroidCameraLinuxGui && ./run.sh
```

4. In OBS: **Sources → Video Capture Device (V4L2)** → select the **phone name**.

The GUI creates a v4l2loopback device labeled with that name (e.g. `/dev/video10+`) at the resolution/FPS from the app. Enable once per phone to use several phones together.

## Build & install

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:installDebug
```
