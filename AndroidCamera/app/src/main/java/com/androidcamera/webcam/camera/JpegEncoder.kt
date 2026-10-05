package com.androidcamera.webcam.camera

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * JPEG encoder that prefers HW acceleration via MediaCodec on API 30+
 * and falls back to the software YuvImage compressor otherwise.
 *
 * The HW path uses the encoder's input [Image] (COLOR_FormatYUV420Flexible)
 * to feed the I420/YUV420 frame. The encoder advertises the colour format
 * via [android.media.MediaCodecInfo.CodecCapabilities]; we probe and adapt
 * to whichever subset the device supports (Flexible / SemiPlanar / Planar).
 *
 * When the HW encoder rejects the input, reports failure, or is missing
 * altogether, we silently fall back to the [YuvImage] SW path.
 *
 * Usage is stateless: call [encode] with an I420 byte buffer and it
 * returns the compressed JPEG bytes, or null on unrecoverable failure.
 */
object JpegEncoder {

    private const val TAG = "JpegEncoder"

    /** Which backend the most recent successful encode() used. */
    enum class Backend { HW_MEDIACODEC, SW_YUVIMAGE }

    /**
     * "Backend used for the last encode()" — observed by the GUI via the
     * /stream.info `jpegEncoder` field. volatile because encode() runs on
     * the analysis executor thread and the HTTP server reads it from a
     * worker thread.
     */
    @Volatile
    var lastBackend: Backend = Backend.SW_YUVIMAGE
        private set

    /**
     * Probe whether a HW JPEG encoder is actually usable on this device.
     * Called once at app startup (or on demand) and cached forever.
     * Returns null when the HW path has never been successfully exercised,
     * true when it has, false only when we have positively proven it is
     * unavailable (no codec found or codec creation fails).
     *
     * The probe finds the first image/jpeg codec, attempts a minimal
     * configure+start cycle, and immediately releases it.
     */
    private val hwAvailable: Boolean? by lazy { probe() }

    /**
     * Convenience accessor. May return null during first access (lazy)
     * or false if the HW encoder is provably absent or broken.
     */
    fun isHardwareAvailable(): Boolean = hwAvailable == true

    private fun probe(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val codecInfo = MediaCodecInfoSelector.findEncoder("image/jpeg")
        if (codecInfo == null) {
            Log.i(TAG, "HW JPEG probe: no image/jpeg encoder in MediaCodecList")
            return false
        }
        Log.i(TAG, "HW JPEG probe: found encoder ${codecInfo.name}")
        return try {
            val codec = MediaCodec.createByCodecName(codecInfo.name)
            val format = MediaFormat.createVideoFormat("image/jpeg", 640, 480)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 640 * 480)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codec.stop()
            codec.release()
            Log.i(TAG, "HW JPEG probe: SUCCESS")
            true
        } catch (e: Exception) {
            Log.w(TAG, "HW JPEG probe: FAILED — ${e.message}")
            false
        }
    }

    /** Encode I420 (YUV420 planar) → JPEG. */
    fun encode(
        i420: ByteArray,
        width: Int,
        height: Int,
        quality: Int
    ): ByteArray? {
        // MediaCodec JPEG encoder is available from API 30 (Android 11).
        // On older or broken devices we silently fall back to the SW path.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && width > 0 && height > 0) {
            val hw = try {
                encodeHw(i420, width, height, quality)
            } catch (e: Exception) {
                Log.w(TAG, "HW JPEG encoder failed, falling back to SW: ${e.message}")
                null
            }
            if (hw != null) {
                lastBackend = Backend.HW_MEDIACODEC
                return hw
            }
        }
        val sw = encodeSw(i420, width, height, quality)
        if (sw != null) lastBackend = Backend.SW_YUVIMAGE
        return sw
    }

    // ── HW path (MediaCodec image/jpeg encoder, API 30+) ──────────

    /**
     * MediaCodec's `image/jpeg` encoder on API 30+ advertises
     * COLOR_FormatYUV420Flexible (Image API). We pull an input Image from
     * the codec, fill its Y/U/V planes from the planar I420 buffer, queue
     * it, then poll the output buffer for the JPEG bytes.
     *
     * If the encoder only advertises SemiPlanar (NV12/NV21) we convert
     * I420→NV21 and copy into a plain ByteBuffer (the legacy direct-feed
     * path). Flexible is the common case on modern devices.
     */
    @SuppressLint("UnsafeOptInUsageError")
    private fun encodeHw(
        i420: ByteArray,
        width: Int,
        height: Int,
        quality: Int
    ): ByteArray? {
        val codecInfo = MediaCodecInfoSelector.findEncoder("image/jpeg")
        if (codecInfo == null) {
            Log.w(TAG, "HW JPEG: no image/jpeg encoder found in MediaCodecList")
            return null
        }
        Log.i(
            TAG,
            "HW JPEG: picked encoder=${codecInfo.name} " +
                "isVendor=${codecInfo.isVendor}"
        )
        val supported = MediaCodecInfoSelector.colorFormats(codecInfo, "image/jpeg").toSet()
        Log.i(TAG, "HW JPEG: supported color formats=$supported")
        val colorFormat = when {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in supported ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in supported ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar in supported ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            else -> {
                Log.w(TAG, "HW JPEG: no supported YUV420 colour format")
                return null
            }
        }
        val codec = MediaCodec.createByCodecName(codecInfo.name)
        // The image/jpeg encoder uses 'quality' (1..100) as its main knob;
        // bitrate / framerate / I-frame interval are not meaningful for a
        // still encoder, so we omit them.
        val format = MediaFormat.createVideoFormat("image/jpeg", width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger("quality", quality.coerceIn(1, 100))
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        try {
            if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                return encodeHwFlexible(codec, i420, width, height)
            }
            // Legacy direct-feed path (SemiPlanar / Planar): pick the buffer
            // size that matches the requested colour format.
            val feed: ByteArray = when (colorFormat) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ->
                    i420ToNv21(i420, width, height)
                else -> i420
            }
            return encodeHwDirect(codec, feed)
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) { /* no-op */ }
            codec.release()
        }
    }

    /**
     * Image API path: pull an input Image, fill its planes from the
     * planar I420 source, queue, then poll the output for JPEG bytes.
     */
    private fun encodeHwFlexible(
        codec: MediaCodec,
        i420: ByteArray,
        width: Int,
        height: Int
    ): ByteArray? {
        val inputBufIndex = codec.dequeueInputBuffer(-1L)
        if (inputBufIndex < 0) return null
        // Must call getInputImage after dequeue; the Image is recycled
        // when releaseOutputBuffer / a new dequeue happens.
        val image = codec.getInputImage(inputBufIndex) ?: return null
        if (image.format != ImageFormat.YUV_420_888) {
            // Flexible encoders surface the input as YUV_420_888 (the public
            // constant for the YUV_420_FLEXIBLE layout). Bail out and let
            // the direct path handle it next time.
            return null
        }
        if (!writeI420ToImage420(i420, width, height, image)) return null
        // PTS=0, flags=0 → still frame. The image bytes are written through
        // the planes, so we use queueInputBuffer with size 0 — the Image's
        // planes are what actually carry the data.
        codec.queueInputBuffer(inputBufIndex, 0, 0, 0L, 0)
        return drainJpegOnce(codec)
    }

    /**
     * Direct ByteBuffer path: copy the (already-converted) YUV buffer into
     * the codec's raw input buffer, queue it, drain the JPEG output once.
     */
    private fun encodeHwDirect(codec: MediaCodec, feed: ByteArray): ByteArray? {
        val inputBufIndex = codec.dequeueInputBuffer(-1L)
        if (inputBufIndex < 0) return null
        val inputBuf = codec.getInputBuffer(inputBufIndex) ?: return null
        inputBuf.clear()
        if (inputBuf.remaining() < feed.size) {
            Log.w(TAG, "HW input buffer too small (have ${inputBuf.remaining()}, need ${feed.size})")
            return null
        }
        inputBuf.put(feed)
        codec.queueInputBuffer(inputBufIndex, 0, feed.size, 0L, 0)
        return drainJpegOnce(codec)
    }

    /** Poll the encoder once for the JPEG output buffer. */
    private fun drainJpegOnce(codec: MediaCodec): ByteArray? {
        val bufferInfo = MediaCodec.BufferInfo()
        val deadline = System.nanoTime() + 2_000_000_000L // 2 s timeout
        while (System.nanoTime() < deadline) {
            val outBufIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000L)
            when {
                outBufIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outBufIndex) ?: continue
                    val jpeg = ByteArray(bufferInfo.size)
                    outBuf.get(jpeg)
                    codec.releaseOutputBuffer(outBufIndex, false)
                    return jpeg
                }
                outBufIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> continue
                outBufIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> { /* retry */ }
                outBufIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* retry */ }
                else -> continue
            }
        }
        Log.w(TAG, "HW JPEG encoder timed out waiting for output")
        return null
    }

    /**
     * Copy a tightly-packed I420 buffer into a [Image] with format
     * YUV_420_888 / YUV_420_FLEXIBLE. The Image's plane strides may differ
     * from the source width, so we walk row × col and respect row/pixel
     * stride on each plane.
     */
    private fun writeI420ToImage420(
        i420: ByteArray,
        width: Int,
        height: Int,
        image: Image
    ): Boolean {
        val planes = image.planes
        if (planes.size < 3) return false
        val yPlane = planes[0]
        val uPlane = planes[1]
        val vPlane = planes[2]
        val ySize = width * height
        val cSize = ySize / 4
        if (i420.size < ySize + 2 * cSize) return false
        // Y plane: I420 Y is at offset 0, contiguous (width*height).
        val yBuf = yPlane.buffer
        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        yBuf.clear()
        if (yPixelStride == 1 && yRowStride == width) {
            yBuf.put(i420, 0, ySize)
        } else {
            var src = 0
            for (row in 0 until height) {
                val rowStart = row * yRowStride
                for (col in 0 until width) {
                    yBuf.put(rowStart + col * yPixelStride, i420[src++])
                }
            }
        }
        // U plane: I420 U is at offset ySize.
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val uBuf = uPlane.buffer
        uBuf.clear()
        val uSrcOffset = ySize
        if (uPixelStride == 1 && uRowStride == width / 2) {
            uBuf.put(i420, uSrcOffset, cSize)
        } else {
            var src = uSrcOffset
            val cw = width / 2
            val ch = height / 2
            for (row in 0 until ch) {
                val rowStart = row * uRowStride
                for (col in 0 until cw) {
                    uBuf.put(rowStart + col * uPixelStride, i420[src++])
                }
            }
        }
        // V plane: I420 V is at offset ySize + cSize.
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val vBuf = vPlane.buffer
        vBuf.clear()
        val vSrcOffset = ySize + cSize
        if (vPixelStride == 1 && vRowStride == width / 2) {
            vBuf.put(i420, vSrcOffset, cSize)
        } else {
            var src = vSrcOffset
            val cw = width / 2
            val ch = height / 2
            for (row in 0 until ch) {
                val rowStart = row * vRowStride
                for (col in 0 until cw) {
                    vBuf.put(rowStart + col * vPixelStride, i420[src++])
                }
            }
        }
        return true
    }

    // ── SW path (YuvImage — existing software encoder) ─────────────

    private fun encodeSw(
        i420: ByteArray,
        width: Int,
        height: Int,
        quality: Int
    ): ByteArray? {
        try {
            val nv21 = i420ToNv21(i420, width, height)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val output = ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), quality, output)
            return output.toByteArray()
        } catch (e: Exception) {
            Log.w(TAG, "SW JPEG encoder failed: ${e.message}")
            return null
        }
    }

    /** Same I420→NV21 conversion used by the SW path; kept here for
     *  encapsulation so callers in the camera package do not need
     *  to carry their own copy. */
    fun i420ToNv21(i420: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = ySize / 4
        val nv21 = ByteArray(ySize + 2 * uvSize)
        System.arraycopy(i420, 0, nv21, 0, ySize)
        val uOffset = ySize
        val vOffset = ySize + uvSize
        val nv21UvOffset = ySize
        for (i in 0 until uvSize) {
            nv21[nv21UvOffset + i * 2] = i420[vOffset + i]
            nv21[nv21UvOffset + i * 2 + 1] = i420[uOffset + i]
        }
        return nv21
    }
}
