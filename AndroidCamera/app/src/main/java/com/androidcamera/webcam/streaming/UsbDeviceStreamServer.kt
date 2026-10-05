package com.androidcamera.webcam.streaming

import android.util.Log
import com.androidcamera.webcam.model.StreamConfig
import com.androidcamera.webcam.model.StreamFormat
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Binary ACUS stream over TCP (no HTTP). Used by ConnectionMode.USB_DEVICE.
 *
 * Protocol v1 (big-endian). The `version` byte is always 1.
 * The trailing bytes after the device name are FLAG-DRIVEN (not a fixed
 * pair): exactly 0/1 bytes depending on the JPEG flag. Frame batching
 * is not part of v1.
 *
 * Handshake once per connection:
 *   magic[4]="ACUS", version[u8]=1, flags[u8],
 *   width[u16], height[u16], fps[u16], rotation[u16],
 *   name_len[u8], name[name_len],
 *   jpeg_quality[u8]   (present iff FLAG_JPEG is set)
 *
 * Trailing-byte table (matches ACUS-PROTOCOL.md):
 *   flags=0x00  → 0 trailing bytes  (YUV — most common)
 *   flags=0x01  → 1 byte jpeg_quality (JPEG)
 *
 * Then frames:
 *   magic[4]="FRME", sequence[u32], size[u32], data[size]
 *     — YUV: one I420 frame (w*h*3/2 bytes)
 *     — JPEG: one JPEG frame
 */
class UsbDeviceStreamServer(
    private val port: Int,
    private val frameBroker: FrameBroker,
    private val streamConfig: StreamConfig
) {
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()
    private val acceptExecutor = Executors.newSingleThreadExecutor()
    private val streamExecutor = Executors.newCachedThreadPool()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        // Bind localhost so the stream is only reachable via ADB reverse / USB debug.
        serverSocket = ServerSocket(port, 8, InetAddress.getByName("127.0.0.1")).also {
            it.reuseAddress = true
        }
        acceptExecutor.execute { acceptLoop() }
        Log.i(TAG, "USB Device ACUS server on 127.0.0.1:$port device=${streamConfig.deviceName}")
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
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
            val output = BufferedOutputStream(socket.getOutputStream())
            val seed = waitForFirstFrame() ?: return
            writeHandshake(output, seed)
            // Stream the format the user actually selected. The frame broker
            // publishes frames already encoded (raw I420 for YUV, JPEG bytes for
            // JPEG), so we just forward payloads — no re-encoding here.
            val expectedFormat = streamConfig.streamFormat
            var lastSeq = -1L
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val frame = frameBroker.latest()
                if (frame == null || frame.sequence == lastSeq || frame.format != expectedFormat) {
                    Thread.sleep(2)
                    continue
                }
                lastSeq = frame.sequence
                writeFrame(output, frame)
            }
        } catch (_: IOException) {
            // client disconnected
        } finally {
            clients.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun waitForFirstFrame(): StreamFrame? {
        val expectedFormat = streamConfig.streamFormat
        val waitStart = System.nanoTime()
        while (running.get()) {
            val latest = frameBroker.latest()
            val frame = latest?.takeIf { it.format == expectedFormat }
            if (frame != null) return frame
            if (System.nanoTime() - waitStart > 10_000_000_000L) {
                Log.w(
                    TAG,
                    "No ${expectedFormat} frames yet for USB Device client " +
                        "(latest=${latest?.format}, expected=${expectedFormat})"
                )
                return null
            }
            Thread.sleep(5)
        }
        return null
    }

    private fun writeHandshake(output: BufferedOutputStream, seed: StreamFrame) {
        // ACUS Protocol v1. The trailing bytes after the name are
        // FLAG-DRIVEN (0/1 bytes depending on the JPEG flag). The PC must
        // read exactly that many bytes — never a fixed pair — so we only
        // emit jpeg_quality when its flag bit is set.
        val nameBytes = streamConfig.deviceName
            .toByteArray(StandardCharsets.UTF_8)
            .let { if (it.size > 255) it.copyOf(255) else it }
        val isJpeg = streamConfig.streamFormat == StreamFormat.JPEG
        var flags: Int = 0
        if (isJpeg) flags = flags or FLAG_JPEG
        // jpeg_quality: real value for JPEG (and FLAG_JPEG is set so it is
        // written to the wire); for YUV the flag is clear and we don't write.
        val jpegQualityByte = if (isJpeg) streamConfig.jpegQuality.quality else 0
        val trailing = if (isJpeg) 1 else 0
        val header = ByteBuffer.allocate(
            4 + 1 + 1 + 2 + 2 + 2 + 2 + 1 + nameBytes.size + trailing
        ).order(ByteOrder.BIG_ENDIAN)
        header.put(MAGIC_ACUS)
        header.put(PROTOCOL_VERSION)
        header.put(flags.toByte())
        header.putShort(seed.width.toShort())
        header.putShort(seed.height.toShort())
        header.putShort(streamConfig.fps.fps.toShort())
        header.putShort(seed.rotationDegrees.toShort())
        header.put(nameBytes.size.toByte())
        header.put(nameBytes)
        if (isJpeg) header.put(jpegQualityByte.toByte())
        output.write(header.array())
        output.flush()
    }

    /**
     * Surfaces the encoder backend (HW vs SW) used for the last JPEG frame,
     * so the GUI can show "Encoder: HW (MediaCodec)" / "Encoder: SW (YuvImage)".
     */
    fun lastEncoderBackendName(): String? {
        val frame = frameBroker.latest() ?: return null
        return frame.encoderBackend?.name
    }

    /**
     * Write a single frame envelope: "FRME"|seq|size|payload, then flush.
     */
    private fun writeFrame(output: BufferedOutputStream, frame: StreamFrame) {
        val header = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN)
        header.put(MAGIC_FRME)
        header.putInt(frame.sequence.toInt())
        header.putInt(frame.payload.size)
        output.write(header.array())
        output.write(frame.payload)
        output.flush()
    }

    companion object {
        private const val TAG = "UsbDeviceStreamServer"
        /** ACUS Protocol v1. */
        const val PROTOCOL_VERSION: Byte = 1
        const val FLAG_JPEG: Int = 0x01
        val MAGIC_ACUS: ByteArray = byteArrayOf(0x41, 0x43, 0x55, 0x53) // "ACUS"
        val MAGIC_FRME: ByteArray = byteArrayOf(0x46, 0x52, 0x4D, 0x45) // "FRME"
    }
}
