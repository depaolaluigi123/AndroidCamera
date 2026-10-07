package com.androidcamera.webcam.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log
import android.util.Range
import android.util.Size
import com.androidcamera.webcam.model.AspectRatio
import com.androidcamera.webcam.model.StreamResolution
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Per-camera capability queries shared by the UI (which values to offer) and both capture
 * paths (CameraX and Camera2). Frames are analysed as YUV_420_888, so stream sizes and
 * frame rates are checked against that format. Characteristics are cached per camera id.
 */
object CameraCapabilities {

    private const val TAG = "CameraCapabilities"

    /** Largest preview stream: the preview is a card on the screen, more only costs ISP bandwidth. */
    private const val PREVIEW_MAX_WIDTH = 1280
    private const val PREVIEW_MAX_HEIGHT = 960

    /**
     * Qualcomm vendor table of `[width, height, minFps, maxFps]` rows: the rates the sensor
     * can stream at for a given largest stream size, beyond the standard AE target ranges.
     * Some phones only reach 60 fps through it (e.g. the main camera of the Redmi Note 9
     * Pro declares AE ranges up to 30 but 60 fps up to 2304×1728 here, which is how the
     * system camera app records 1080p60).
     */
    private const val QTI_STREAM_FPS_TABLE = "org.quic.camera2.streamBasedFPS.info.StreamBasedFPSTable"

    private val cache = ConcurrentHashMap<String, CameraCharacteristics>()

    fun characteristics(context: Context, cameraId: String): CameraCharacteristics? {
        cache[cameraId]?.let { return it }
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        return try {
            manager.getCameraCharacteristics(cameraId).also { cache[cameraId] = it }
        } catch (e: Exception) {
            Log.w(TAG, "getCameraCharacteristics($cameraId) failed: ${e.message}")
            null
        }
    }

    private fun yuvSizes(context: Context, cameraId: String): List<Size> =
        characteristics(context, cameraId)
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()

    /** True when the camera can deliver [resolution] (landscape) as YUV frames. */
    fun isResolutionSupported(context: Context, cameraId: String, resolution: StreamResolution): Boolean =
        yuvSizes(context, cameraId).any {
            it.width == resolution.landscapeWidth && it.height == resolution.landscapeHeight
        }

    /** Resolutions of [shape] the camera supports (all of them when it cannot be queried). */
    fun supportedResolutions(context: Context, cameraId: String, shape: AspectRatio): List<StreamResolution> {
        if (yuvSizes(context, cameraId).isEmpty()) return StreamResolution.of(shape)
        return StreamResolution.of(shape).filter { isResolutionSupported(context, cameraId, it) }
    }

    /**
     * Frame rates to offer at [resolution], highest first: the ones the camera can hold
     * constant (fixed [f, f] AE target ranges) at that size. A camera declaring no constant
     * rate gets the top of its variable ranges instead, so the list is never empty.
     */
    fun frameRateOptions(context: Context, cameraId: String, resolution: StreamResolution?): List<Int> {
        val chars = characteristics(context, cameraId) ?: return emptyList()
        val ranges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        val maxFps = maxFrameRate(chars, resolution)
        val constant = ranges.filter { it.lower == it.upper && it.upper <= maxFps }.map { it.upper }
        val rates = constant.ifEmpty { ranges.map { it.upper }.filter { it <= maxFps } }
        return (rates + vendorFixedRates(chars, resolution)).distinct().sortedDescending()
    }

    /**
     * Constant rates the vendor table ([QTI_STREAM_FPS_TABLE]) allows at [resolution]: rows
     * whose size covers the resolution and whose range is fixed ([f, f]).
     */
    private fun vendorFixedRates(chars: CameraCharacteristics, resolution: StreamResolution?): List<Int> {
        resolution ?: return emptyList()
        val table = vendorFpsTable(chars) ?: return emptyList()
        return table.toList().chunked(4).filter { row ->
            row.size == 4 && row[2] == row[3] &&
                row[0] >= resolution.landscapeWidth && row[1] >= resolution.landscapeHeight
        }.map { it[3] }.distinct()
    }

    @Suppress("UNCHECKED_CAST")
    private fun vendorFpsTable(chars: CameraCharacteristics): IntArray? = try {
        val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            CameraCharacteristics.Key(QTI_STREAM_FPS_TABLE, IntArray::class.java)
        } else {
            chars.keys.firstOrNull { it.name == QTI_STREAM_FPS_TABLE } as CameraCharacteristics.Key<IntArray>?
        }
        key?.let { chars.get(it) }
    } catch (e: Exception) {
        // Absent on non-Qualcomm devices (IllegalArgumentException for an unknown vendor tag).
        null
    }

    /** Highest rate the camera can stream [resolution] at as YUV (unlimited when unknown). */
    private fun maxFrameRate(chars: CameraCharacteristics, resolution: StreamResolution?): Int {
        resolution ?: return Int.MAX_VALUE
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return Int.MAX_VALUE
        val minFrameNs = try {
            map.getOutputMinFrameDuration(
                ImageFormat.YUV_420_888,
                Size(resolution.landscapeWidth, resolution.landscapeHeight)
            )
        } catch (e: Exception) {
            0L
        }
        return if (minFrameNs > 0) (1_000_000_000L / minFrameNs).toInt() else Int.MAX_VALUE
    }

    /** AE target range for [fps]: the fixed [fps, fps] range if present, else the closest one. */
    fun fpsRange(context: Context, cameraId: String, fps: Int): Range<Int>? {
        val ranges = characteristics(context, cameraId)
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.upper == fps }.maxByOrNull { it.lower }
            ?: Range(fps, fps).takeIf { isVendorRate(context, cameraId, fps) }
            ?: ranges.filter { it.upper >= fps }.minByOrNull { it.upper - it.lower + abs(it.upper - fps) }
            ?: ranges.maxByOrNull { it.upper }
    }

    /** True when [fps] comes from the vendor table, so it is not among the standard AE ranges. */
    private fun isVendorRate(context: Context, cameraId: String, fps: Int): Boolean {
        val table = characteristics(context, cameraId)?.let { vendorFpsTable(it) } ?: return false
        return table.toList().chunked(4).any { it.size == 4 && it[2] == fps && it[3] == fps }
    }

    /** Closest focus distance in diopters (0 = fixed-focus lens). */
    fun minimumFocusDistance(context: Context, cameraId: String): Float =
        characteristics(context, cameraId)?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f

    fun supportsManualFocus(context: Context, cameraId: String): Boolean =
        minimumFocusDistance(context, cameraId) > 0f

    /** Highest digital zoom factor (1 = no zoom). */
    fun maxDigitalZoom(context: Context, cameraId: String): Float =
        characteristics(context, cameraId)?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f

    fun hasFlash(context: Context, cameraId: String): Boolean =
        characteristics(context, cameraId)?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true

    /**
     * Preview stream size (landscape): the largest one of [aspect] within the preview cap,
     * so the preview shows the framing of the stream (a 16:9 stream is a centred crop of
     * the 4:3 sensor image).
     */
    fun previewSize(context: Context, cameraId: String, aspect: AspectRatio): Size {
        val sizes = characteristics(context, cameraId)
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        val fitting = sizes.filter { it.width <= PREVIEW_MAX_WIDTH && it.height <= PREVIEW_MAX_HEIGHT }
        return fitting.filter { aspect.matches(it.width, it.height) }.maxByOrNull { it.width * it.height }
            ?: fitting.maxByOrNull { it.width * it.height }
            ?: sizes.minByOrNull { it.width * it.height }
            ?: Size(PREVIEW_MAX_WIDTH, PREVIEW_MAX_HEIGHT)
    }
}
