package com.androidcamera.webcam.streaming

import com.androidcamera.webcam.camera.JpegEncoder
import com.androidcamera.webcam.model.StreamFormat
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class StreamFrame(
    val format: StreamFormat,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val payload: ByteArray,
    val sequence: Long,
    /** Which backend produced the payload (only meaningful when format == JPEG). */
    val encoderBackend: JpegEncoder.Backend? = null
)

/**
 * Thread-safe holder for the latest encoded frame produced by a camera.
 */
class FrameBroker {
    private val latestFrame = AtomicReference<StreamFrame?>(null)
    private val sequence = AtomicLong(0L)

    fun publish(
        format: StreamFormat,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        payload: ByteArray
    ) {
        latestFrame.set(
            StreamFrame(
                format = format,
                width = width,
                height = height,
                rotationDegrees = rotationDegrees,
                payload = payload,
                sequence = sequence.incrementAndGet(),
                encoderBackend = if (format == StreamFormat.JPEG) {
                    JpegEncoder.lastBackend
                } else {
                    null
                }
            )
        )
    }

    fun latest(): StreamFrame? = latestFrame.get()

    fun clear() {
        latestFrame.set(null)
    }
}
