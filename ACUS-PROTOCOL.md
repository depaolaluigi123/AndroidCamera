# ACUS Protocol Specification

## Table of Contents

1. [What is ACUS?](#what-is-acus)
2. [Architecture and Connection Topology](#architecture-and-connection-topology)
3. [Protocol Format](#protocol-format)
   - [Connection Establishment and Handshake](#connection-establishment-and-handshake)
   - [Per-Frame Data](#per-frame-data)
4. [Android Implementation](#android-implementation)
5. [Linux Driver Implementation](#linux-driver-implementation)
6. [Python ACUS Support Module](#python-acus-support-module)
7. [ACUS Preview Proxy (HTTP/WebSocket Bridge)](#acus-preview-proxy-httpwebsocket-bridge)
8. [Discovery Integration](#discovery-integration)
9. [Key Design Decisions](#key-design-decisions)
10. [Build Requirements](#build-requirements)
11. [Protocol Extensions](#protocol-extensions)
    - [JPEG Compression Extension](#jpeg-compression-extension)
    - [Frame Batching Extension](#frame-batching-extension)

---

## What is ACUS?

**ACUS** stands for **Android Camera USB**. It is a lightweight, low-latency binary streaming protocol designed to transmit camera frames from an Android phone to a Linux PC over a USB debugging connection, without requiring HTTP, WiFi, or any network connectivity.

The primary use case is turning a phone in USB Device mode into a Linux V4L2 (Video4Linux2) webcam, consumable by applications like OBS. The phone runs an ACUS server that streams raw I420 (YUV420) frames; a Linux driver receives them via `adb forward` and writes them to a V4L2 loopback device node.

---

## Architecture and Connection Topology

```
┌─────────────────────────┐          adb forward           ┌──────────────────────┐
│  Android Phone          │  USB debug  tcp:PORT  tcp:PORT │  Linux PC            │
│                         │ ◄─────────────────────────────────►                 │
│  ┌───────────────────┐  │                                   │  ┌─────────────┐  │
│  │ UsbDeviceStream   │  │                                   │  │ acus_driver │  │
│  │ Server (Kotlin)   │  │         ACUS binary stream        │  │ (C binary)   │  │
│  │ 127.0.0.1:PORT    │─────── localhost TCP (no network) ──► │             │  │
│  └───────────────────┘  │                                   │  └──────┬──────┘  │
│         │               │                                   │         │          │
│         │ YUV I420      │                                   │  V4L2 loopback    │
│         ▼               │                                   │  /dev/videoN      │
│  ┌───────────────────┐  │                                   │         ▼          │
│  │  FrameBroker      │  │                                   │  OBS / ffmpeg      │
│  │  (camera frames)  │  │                                   │  Video Capture     │
│  └───────────────────┘  │                                   └────────────────────┘
└─────────────────────────┘
```

**Transport chain:**
1. Phone: `FrameBroker` captures camera frames in I420 format.
2. Phone: `UsbDeviceStreamServer` binds to `127.0.0.1:PORT` (localhost only) and streams raw ACUS frames over TCP.
3. USB: `adb forward tcp:PORT tcp:PORT` tunnels the TCP connection over the USB debug bridge.
4. Linux: `acus_driver` connects to `localhost:PORT`, receives ACUS frames, and writes I420 data to a V4L2 loopback device (`/dev/videoN`).
5. Linux: Applications (OBS, ffmpeg, etc.) consume the device as a standard V4L2 webcam.

---

## Protocol Format

All multi-byte integers are in **big-endian** (network byte order). The protocol operates over a raw TCP socket.

### Connection Establishment and Handshake

The handshake is sent **once** by the Android server at the start of each connection. It is sent **after** the first camera frame has been captured, so that the `width`, `height`, and `fps` fields reflect the actual live camera parameters rather than placeholder values.

```
┌────────────┬─────────────┬───────────────────────────────┐
│ Offset     │ Size        │ Field                          │
├────────────┼─────────────┼───────────────────────────────┤
│ 0          │ 4 bytes     │ Magic bytes: "ACUS" (0x41 0x43 0x55 0x53) │
│ 4          │ 1 byte      │ Protocol version = 1           │
│ 5          │ 1 byte      │ Flags (bitmask):               │
│            │             │   bit 0: JPEG support (1 = supported) │
│            │             │   bit 1–7: Reserved            │
│ 6–7        │ 2 bytes     │ Frame width in pixels (u16 BE) │
│ 8–9        │ 2 bytes     │ Frame height in pixels (u16 BE)│
│ 10–11      │ 2 bytes     │ Target FPS (u16 BE)            │
│ 12–13      │ 2 bytes     │ Device rotation (u16 BE, degrees, 0/90/180/270) │
│ 14         │ 1 byte      │ Device name length in bytes (u8)│
│ 15 … 15+name_len-1 │ name_len bytes │ Device name (UTF-8)          │
│ 15+name_len│ 1 byte      │ JPEG quality (u8, 10–100, only if bit 0 of flags is 1) │
└────────────┴─────────────┴───────────────────────────────┘
```

**Header size:** 15 or 16 bytes + `name_len` (the JPEG quality byte is present
**iff** the JPEG flag bit is set — it is not a fixed pair).

The server always answers Protocol v1; the flags reflect what the user actually
selected in the Android app:

| User selection on the Android app | Flags byte   | Extra bytes after name           |
|----------------------------------|--------------|----------------------------------|
| YUV                              | `0x00`       | none                             |
| JPEG                             | `0x01` (JPEG) | `jpeg_quality` (1 byte)          |

Because the extra bytes are presence-driven, the client (C driver, Python
`acus_driver.probe`, preview-proxy `_read_handshake`) must read exactly the
number of bytes the flags claim — never read a fixed 1 byte unconditionally.

---

### Per-Frame Data

Each incoming frame from the camera is wrapped in an ACUS frame envelope:

```
┌────────────┬─────────────┬────────────────────────────────┐
│ Offset     │ Size        │ Field                          │
├────────────┼─────────────┼────────────────────────────────┤
│ 0          │ 4 bytes     │ Frame magic: "FRME" (0x46 0x52 0x4D 0x45) │
│ 4–7        │ 4 bytes     │ Sequence number (u32 BE, starts at 1) │
│ 8–11       │ 4 bytes     │ Frame data size in bytes (u32 BE)     │
│ 12 … 12+size-1 │ size bytes │ Frame data (format per handshake) │
└────────────┴─────────────┴────────────────────────────────┘
```

**Frame data format** (per handshake):
- **Version 1 / I420 (YUV420) planar format** for a `width × height` frame:
  - `Y` plane: `width × height` bytes (full resolution)
  - `U` plane: `(width/2) × (height/2)` bytes (quarter resolution)
  - `V` plane: `(width/2) × (height/2)` bytes (quarter resolution)
  - **Total size:** `width × height × 3 / 2` bytes.

- **Version 1 / JPEG compressed format** (when flags bit 0 is set):
  - Single JPEG compressed frame (size varies per frame)
  - Sequence number still increments per frame

---

## Android Implementation

**Source file:** `AndroidCamera/app/src/main/java/com/androidcamera/webcam/streaming/UsbDeviceStreamServer.kt`

The server is implemented in Kotlin as a coroutine-based TCP server:

```kotlin
const val PROTOCOL_VERSION: Byte = 1             // ACUS Protocol v1
const val FLAG_JPEG: Int = 0x01                  // bit 0: JPEG transport
val MAGIC_ACUS: ByteArray = byteArrayOf(0x41, 0x43, 0x55, 0x53) // "ACUS"
val MAGIC_FRME: ByteArray = byteArrayOf(0x46, 0x52, 0x4D, 0x45) // "FRME"
```

**Key implementation details:**

- **Bind address:** `127.0.0.1` only — the server is never bound to a network-accessible IP. It is exclusively reachable through `adb forward`.
- **TCP_NODELAY:** Enabled on the socket to disable Nagle's algorithm, minimizing latency.
- **Deferred handshake:** The server waits for the first frame from the `FrameBroker` (in the user-selected format) before sending the handshake. This ensures the handshake reports the actual negotiated camera resolution, not a default placeholder.
- **Format-driven flags:** The flags byte is built from `StreamConfig.streamFormat` (JPEG → `FLAG_JPEG`); it is **not** a hardcoded value. A YUV user gets `flags=0x00` and no extras — the GUI then shows `format=YUV` and `JPEG q=—`.
- **Concurrent clients:** Supported via a `CopyOnWriteArrayList`. All connected clients receive every frame.
- **Framing:** After the handshake, the server writes each frame as: 12-byte header + raw frame data. The frame payload is whatever the `FrameBroker` published (I420 for YUV users, JPEG bytes for JPEG users). The client is responsible for reading exactly the announced payload size before reading the next header.
- **JPEG compression:** When `StreamConfig.streamFormat = JPEG`, the `CameraStreamController` encodes each I420 frame to JPEG at the configured quality **before** publishing to the broker; the server forwards those bytes verbatim. No re-encoding happens at the streaming layer.

---

## Linux Driver Implementation

**Source file:** `AndroidCameraUsbDriver/src/acus_driver.c`

The driver is a single C file that implements two operational modes:

### Probe Mode (`--probe`)

Performs only the handshake exchange, prints stream metadata as JSON to stdout, then exits. Used by the discovery system to detect whether a connected phone speaks ACUS.

```bash
./acus_driver --probe --device /dev/videoX --serial 0x1234 --port 2743
```

> **Keyword reference:**
> - `--probe` — Attiva la modalità "probe": esegue solo l'handshake con il dispositivo ACUS, stampa i metadati dello stream come JSON su stdout e termina. Usata dal sistema di discovery per verificare se un telefono connesso supporta ACUS.
> - `--device /dev/videoX` — Specifica il percorso del dispositivo V4L2 loopback su cui scrivere i frame (necessario anche in modalità probe per stabilire il contesto del dispositivo).
> - `--serial 0x1234` — Identificatore seriale del dispositivo ADB (ottenibile con `adb devices -l`). Permette al driver di comunicare con lo specifico telefono quando più dispositivi sono connessi.
> - `--port 2743` — Porta TCP su cui il server ACUS (lato Android) è in ascolto. Il valore predefinito e consigliato è `2743`.

Output example (YUV — the user picked raw I420):
```json
{"deviceName":"Pixel 7","width":1920,"height":1080,"outputWidth":1920,"outputHeight":1080,"fps":30,"rotation":0,"pixelFormat":"yuv420p","format":"YUV","ready":true,"transport":"acus","protocol_version":1,"jpeg_supported":false,"jpeg_quality":0}
```

Output example (JPEG q75):
```json
{"deviceName":"Pixel 7","width":1920,"height":1080,"outputWidth":1920,"outputHeight":1080,"fps":30,"rotation":0,"pixelFormat":"mjpeg","format":"JPEG","ready":true,"transport":"acus","protocol_version":1,"jpeg_supported":true,"jpeg_quality":75}
```

The Python discovery layer accepts both snake_case (`jpeg_quality`, as the C
probe emits) and camelCase (`jpegQuality`, as the HTTP `/stream.info` endpoint
emits) spellings.

### Device Mode (`--device /dev/videoN`)

Full streaming mode:

1. Sets up `adb forward` to tunnel `tcp:PORT` on localhost to the device's `localhost:PORT`.
2. Opens a TCP socket to `127.0.0.1:PORT` with `TCP_NODELAY`.
3. Reads and validates the ACUS handshake: checks for magic "ACUS" and accepts **only version 1**. Anything else is refused (returns an error / `probe` JSON absent). The flags-driven trailing bytes are read exactly as the flags claim (none or 1 byte), never a fixed pair.
4. Opens the V4L2 loopback device and ioctls `VIDIOC_S_FMT` to set the capture format to `V4L2_PIX_FMT_YUV420` (raw YUV users) or `V4L2_PIX_FMT_MJPEG` with a `V4L2_PIX_FMT_JPEG` fallback (JPEG users) at the announced resolution.
5. Reads frame envelopes in a loop: reads 12-byte header, validates "FRME" magic, reads the announced number of bytes, writes to the V4L2 device.
   - For JPEG frames, either passes through to V4L2 (if format is JPEG) or decompresses to YUV420.
6. Installs `SIGINT` and `SIGTERM` handlers for clean shutdown.

```bash
./acus_driver --device /dev/videoX --serial 0x1234 --port 2743
```

> **Nota:** La modalità device è la modalità completa di streaming. Il driver configura automaticamente il forward ADB, si connette al server ACUS e scrive i frame I420 sul dispositivo V4L2 specificato. Per i dettagli sulle singole keyword, vedere la sezione [Probe Mode](#probe-mode---probe) sopra.

---

## Python ACUS Support Module

**Source file:** `AndroidCameraLinuxGui/android_camera_gui/acus_driver.py`

A Python wrapper providing:

- **`probe(adb_serial, port)`** — Opens an ACUS connection, performs the handshake, returns a dict with `width`, `height`, `fps`, `rotation`, `name`, `protocol_version`, `jpeg_supported`, `jpeg_quality`. Used during device discovery.
- **`driver_path()`** — Locates the compiled `acus_driver` C binary.
- **`ensure_built()`** — Builds the C binary via `make` if not already built.
- **`parse_acus_url(url)`** — Parses URLs of the form `acus://SERIAL@HOST:PORT` (used internally by the GUI to identify ACUS transport).

---

## ACUS Preview Proxy (HTTP/WebSocket Bridge)

**Source file:** `AndroidCameraLinuxGui/android_camera_gui/acus_preview_proxy.py`

A local HTTP/WebSocket server that bridges the ACUS binary stream to browser-based preview clients. This avoids the need for a native app to view the camera feed.

**HTTP endpoints** (served on `127.0.0.1` only):

| Endpoint          | Description                                              |
|--------------------|----------------------------------------------------------|
| `GET /stream.info` | Returns JSON metadata: `{width, height, fps, name, protocol_version, jpeg_supported}` |
| `GET /stream.yuv`  | Returns raw I420 bytes for the current frame (one shot)  |
| `GET /stream.mjpeg`| Returns MJPEG stream for browser preview                 |
| `GET /acus/ws`     | WebSocket endpoint for live streaming preview            |

**Architecture:**

- An `_AcusHub` background thread manages the underlying ACUS TCP connection with automatic retry logic and timeout handling.
- Multiple proxy instances can coexist, keyed by `(adb_serial, phone_port)`.
- WebSocket clients receive live frame updates pushed from the hub, enabling a real-time browser preview with minimal latency.

---

## Discovery Integration

**Source file:** `AndroidCameraLinuxGui/android_camera_gui/discovery.py`

The device discovery system probes phones in a specific order, trying faster/more reliable transports first:

1. **ACUS (USB Device mode)** — `adb forward` + ACUS. Tried first because it works without WiFi and with USB debugging alone. If the `acus_driver --probe` call succeeds, the device is marked with transport label `"USB Device (ACUS)"`.
2. **USB Tethering + HTTP** — If ACUS fails, tries the phone's HTTP server endpoint via an `adb forward` HTTP tunnel, with a fallback to direct HTTP over USB tethering.

This priority ordering means a phone connected via USB will always use the ACUS transport if available, falling back to HTTP only if ACUS is not running on the phone.

---

## Key Design Decisions

### Why a custom binary protocol instead of HTTP/RTSP?

ACUS prioritizes **low latency and minimal overhead**. HTTP adds significant per-frame overhead (headers, chunked transfer encoding) and typically requires a more complex server stack. RTSP/RTP adds network protocol complexity and is designed for multi-hop networks, not a direct localhost-to-USB tunnel. ACUS is optimized for the direct, low-latency path between the camera broker and the USB tunnel.

### Why I420 (YUV420) and not a compressed format like JPEG or H.264?

The phone is already capturing in YUV format from the camera API. Transmitting raw YUV avoids the CPU cost of encoding and the latency of compression. The `adb forward` tunnel has enough bandwidth for 1080p@30fps raw YUV (`1920×1080×1.5 = ~3MB per frame ≈ 720 Mbps`), which is within USB 2.0 High Speed capability for short bursts. For higher resolutions or frame rates over USB 2.0, the driver and broker support adaptive frame dropping.

**Protocol v1** also optionally supports JPEG compression, useful for WiFi/LAN streaming where bandwidth is more constrained. This allows trading quality for framerate based on network conditions.

### Why localhost-only binding on Android?

Binding the ACUS server to `127.0.0.1` means it is **completely inaccessible over the network**. The only way to reach it is through the `adb forward` tunnel, which requires USB debugging authorization. This provides security through network isolation: even if the phone's IP address is known, the ACUS server cannot be contacted without physical USB access and adb authorization.

### Why V4L2 loopback?

Linux video applications (OBS, ffmpeg, Cheese, etc.) are built around the V4L2 API. Providing a `/dev/videoN` loopback device makes the Android camera appear as a standard webcam with zero integration effort — no custom SDK or plugin needed on the consumer side.

### Why big-endian byte order?

ACUS was designed to mirror network protocol conventions (big-endian / network byte order). This makes the protocol wire-format compatible with how most network tools and libraries handle multi-byte integers, simplifying debugging with tools like Wireshark or `nc`.

### Why adb forward instead of raw USB gadget?

Using `adb forward` leverages Android's existing USB debugging infrastructure, which is:
- Already authorized when USB debugging is accepted.
- Stable across Android versions (no per-device USB gadget driver needed).
- Independent of USB tethering being enabled.

The tradeoff is that `adb` adds a small per-frame overhead, but this is negligible.

---

## Protocol Extensions

### JPEG Compression Extension (Flags bit 0)

When the JPEG flag is set in the handshake:
- Frames are transmitted as JPEG compressed images instead of raw I420.
- The handshake includes the JPEG quality setting (10-100).
- This reduces bandwidth significantly at the cost of CPU for encoding and some quality loss.
- Useful for WiFi/LAN streaming where bandwidth is limited.

---

## Build Requirements

### Android side
- Android camera2 API (API 21+)
- No additional native dependencies

### Linux side
- A C compiler (gcc or clang)
- `make`
- V4L2 kernel headers (`linux/videodev2.h`)
- `adb` in PATH
- Optional: `v4l2loopback` kernel module (`modprobe v4l2loopback`)

### Python GUI side
- Python 3
- `pyzmq` (for WebSocket proxy)
- Android `adb` Python bindings (via `adb_shell` or similar)