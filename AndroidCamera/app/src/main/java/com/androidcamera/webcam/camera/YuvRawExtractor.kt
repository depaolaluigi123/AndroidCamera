package com.androidcamera.webcam.camera

import android.graphics.ImageFormat
import androidx.camera.core.ImageProxy

/**
 * Extracts tightly-packed I420 (yuv420p) from CameraX YUV_420_888 frames,
 * then rotates / scales to the configured output size.
 */
class YuvRawExtractor {

    /**
     * Build an upright (already oriented) I420 frame at [targetWidth]×[targetHeight].
     * [rotationDegrees] must be 0 / 90 / 180 / 270 (sensor + orientation extras).
     */
    fun processToOutput(
        image: ImageProxy,
        targetWidth: Int,
        targetHeight: Int,
        rotationDegrees: Int
    ): Triple<Int, Int, ByteArray>? {
        val src = toI420(image) ?: return null
        return processI420(src, image.width, image.height, targetWidth, targetHeight, rotationDegrees)
    }

    /** Orient + letterbox an already-packed I420 buffer. */
    fun processI420(
        src: ByteArray,
        srcWidth: Int,
        srcHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        rotationDegrees: Int
    ): Triple<Int, Int, ByteArray>? {
        val rotated = rotateI420(src, srcWidth, srcHeight, rotationDegrees)
        val rw = if (rotationDegrees % 180 == 0) srcWidth else srcHeight
        val rh = if (rotationDegrees % 180 == 0) srcHeight else srcWidth
        val dw = (targetWidth / 2) * 2
        val dh = (targetHeight / 2) * 2
        if (dw < 2 || dh < 2) return null
        // Fit inside the target (letterbox) — never crop top/bottom/sides after orienting.
        val out = scaleI420FitPad(rotated, rw, rh, dw, dh)
        return Triple(dw, dh, out)
    }

    fun toI420(image: ImageProxy): ByteArray? {
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

        copyChromaPlane(uBuffer, uPlane.rowStride, uPlane.pixelStride, width / 2, height / 2, out, outIndex)
        outIndex += cSize
        copyChromaPlane(vBuffer, vPlane.rowStride, vPlane.pixelStride, width / 2, height / 2, out, outIndex)
        return out
    }

    private fun copyChromaPlane(
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

    private fun rotateI420(src: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
        val d = ((degrees % 360) + 360) % 360
        if (d == 0) return src
        return when (d) {
            90 -> rotate90(src, width, height)
            180 -> rotate180(src, width, height)
            270 -> rotate270(src, width, height)
            else -> src
        }
    }

    private fun rotate90(src: ByteArray, w: Int, h: Int): ByteArray {
        val dw = h
        val dh = w
        val ySize = dw * dh
        val cSize = ySize / 4
        val out = ByteArray(ySize + cSize + cSize)
        // Y
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[x * dw + (h - 1 - y)] = src[y * w + x]
            }
        }
        val swc = w / 2
        val shc = h / 2
        val dwc = dw / 2
        val dhc = dh / 2
        val srcU = w * h
        val srcV = srcU + swc * shc
        val dstU = ySize
        val dstV = ySize + cSize
        for (y in 0 until shc) {
            for (x in 0 until swc) {
                val dx = shc - 1 - y
                val dy = x
                out[dstU + dy * dwc + dx] = src[srcU + y * swc + x]
                out[dstV + dy * dwc + dx] = src[srcV + y * swc + x]
            }
        }
        return out
    }

    private fun rotate180(src: ByteArray, w: Int, h: Int): ByteArray {
        val ySize = w * h
        val cSize = ySize / 4
        val out = ByteArray(ySize + cSize + cSize)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[(h - 1 - y) * w + (w - 1 - x)] = src[y * w + x]
            }
        }
        val swc = w / 2
        val shc = h / 2
        val srcU = ySize
        val srcV = srcU + cSize
        val dstU = ySize
        val dstV = ySize + cSize
        for (y in 0 until shc) {
            for (x in 0 until swc) {
                val dx = swc - 1 - x
                val dy = shc - 1 - y
                out[dstU + dy * swc + dx] = src[srcU + y * swc + x]
                out[dstV + dy * swc + dx] = src[srcV + y * swc + x]
            }
        }
        return out
    }

    private fun rotate270(src: ByteArray, w: Int, h: Int): ByteArray {
        val dw = h
        val dh = w
        val ySize = dw * dh
        val cSize = ySize / 4
        val out = ByteArray(ySize + cSize + cSize)
        for (y in 0 until h) {
            for (x in 0 until w) {
                out[(w - 1 - x) * dw + y] = src[y * w + x]
            }
        }
        val swc = w / 2
        val shc = h / 2
        val dwc = dw / 2
        val dhc = dh / 2
        val srcU = w * h
        val srcV = srcU + swc * shc
        val dstU = ySize
        val dstV = ySize + cSize
        for (y in 0 until shc) {
            for (x in 0 until swc) {
                val dx = y
                val dy = swc - 1 - x
                out[dstU + dy * dwc + dx] = src[srcU + y * swc + x]
                out[dstV + dy * dwc + dx] = src[srcV + y * swc + x]
            }
        }
        return out
    }

    /** Scale with center-crop so the output exactly fills [dw]×[dh]. */
    private fun scaleI420CenterCrop(
        src: ByteArray,
        sw: Int,
        sh: Int,
        dw: Int,
        dh: Int
    ): ByteArray {
        if (sw == dw && sh == dh) return src
        val scale = maxOf(dw.toFloat() / sw, dh.toFloat() / sh)
        val cropW = ((dw / scale).toInt() / 2) * 2
        val cropH = ((dh / scale).toInt() / 2) * 2
        val x0 = ((sw - cropW) / 4) * 2
        val y0 = ((sh - cropH) / 4) * 2
        val cropped = cropI420(src, sw, sh, x0, y0, cropW.coerceAtLeast(2), cropH.coerceAtLeast(2))
        return scaleI420(cropped, cropW.coerceAtLeast(2), cropH.coerceAtLeast(2), dw, dh)
    }

    /** Scale down/up to fit inside [dw]×[dh], pad with black — no cropping. */
    private fun scaleI420FitPad(
        src: ByteArray,
        sw: Int,
        sh: Int,
        dw: Int,
        dh: Int
    ): ByteArray {
        if (sw == dw && sh == dh) return src
        val scale = minOf(dw.toFloat() / sw, dh.toFloat() / sh)
        var mw = ((sw * scale).toInt() / 2) * 2
        var mh = ((sh * scale).toInt() / 2) * 2
        mw = mw.coerceIn(2, dw)
        mh = mh.coerceIn(2, dh)
        val scaled = scaleI420(src, sw, sh, mw, mh)
        if (mw == dw && mh == dh) return scaled
        val ySize = dw * dh
        val cSize = ySize / 4
        val out = ByteArray(ySize + cSize + cSize) // zeroed = black
        val x0 = ((dw - mw) / 4) * 2
        val y0 = ((dh - mh) / 4) * 2
        for (y in 0 until mh) {
            System.arraycopy(scaled, y * mw, out, (y0 + y) * dw + x0, mw)
        }
        val mwC = mw / 2
        val mhC = mh / 2
        val dwC = dw / 2
        val x0c = x0 / 2
        val y0c = y0 / 2
        val srcU = mw * mh
        val srcV = srcU + mwC * mhC
        val dstU = ySize
        val dstV = ySize + cSize
        for (y in 0 until mhC) {
            System.arraycopy(
                scaled,
                srcU + y * mwC,
                out,
                dstU + (y0c + y) * dwC + x0c,
                mwC
            )
            System.arraycopy(
                scaled,
                srcV + y * mwC,
                out,
                dstV + (y0c + y) * dwC + x0c,
                mwC
            )
        }
        return out
    }

    private fun cropI420(
        src: ByteArray,
        sw: Int,
        sh: Int,
        x0: Int,
        y0: Int,
        cw: Int,
        ch: Int
    ): ByteArray {
        val ySize = cw * ch
        val cSize = ySize / 4
        val out = ByteArray(ySize + cSize + cSize)
        for (y in 0 until ch) {
            System.arraycopy(src, (y0 + y) * sw + x0, out, y * cw, cw)
        }
        val swc = sw / 2
        val srcU = sw * sh
        val srcV = srcU + swc * (sh / 2)
        val dstU = ySize
        val dstV = ySize + cSize
        val x0c = x0 / 2
        val y0c = y0 / 2
        val cwc = cw / 2
        val chc = ch / 2
        for (y in 0 until chc) {
            System.arraycopy(src, srcU + (y0c + y) * swc + x0c, out, dstU + y * cwc, cwc)
            System.arraycopy(src, srcV + (y0c + y) * swc + x0c, out, dstV + y * cwc, cwc)
        }
        return out
    }

    private fun scaleI420(
        src: ByteArray,
        sw: Int,
        sh: Int,
        dw: Int,
        dh: Int
    ): ByteArray {
        if (sw == dw && sh == dh) return src
        val ySize = dw * dh
        val cSize = ySize / 4
        val out = ByteArray(ySize + cSize + cSize)
        val xRatio = sw.toFloat() / dw
        val yRatio = sh.toFloat() / dh

        for (y in 0 until dh) {
            val sy = (y * yRatio).toInt().coerceIn(0, sh - 1)
            for (x in 0 until dw) {
                val sx = (x * xRatio).toInt().coerceIn(0, sw - 1)
                out[y * dw + x] = src[sy * sw + sx]
            }
        }

        val swc = sw / 2
        val shc = sh / 2
        val dwc = dw / 2
        val dhc = dh / 2
        val srcU = sw * sh
        val srcV = srcU + swc * shc
        val dstU = ySize
        val dstV = ySize + cSize
        val cxRatio = swc.toFloat() / dwc
        val cyRatio = shc.toFloat() / dhc

        for (y in 0 until dhc) {
            val sy = (y * cyRatio).toInt().coerceIn(0, shc - 1)
            for (x in 0 until dwc) {
                val sx = (x * cxRatio).toInt().coerceIn(0, dwc - 1).coerceIn(0, swc - 1)
                out[dstU + y * dwc + x] = src[srcU + sy * swc + sx]
                out[dstV + y * dwc + x] = src[srcV + sy * swc + sx]
            }
        }
        return out
    }
}
