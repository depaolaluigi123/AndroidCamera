# AndroidCamera Linux GUI

Linux GUI to connect **AndroidCamera Webcam** phones to OBS.

It sits next to the Android project:

```
GitHub Projects/
├── AndroidCamera/           # Android app
└── AndroidCameraLinuxGui/   # this GUI
```

## What it does

1. Lists phones connected via USB (ADB) with the **webcam service active**
2. Shows the **name** set in the app, resolution and FPS
3. With **Enable for OBS** creates a V4L2 webcam with that name:
   - **USB Tethering / IP**: `curl` + `ffmpeg` from the YUV HTTP stream
   - **USB Device**: `AndroidCameraUsbDriver` driver (`acus_driver`) via `adb forward` (no HTTP)
4. With **Disable** stops the bridge for that phone (no password / no `modprobe`)
5. With **Reload devices** reconstructs the `/dev/video*` nodes from the state (root password)
6. In OBS: **Sources → Video Capture Device (V4L2)** → select the phone name

## Requirements

- Python 3.10+ with `tkinter` (`python3-tk` on Debian/Ubuntu)
- `adb` (Android platform-tools)
- `v4l2loopback-dkms`, `ffmpeg`, `curl`
- Root permissions when `modprobe` is needed (**Enable for OBS** if nodes need to be created, or **Reload devices**) via `sudo` or `pkexec`

## Launch

From the monorepo root (also loads `.env`):

```bash
./run-linux-gui.sh
```

or from the subdirectory:

```bash
cd AndroidCameraLinuxGui
./run.sh
```

or:

```bash
python3 -m android_camera_gui
```

## Usage

1. On the phone: open AndroidCamera Webcam, set a unique name, choose the mode
   (**USB Tethering**, **USB Device** or **IP**) and start the service
2. Connect the phone in USB debug mode and authorize the PC  
   (for **USB Device** tethering is not required nor is Wi‑Fi)
3. In the GUI press **Refresh**, select the phone, **Enable for OBS**
4. In OBS select the webcam with the phone name (source **Video Capture Device (V4L2)**)

**Live preview:** with a phone selected, the **In-app preview** button opens a preview window directly inside the GUI (Pillow). No more Vue.js / external browser server required.

You can enable multiple phones simultaneously (each with a different name).

After a stop/start of the service on the phone, or after a resolution/FPS/orientation change in the app, press **Enable for OBS** again.

### Main buttons

| Button | What it does | Root password? |
| --- | --- | --- |
| **Enable for OBS** | Creates/reuses the V4L2 node and starts `curl \| ffmpeg` | Only if the node needs to be created/recreated |
| **Disable** | Stops the bridge and removes the phone from the state (`devices.tsv`). The `/dev/videoN` node may stay idle | No |
| **Reload devices** | `modprobe -r` + `modprobe` (same root command → single password) with the webcams still in the state, then restarts the bridges. If the state is empty, it only tries to unload the module | Yes (once) |
| **In-app preview** | Starts (once) the Vue server and opens the browser | No |

**When to use Reload devices:** after having disabled one or more phones and you want to clean up orphaned nodes / align `v4l2loopback` with the state; or if `/dev/video*` are in an inconsistent state. If OBS holds the devices open, detach or close the V4L2 sources before pressing the button.

### What the **Enable for OBS** button does

Sequence executed by the GUI (OBS WebSocket if available, typically port `4455`, password from `~/.config/obs-studio/global.ini`):

1. **Reads the phone stream** (`/stream.info`) and waits for real YUV frames (not just the HTTP server up).
2. **Assigns / reuses** a `/dev/videoN` node: the kernel picks the index of the free minor (auto-assignment — `video_nr`/`card_label` are NOT pinned). Updates the state in `/tmp/androidcamera-v4l2/devices.tsv`.
3. **If the node doesn't exist** (or needs to be recreated): temporarily detaches OBS V4L2 sources from those devices, then runs `modprobe v4l2loopback` (may ask for root password). If the node with that name already exists, this step is skipped.
4. **Releases the device for format change** (OBS WebSocket, without deleting the source):
   - disable scene items using that `/dev/videoN`;
   - temporarily clear `device_id` on linked V4L2 sources (so OBS closes the file descriptor and `v4l2loopback` can change width/height/FPS).
5. **Sets the format** on the loopback and starts the `curl | ffmpeg` bridge → `/dev/videoN` with the app's resolution and FPS.
6. **Verifies** that the loopback actually reports the expected `WxH`.
7. **Updates OBS** (preferred: without removing the source):
   - restore `device_id` + resolution + FPS + pixel format on the existing source;
   - re-enable scene items.
8. **Fallback** (only if in-place update is not enough, e.g. OBS still shows the old size): removes that source from all scenes that contained it and **recreates it in the same scenes** with the new data (and, if possible, the same transforms).
9. Checks that `/dev/videoN` produces frames (or that the bridge is active and the device is already in use by OBS).

**What the button does NOT do:** it does not change the active OBS scene, does not create new scenes, does not invent V4L2 sources if none exists connected to that device (in that case you must add one manually in OBS; subsequent clicks will update the data). It does not reload the module just because you disabled another phone: for that there is **Reload devices**.

For the OBS step the optional `obsws-python` package is needed (`pip install --user obsws-python`). Without WebSocket / with OBS closed, the V4L2 bridge still starts; in OBS you'll need to manually update or add the source.

## Language

**IT** / **EN** buttons at the top right. Strings are in separate modules:

- `android_camera_gui/i18n/it.py`
- `android_camera_gui/i18n/en.py`
- contract: `android_camera_gui/i18n/strings_base.py`

The choice is saved in `~/.config/androidcamera-gui/config.json`.