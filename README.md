# AndroidCamera

Project to use one or more Android phones' cameras as a V4L2 webcam on Linux (OBS).

```
AndroidCamera/                 ← this folder (parent repo)
├── AndroidCamera/             # Android app
├── AndroidCameraLinuxGui/     # Python GUI to enable phones for OBS
├── AndroidCameraUsbDriver/    # userspace USB Device driver (ACUS → V4L2)
├── .env                       # ACUS_MAX_RETRIES
├── run-linux-gui.sh
└── README.md                  # this file
```

### Connection modes (Android app)

| Mode | Transport | Notes |
| --- | --- | --- |
| **USB Tethering** | YUV HTTP over RNDIS IP | Requires active USB tethering |
| **USB Device** | Binary ACUS protocol via `adb forward` | USB debug only; the `acus_driver` writes to `/dev/videoN` |
| **IP (LAN)** | YUV HTTP over Wi‑Fi | Same network as the PC |

- App: see [AndroidCamera/README.md](AndroidCamera/README.md)
- GUI: `./run-linux-gui.sh` (or see [AndroidCameraLinuxGui/README.md](AndroidCameraLinuxGui/README.md))

## System packages required (Linux PC)

The following system components are needed (Debian/Ubuntu and derivatives):

| Package | Purpose |
| --- | --- |
| `v4l2loopback-dkms` | Kernel module that creates virtual webcam nodes `/dev/videoN` |
| `v4l2loopback-utils` | Optional utilities (`v4l2loopback-ctl`, etc.) |
| `ffmpeg` | Bridge from the phone's YUV stream to the V4L2 device |
| `curl` | Downloads the HTTP stream from the phone and pipes it to `ffmpeg` |
| `adb` / platform-tools | Discovers phones in USB debug mode (the GUI also looks for `~/Android/Sdk/platform-tools/adb`) |
| `python3` + `python3-tk` | GUI in `AndroidCameraLinuxGui` |
| kernel headers for DKMS | Compile `v4l2loopback` (usually `linux-headers-$(uname -r)`) |

### Installation script

From the parent directory:

```bash
./install-deps.sh              # base packages (+ adb from apt)
```

Alternatively, manually:

```bash
sudo apt update
sudo apt install -y \
  v4l2loopback-dkms v4l2loopback-utils \
  ffmpeg curl \
  python3 python3-tk \
  adb \
  linux-headers-$(uname -r)
```

If you already have the Android SDK, the GUI also uses `~/Android/Sdk/platform-tools/adb` (see `AndroidCameraLinuxGui/run.sh`).

OBS Studio is optional but the typical target (`obs-studio` + source **Video Capture Device (V4L2)**).

The Python GUI has no mandatory pip dependencies (only standard library + `tkinter`). To automatically update resolution/FPS on existing OBS V4L2 sources: `pip install --user obsws-python` (OBS with WebSocket enabled).

## Kernel module `v4l2loopback`

**Not** loaded automatically at every PC boot. The `v4l2loopback-dkms` package installs/compiles it; loading is handled by `run.sh` (see below) at **every GUI startup**, not by a systemd service.

At **computer reboot** the module is unloaded along with the rest of the kernel: the `/dev/videoN` created in the previous session disappear, and also `/tmp/androidcamera-v4l2/` (state + bridge PIDs) is cleared. There is no daemon that automatically "restarts" the Android webcams: after boot just re-launch the GUI (`./run-linux-gui.sh`).

### Loading the slots from `run.sh`

`run-linux-gui.sh` (which calls `AndroidCameraLinuxGui/run.sh`) loads `v4l2loopback` with at least `V4L2_SLOTS` slots (read from the `.env` file) **before** starting the Python GUI:

- if the module already has ≥ N of **our** slots (driver `v4l2loopback` in `/sys/class/video4linux`; real webcams don't count) → does nothing (immediate startup, no password);
- if not loaded → `modprobe v4l2loopback devices=N`;
- if it has fewer than N → unload and reload with N slots;
- if unloading fails (OBS/VLC holds a `/dev/videoN` open) → **blocks** the GUI with a clear error.

`run.sh` uses **auto-assignment** (no `video_nr`/`card_label`):

```bash
sudo modprobe v4l2loopback devices=N exclusive_caps=0 max_buffers=8
```

Important parameters:

- `devices` – how many virtual nodes to create (=`V4L2_SLOTS`); the `/dev/videoN` indices are chosen by the kernel among free minors
- `exclusive_caps=0`, `max_buffers=8` – settings tested with the ACUS driver and the GUI

**Why we don't pin `video_nr`/`card_label`.** On the dkms in use the `video_nr` array has **fixed size (8 entries)**: passing a list of N>8 elements fails `modprobe` with `EINVAL` (verified with `V4L2_SLOTS=10`). With auto-assignment the module accepts N>8. The downside is we don't control which `/dev/videoN` get assigned (may include `/dev/video0`): this is not a problem, because the GUI reallocates `/dev/videoN` per-phone by reading `/sys/class/video4linux` and doing `mknod`, and reapplies `card_label` at runtime via `v4l2-ctl`. For this reason `V4L2_SLOTS` should be kept ≤ 8 if the module exposes only 8 entries in `parameters/video_nr` (higher values will fail).

To get the required root privileges for `modprobe`, `run.sh` requests the sudo ticket in advance (`sudo -v`): if no valid ticket exists, it asks for the password **once** at the beginning, then reuses the ticket for subsequent `modprobe` calls. Alternatively, you can launch `sudo ./run-linux-gui.sh`.

### How to start usually

1. Connect the phone to the PC with a USB cable and **manually enable USB Tethering** on the phone  
   (Settings → Network / Connections → Hotspot & tethering → **USB Tethering**).  
   This is required for cable use (as an alternative to Wi‑Fi / IP LAN mode).
2. In the Android app start the webcam service in **USB Tethering** mode.
3. On the PC: `./run-linux-gui.sh` (loads the V4L2 slots, then opens the GUI).  
   In the GUI click **Enable for OBS** to host the phone as a `/dev/videoN`.  
   Full button sequence:  
   [AndroidCameraLinuxGui/README.md — What the "Enable for OBS" button does](AndroidCameraLinuxGui/README.md#what-the-enable-for-obs-button-does).

**Disable** stops only the bridge and updates the state: it does not unload/reload the module. To reconstruct nodes (e.g. after disabling some phones) use **Reload devices** in the GUI — see [AndroidCameraLinuxGui/README.md — Main buttons](AndroidCameraLinuxGui/README.md#main-buttons).

Verify the module is loaded:

```bash
lsmod | grep v4l2loopback
v4l2-ctl --list-devices   # if v4l2-utils is installed
cat /sys/module/v4l2loopback/parameters/devices   # effective slots
```

Unload the module (only if no program is using it, e.g. OBS closed):

```bash
sudo modprobe -r v4l2loopback
```

### Changing the number of slots

Edit `V4L2_SLOTS` in the `.env` file (integer >= 1, default 10, recommended >= 10), then:

1. **close OBS/VLC** and any other programs using a webcam (otherwise `run.sh` cannot unload `v4l2loopback` to recreate the slots);
2. re-launch `./run-linux-gui.sh`.

No systemd service or computer reboot is needed: the slots are recreated at every GUI startup by `run.sh`.

> ⚠️ Do not manually create `/etc/modules-load.d/v4l2loopback.conf`: that file
> would cause a "naked" `v4l2loopback` load at boot with **default** parameters,
> which would then prevent `run.sh` from unloading the module to recreate the
> slots with the requested number.

## Runtime state on PC: `/tmp/androidcamera-v4l2/`

When you enable a phone (GUI **Enable for OBS**), this temporary folder is used on the computer — **not** `/etc/`.

| Path | Role |
| --- | --- |
| `/tmp/androidcamera-v4l2/` | V4L2 bridge state folder |
| `/tmp/androidcamera-v4l2/devices.tsv` | List of enabled phones (name → `/dev/videoN`, stream URL, resolution, FPS, …) |
| `/tmp/androidcamera-v4l2/<name>.pid` | PID of the bridge process (`curl` \| `ffmpeg`) |
| `/tmp/androidcamera-v4l2/<name>.log` | Bridge log |

The virtual webcams appear as kernel nodes:

- `/dev/videoN` (e.g. `/dev/video2`, `/dev/video11`)
- Name in OBS = phone name set in the app (`card_label` of `v4l2loopback`)

### Format of `devices.tsv`

One line per phone, fields separated by tabs:

```text
label  video_nr  port  stream_url  in_w  in_h  out_w  out_h  fps  rotation
```

Example:

```text
android1	11	8080	http://192.168.95.71:8080/stream.yuv	640	480	640	480	25	0
```

### How `N` in `/dev/videoN` is chosen

1. If a loopback with the same name already exists → reuse that number  
2. Otherwise, if the name is already in `devices.tsv` → reuse the saved `video_nr`  
3. Otherwise → first free between **0 and 255** that is not already present in `/sys/class/video4linux` nor in the state (so it doesn't collide with the PC webcam, etc.)

The folder is under `/tmp`: it's cleared at PC reboot; in that case just re-enable the phones from the GUI.