# AndroidCameraUsbDriver

Driver userspace che trasforma un telefono in **modalità USB Device** in una webcam V4L2 su Linux.

```
Telefono (protocollo binario ACUS su 127.0.0.1:PORT)
        │  adb forward tcp:PORT tcp:PORT
        ▼
acus_driver  ──write──►  /dev/videoN (v4l2loopback)
        │
        ▼
OBS → Video Capture Device (V4L2)
```

Non viene utilizzato HTTP. I frame viaggiano come protocollo binario **ACUS** attraverso il debug USB
(`adb forward` mappa la porta host alla porta del server del telefono).

## Build

```bash
make
# → bin/acus_driver
```

## Probe / stream

```bash
# Handshake only (JSON su stdout)
./bin/acus_driver --probe --serial DEVICE_SERIAL --port 8080

# Alimenta un nodo loopback
./bin/acus_driver --serial DEVICE_SERIAL --port 8080 --device /dev/video2
```

Normalmente non lo si esegue a mano: `AndroidCameraLinuxGui` lo avvia quando si clicca **Abilita per OBS** per un telefono scoperto in modalità USB Device.