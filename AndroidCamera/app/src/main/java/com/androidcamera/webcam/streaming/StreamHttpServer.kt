package com.androidcamera.webcam.streaming

import android.util.Log
import com.androidcamera.webcam.camera.JpegEncoder
import com.androidcamera.webcam.model.StreamConfig
import com.androidcamera.webcam.model.StreamFormat
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HTTP server for raw I420 (YUV) or JPEG streams.
 */
class StreamHttpServer(
    private val port: Int,
    private val frameBroker: FrameBroker,
    private val streamConfigRef: () -> StreamConfig
) {
    private val streamConfig: StreamConfig
        get() = streamConfigRef()
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val streamExecutor = Executors.newCachedThreadPool()

    /** Rolling window of the last N frame timestamps to compute actual FPS. */
    private val frameTimestamps = java.util.ArrayDeque<Long>(64)
    private val fpsLock = Any()

    /** Record a frame arrival timestamp and return the current measured FPS. */
    private fun recordFrame(): Int {
        val now = System.nanoTime()
        synchronized(fpsLock) {
            frameTimestamps.addLast(now)
            // Keep only the last 64 timestamps (about 1 second at 60fps).
            if (frameTimestamps.size > 64) frameTimestamps.removeFirst()
            if (frameTimestamps.size >= 2) {
                val spanNs = frameTimestamps.last() - frameTimestamps.first()
                if (spanNs > 0) {
                    return ((frameTimestamps.size - 1) * 1_000_000_000L / spanNs).toInt()
                }
            }
        }
        return streamConfig.fps.fps
    }

    /** Clear timestamp history when FPS configuration changes to avoid stale measurements. */
    fun clearFpsHistory() {
        synchronized(fpsLock) {
            frameTimestamps.clear()
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverSocket = ServerSocket(port).also { it.reuseAddress = true }
        acceptExecutor.execute { acceptLoop() }
        Log.i(TAG, "Stream server on :$port device=${streamConfig.deviceName} format=${streamConfig.streamFormat}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    fun updateConfig(newConfig: StreamConfig) {
        // Clear FPS history when the target FPS changes to avoid stale measurements
        // that mix frames from different FPS configurations.
        clearFpsHistory()
    }

    private fun acceptLoop() {
        val server = serverSocket ?: return
        while (running.get()) {
            try {
                val socket = server.accept()
                clients.add(socket)
                streamExecutor.execute { handleClient(socket) }
            } catch (_: SocketException) {
                break
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Accept error: ${e.message}")
            }
        }
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 0
            socket.tcpNoDelay = true
            val input = socket.getInputStream().bufferedReader()
            val requestLine = input.readLine() ?: return
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }

            val target = requestLine.split(" ").getOrNull(1)?.substringBefore('?') ?: "/"
            val output = BufferedOutputStream(socket.getOutputStream())

            when {
                target == "/" || target.startsWith("/index") -> {
                    writeTextResponse(output, 200, "text/html; charset=utf-8", indexHtml())
                }
                target == "/stream.info" || target == "/info" -> {
                    writeTextResponse(output, 200, "application/json", infoJson())
                }
                target == "/stream.yuv" || target == "/stream" || target == "/cam.yuv" ||
                    target == "/stream.video" || target == "/video" || target == "/cam.video" -> {
                    // /stream.yuv and /stream.video are the raw-I420 endpoint.
                    // Always serve raw I420 regardless of the negotiated
                    // phone-side format: when the phone publishes JPEG-compressed
                    // frames we transparently transcode them back to I420 here
                    // so consumers (custom pipelines, …) get the format they
                    // asked for. The previous behaviour served JPEG bytes on
                    // /stream.yuv when the phone had switched to JPEG, which
                    // made YUV-aware consumers (ffmpeg, the V4L2 loopback
                    // pipeline) misinterpret JPEG headers as I420 chroma planes.
                    //
                    // /stream.video is the canonical alias requested by the
                    // Python GUI when USB Tethering / IP mode is selected; it
                    // points at the same I420 bytes as /stream.yuv but with a
                    // name that does not depend on the on-the-wire format.
                    writeYuvStream(output, StreamFormat.YUV, StreamFormat.JPEG)
                }
                target == "/stream.mjpeg" || target == "/mjpeg" -> {
                    writeMjpegStream(output, streamConfig.streamFormat)
                }
                else -> writeTextResponse(
                    output,
                    404,
                    "text/plain",
                    "Not found. Use /stream.video (YUV) or /stream.mjpeg (MJPEG) or /stream.info\n"
                )
            }
        } catch (_: IOException) {
            // disconnected
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun infoJson(): String {
        // The broker holds the *latest encoded* frame in whatever format the
        // user picked (YUV or JPEG). We must accept BOTH for `ready` — older
        // builds filtered for YUV-only and reported ready=false to clients
        // whenever JPEG was negotiated, which hid the phone entirely from
        // the GUI even though frames were flowing fine.
        val frame = frameBroker.latest()
        val outW = streamConfig.outputWidth
        val outH = streamConfig.outputHeight
        val ready = frame != null
        val actualFps = if (ready) recordFrame() else streamConfig.fps.fps
        // JPEG encoder backend (HW MediaCodec vs SW YuvImage) — surfaced so
        // the GUI can show whether the phone is using hardware-accelerated
        // compression. Older builds reported jpegQuality only.
        val jpegBackend = frame?.encoderBackend?.name
        return JSONObject()
            .put("format", streamConfig.streamFormat.name)
            .put("ready", ready)
            // width/height of the negotiated stream payload:
            //   - YUV : bytes per I420 frame (w*h*3/2)
            //   - JPEG: bytes per JPEG-compressed frame (varies)
            .put("width", frame?.width ?: outW)
            .put("height", frame?.height ?: outH)
            .put("outputWidth", outW)
            .put("outputHeight", outH)
            .put("fps", actualFps)
            .put("configuredFps", streamConfig.fps.fps)
            .put("jpegQuality", streamConfig.jpegQuality.quality)
            // Rotation belongs to the upstream camera buffer; if we don't have
            // a frame yet, fall back to the configured orientation hint.
            .put("rotation", frame?.rotationDegrees ?: 0)
            .put("deviceName", streamConfig.deviceName)
            .put("path", if (streamConfig.streamFormat == StreamFormat.YUV) "/stream.video" else "/stream.mjpeg")
            .put("pixelFormat", if (streamConfig.streamFormat == StreamFormat.YUV) "yuv420p" else "jpeg")
            .put("jpegEncoder", jpegBackend ?: JSONObject.NULL)
            .put("jpegHardwareAvailable", JpegEncoder.isHardwareAvailable())
            .toString()
    }

    private fun indexHtml(): String {
        val format = streamConfig.streamFormat
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><title>${streamConfig.deviceName}</title>
            <style>body{margin:0;background:#111;color:#ddd;font-family:sans-serif;display:flex;
            justify-content:center;align-items:center;height:100vh;padding:24px;text-align:center}
            code{color:#7dffa5}</style></head>
            <body><div>
            <h2>${streamConfig.deviceName}</h2>
            <p>Format: <code>$format</code> ${if (format == StreamFormat.JPEG) "Quality: ${streamConfig.jpegQuality.quality}%" else ""}</p>
            <p>Endpoints:
                ${if (format == StreamFormat.YUV) "<code>/stream.video</code> (YUV420, alias of /stream.yuv)" else "<code>/stream.mjpeg</code> (MJPEG)"}
                · Info: <code>/stream.info</code>
            </p>
            </div></body></html>
        """.trimIndent()
    }

    private fun writeTextResponse(
        output: BufferedOutputStream,
        code: Int,
        contentType: String,
        body: String
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = if (code == 200) "OK" else "Error"
        val header = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.write(bytes)
        output.flush()
    }

    private fun writeYuvStream(output: BufferedOutputStream, expected: StreamFormat, allowAlso: StreamFormat? = null) {
        var first: StreamFrame? = null
        val waitStart = System.nanoTime()
        while (running.get() && first == null) {
            val frame = frameBroker.latest()
            val matches = frame != null && (
                frame.format == expected ||
                    (allowAlso != null && frame.format == allowAlso)
                )
            if (matches) first = frame
            else {
                if (System.nanoTime() - waitStart > 5_000_000_000L) {
                    writeTextResponse(output, 503, "text/plain", "No ${expected} frames yet\n")
                    return
                }
                Thread.sleep(5)
            }
        }
        val seed = first ?: return

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Connection: keep-alive\r\n")
            append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
            append("Pragma: no-cache\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("X-Width: ${seed.width}\r\n")
            append("X-Height: ${seed.height}\r\n")
            append("X-Pixel-Format: yuv420p\r\n")
            append("X-Rotation: ${seed.rotationDegrees}\r\n")
            append("X-Device-Name: ${streamConfig.deviceName}\r\n")
            // X-Fps will be updated to actual once we've seen at least 2 frames.
            .append("X-Fps: ${streamConfig.fps.fps}\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.flush()

        var lastSeq = -1L
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val frame = frameBroker.latest()
            if (frame == null || frame.sequence == lastSeq) {
                Thread.sleep(2)
                continue
            }
            if (frame.format != expected && (allowAlso == null || frame.format != allowAlso)) {
                Thread.sleep(2)
                continue
            }
            val payload: ByteArray? = if (frame.format == expected) {
                frame.payload
            } else {
                // The phone is publishing JPEG-compressed frames but the
                // consumer asked for raw I420 on /stream.yuv. Decode the
                // JPEG and re-emit the I420 plane layout so the consumer
                // gets the format they asked for. We pay a software decode
                // per frame, but only on /stream.yuv when the user picked
                // JPEG on the phone — the canonical /stream.mjpeg path
                // stays untouched.
                jpegToI420(frame.payload)
            }
            if (payload == null) {
                Thread.sleep(2)
                continue
            }
            lastSeq = frame.sequence
            recordFrame()
            output.write(payload)
            output.flush()
        }
    }

    /** Decode a JPEG byte array to I420 (YUV420 planar). Returns null on
     *  decode failure so the caller can skip the frame instead of pushing
     *  half-decoded garbage. */
    private fun jpegToI420(jpegBytes: ByteArray): ByteArray? {
        return try {
            val opts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
            }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                ?: return null
            val w = bitmap.width
            val h = bitmap.height
            val argb = IntArray(w * h)
            bitmap.getPixels(argb, 0, w, 0, 0, w, h)
            bitmap.recycle()
            val out = ByteArray(w * h * 3 / 2)
            val cw = w / 2
            val ch = h / 2
            var yp = 0
            var up = w * h
            var vp = w * h + w * h / 4
            for (j in 0 until h) {
                for (i in 0 until w) {
                    val c = argb[j * w + i]
                    val r = (c shr 16) and 0xff
                    val g = (c shr 8) and 0xff
                    val b = c and 0xff
                    // BT.601 full-range Y / U / V.
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    out[yp++] = y.coerceIn(0, 255).toByte()
                    if ((j and 1) == 0 && (i and 1) == 0) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        out[up++] = u.coerceIn(0, 255).toByte()
                        out[vp++] = v.coerceIn(0, 255).toByte()
                    }
                }
            }
            // Avoid unused-variable warnings for cw / ch.
            check(up <= w * h + cw * ch && vp <= out.size) { "chroma size mismatch" }
            out
        } catch (e: Exception) {
            Log.w(TAG, "jpegToI420 decode failed: ${e.message}")
            null
        }
    }

    private fun writeJpegStream(output: BufferedOutputStream, expected: StreamFormat) {
        // The broker already carries JPEG-encoded frames when the user selected
        // JPEG in the app (the CameraStreamController does the YUV→JPEG step
        // in-process). So unlike the YUV path we must NOT re-encode the frame
        // here — we forward the raw JPEG payload as-is.
        var first: StreamFrame? = null
        val waitStart = System.nanoTime()
        while (running.get() && first == null) {
            val frame = frameBroker.latest()
            if (frame != null && frame.format == expected) first = frame
            else {
                if (System.nanoTime() - waitStart > 5_000_000_000L) {
                    writeTextResponse(output, 503, "text/plain", "No ${expected} frames yet\n")
                    return
                }
                Thread.sleep(5)
            }
        }
        val seed = first ?: return

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Connection: keep-alive\r\n")
            append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
            append("Pragma: no-cache\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("X-Width: ${seed.width}\r\n")
            append("X-Height: ${seed.height}\r\n")
            append("X-Jpeg-Quality: ${streamConfig.jpegQuality.quality}\r\n")
            append("X-Device-Name: ${streamConfig.deviceName}\r\n")
            append("X-Fps: ${streamConfig.fps.fps}\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.flush()

        var lastSeq = -1L
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val frame = frameBroker.latest()
            if (frame == null || frame.sequence == lastSeq || frame.format != expected) {
                Thread.sleep(2)
                continue
            }

            lastSeq = frame.sequence
            if (frame.payload.isNotEmpty()) {
                recordFrame()
                output.write(frame.payload)
                output.flush()
            }
        }
    }

    private fun writeMjpegStream(output: BufferedOutputStream, expected: StreamFormat) {
        var first: StreamFrame? = null
        val waitStart = System.nanoTime()
        while (running.get() && first == null) {
            val frame = frameBroker.latest()
            if (frame != null && frame.format == expected) first = frame
            else {
                if (System.nanoTime() - waitStart > 5_000_000_000L) {
                    writeTextResponse(output, 503, "text/plain", "No frames yet\n")
                    return
                }
                Thread.sleep(5)
            }
        }
        val seed = first ?: return

        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Connection: keep-alive\r\n")
            append("Cache-Control: no-cache, no-store, must-revalidate\r\n")
            append("Pragma: no-cache\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.flush()

        var lastSeq = -1L
        while (running.get() && !Thread.currentThread().isInterrupted) {
            val frame = frameBroker.latest()
            if (frame == null || frame.sequence == lastSeq || frame.format != expected) {
                Thread.sleep(2)
                continue
            }

            lastSeq = frame.sequence
            if (frame.payload.isNotEmpty()) {
                recordFrame()
                sendMjpegFrame(output, frame.payload)
            }
        }
    }

    private fun sendMjpegFrame(output: BufferedOutputStream, jpegData: ByteArray) {
        val header = buildString {
            append("--frame\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${jpegData.size}\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        val footer = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        output.write(header)
        output.write(jpegData)
        output.write(footer)
        output.flush()
    }

    companion object {
        private const val TAG = "StreamHttpServer"
    }
}
