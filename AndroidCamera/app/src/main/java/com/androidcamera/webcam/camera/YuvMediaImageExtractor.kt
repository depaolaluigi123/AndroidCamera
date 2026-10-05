package com.androidcamera.webcam.camera

import android.graphics.ImageFormat
import android.media.Image

/**
 * Same I420 pipeline as [YuvRawExtractor], but for Camera2 [Image] frames.
 */
class YuvMediaImageExtractor {
    private val delegate = YuvRawExtractor()

    fun processToOutput(
        image: Image,
        targetWidth: Int,
        targetHeight: Int,
        rotationDegrees: Int
    ): Triple<Int, Int, ByteArray>? {
        val src = toI420(image) ?: return null
        return delegate.processI420(
            src,
            image.width,
            image.height,
            targetWidth,
            targetHeight,
            rotationDegrees
        )
    }

    fun toI420(image: Image): ByteArray? {
        if (image.format != ImageFormat.YUV_420_888) return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0 || width % 2 != 0 || height % 2 != 0) return null

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer.duplicate().also { it.clear() }
        val uBuffer = uPlane.buffer.duplicate().also { it.clear() }
        val vBuffer = vPlane.buffer.duplicate().also { it.clear() }

        val ySize = width * height
        val cSize = ySize / 4
        val out = ByteArray(ySize + cSize + cSize)
        var outIndex = 0

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        if (yPixelStride == 1) {
            if (yRowStride == width) {
                yBuffer.get(out, 0, ySize)
                outIndex = ySize
            } else {
                val row = ByteArray(width)
                for (rowIdx in 0 until height) {
                    yBuffer.position(rowIdx * yRowStride)
                    yBuffer.get(row, 0, width)
                    System.arraycopy(row, 0, out, outIndex, width)
                    outIndex += width
                }
            }
        } else {
            for (row in 0 until height) {
                val rowStart = row * yRowStride
                for (col in 0 until width) {
                    out[outIndex++] = yBuffer.get(rowStart + col * yPixelStride)
                }
            }
        }

        copyChroma(uBuffer, uPlane.rowStride, uPlane.pixelStride, width / 2, height / 2, out, outIndex)
        outIndex += cSize
        copyChroma(vBuffer, vPlane.rowStride, vPlane.pixelStride, width / 2, height / 2, out, outIndex)
        return out
    }

    private fun copyChroma(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        out: ByteArray,
        outOffset: Int
    ) {
        var dst = outOffset
        if (pixelStride == 1) {
            if (rowStride == width) {
                buffer.position(0)
                buffer.get(out, outOffset, width * height)
            } else {
                val row = ByteArray(width)
                for (rowIdx in 0 until height) {
                    buffer.position(rowIdx * rowStride)
                    buffer.get(row, 0, width)
                    System.arraycopy(row, 0, out, dst, width)
                    dst += width
                }
            }
        } else {
            for (row in 0 until height) {
                val rowStart = row * rowStride
                for (col in 0 until width) {
                    out[dst++] = buffer.get(rowStart + col * pixelStride)
                }
            }
        }
    }
}
