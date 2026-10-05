# Linux commands used by AndroidCamera

## Managing v4l2loopback (virtual slots for USB Device webcam)

### Installing dependencies

`install-deps.sh` installs the system packages needed for AndroidCamera
(`v4l2loopback-dkms`, `v4l2loopback-utils`, `ffmpeg`, `curl`, `python3`,
`python3-tk`, `adb`, `linux-headers-$(uname -r)`).

**Example:**
```bash
sudo ./install-deps.sh
```

This script installs **only** the packages: it does NOT load v4l2loopback and does NOT
configure any automatic loading at OS boot. The module is loaded by `run.sh`
at GUI startup (see below).

### Loading slots at GUI startup

`run-linux-gui.sh` (which calls `AndroidCameraLinuxGui/run.sh`) loads the
`v4l2loopback` module with **at least** `V4L2_SLOTS` slots (read from the
`.env` file in the repo root) **before** starting the Python GUI.

Loading: `modprobe v4l2loopback devices=N exclusive_caps=0 max_buffers=8`
with `N = V4L2_SLOTS`. The `/dev/videoN` indices are chosen by the kernel
(auto-assignment): they are not pinned by `run.sh`, because on some dkms
the `video_nr` array has a fixed size (here 8) and passing a list longer
than 8 fails `modprobe` with `EINVAL`. For the same reason
`V4L2_SLOTS` has no forced upper bound here; if you exceed the module's
limit, `run.sh` blocks with a clear error ("loaded only K slot(s) of N
requested").

Behavior of `run.sh`:

- if `v4l2loopback` is already loaded with at least `V4L2_SLOTS` of our slots →
  does nothing (no password prompt, immediate startup);
- if the module is not loaded → runs `modprobe v4l2loopback devices=N`;
- if the module is loaded with a number of slots **less** than N →
  unloads (`modprobe -r`) and reloads with N slots;
- if unloading fails (a consumer — typically OBS, VLC, or a browser —
  holds a `/dev/videoN` open) → **blocks** execution with an error,
  because without the needed slots the GUI cannot function.

The count of our slots is done by reading
`/sys/class/video4linux/videoN/device/driver`: it counts only the nodes whose
driver is `v4l2loopback`, so real webcams (`uvcvideo` & similar) are not
erroneously included.

To get the root privileges needed for `modprobe`, `run.sh` requests the sudo
ticket in advance (`sudo -v`): if no valid sudo ticket exists, it asks for
the password once at the beginning, then reuses the ticket for subsequent
`modprobe` calls. Alternatively, you can run `sudo ./run-linux-gui.sh`.

**Example:**
```bash
./run-linux-gui.sh
# (or, to have privileges upfront:)
sudo ./run-linux-gui.sh
```

### Changing the number of slots

The number of `v4l2loopback` slots is defined in `.env` with the variable
`V4L2_SLOTS` (integer >= 1; default: 10; recommended >= 10 to have room for
multiple phones + resolution changes). Values higher than the installed
module's limit (on some dkms the `video_nr` array is fixed at 8) can
fail `modprobe`: keep `V4L2_SLOTS` <= 8 if your module exposes only 8 entries
in `parameters/video_nr`. After changing the value:

1. close OBS/VLC and any other programs using a webcam
   (otherwise `run.sh` won't be able to unload `v4l2loopback`);
2. re-launch `./run-linux-gui.sh`.

It is not necessary to reboot the computer: the slots are recreated at every
GUI startup by `run.sh`.

### Support commands

```bash
# Check available video devices
ls -l /dev/video*

# List v4l2loopback devices
v4l2-ctl --list-devices

# Count ACTIVE v4l2loopback slots (reliable method, independent of
# dkms: counts videoN nodes whose driver is v4l2loopback)
for e in /sys/class/video4linux/video*; do
  drv=$(readlink -f "$e/device/driver" 2>/dev/null); drv=${drv##*/}
  [[ "$drv" == "v4l2loopback" ]] && echo "$e -> v4l2loopback"
done

# On some dkms the devices parameter is also available (not always present)
cat /sys/module/v4l2loopback/parameters/devices 2>/dev/null
```

### Notes

- The `v4l2loopback` slots created by `run.sh` are "kernel-owned" and remain
  free until they are assigned to a phone.
- Multiple phones can be enabled simultaneously without unloading/
  reloading the module, because the GUI (never) unloads `v4l2loopback` at runtime.
- At **computer reboot** the module is unloaded together with the rest of
  the kernel, and the `/dev/videoN` created in the previous session disappear
  (along with the state in `/tmp/androidcamera-v4l2/`): no daemon restarts
  them on its own, just re-launch `./run-linux-gui.sh`.