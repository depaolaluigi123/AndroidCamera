package com.androidcamera.webcam.camera

import android.media.MediaCodecInfo
import android.media.MediaCodecList

/**
 * Tiny helper to enumerate MediaCodec encoders for a given MIME type.
 *
 * Kept separate from [JpegEncoder] so the encoder can stay focused on its
 * stateless I420→JPEG conversion logic.
 */
internal object MediaCodecInfoSelector {

    /**
     * Returns the first [MediaCodecInfo] for [mimeType], or null.
     *
     * Qualcomm vendor codecs (including the JPEG HW encoder on
     * Snapdragon 6xx/7xx devices) are sometimes absent from
     * [MediaCodecList.REGULAR_CODECS] on Xiaomi/MIUI builds.
     * We probe [REGULAR_CODECS] first, fall back to [ALL_CODECS]
     * (which surfaces vendor/hidden codecs), and return the first
     * encoder that claims the MIME type.
     */
    fun findEncoder(mimeType: String): MediaCodecInfo? {
        for (listType in listOf(MediaCodecList.REGULAR_CODECS, MediaCodecList.ALL_CODECS)) {
            MediaCodecList(listType).codecInfos
                .firstOrNull { it.isEncoder && it.supportedTypes.any { t -> t.equals(mimeType, ignoreCase = true) } }
                ?.let { return it }
        }
        return null
    }

    /**
     * Returns the list of colour formats advertised by the given encoder for
     * the MIME type. Used by [JpegEncoder] to decide which colour format to
     * feed into the codec (JPEG encoders on API 30+ typically advertise only
     * YUV420 flexible, which requires the Image API rather than a raw buffer).
     */
    fun colorFormats(info: MediaCodecInfo, mimeType: String): IntArray =
        runCatching { info.getCapabilitiesForType(mimeType).colorFormats }
            .getOrDefault(intArrayOf())
}
