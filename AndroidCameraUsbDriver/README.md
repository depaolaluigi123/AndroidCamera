# AndroidCameraUsbDriver

Userspace driver that turns a phone in **USB Device** mode into a Linux V4L2 webcam.

```
Phone (ACUS binary TCP on 127.0.0.1:PORT)
        │  adb forward tcp:PORT tcp:PORT
        ▼
acus_driver  ──write──►  /dev/videoN (v4l2loopback)
        │
        ▼
OBS → Video Capture Device (V4L2)
```

No HTTP is used. Frames travel as the binary **ACUS** protocol over USB debugging
(`adb forward` maps the host port to the phone server).

## Build

```bash
make
# → bin/acus_driver
```

## Probe / stream

```bash
# Handshake only (JSON on stdout)
./bin/acus_driver --probe --serial DEVICE_SERIAL --port 8080

# Feed a loopback node
./bin/acus_driver --serial DEVICE_SERIAL --port 8080 --device /dev/video2
```

Normally you do not run this by hand: `AndroidCameraLinuxGui` starts it when you click **Enable for OBS** for a phone discovered in USB Device mode.
