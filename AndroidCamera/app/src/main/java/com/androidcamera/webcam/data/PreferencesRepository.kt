package com.androidcamera.webcam.data

import android.content.Context
import com.androidcamera.webcam.model.AppLanguage
import com.androidcamera.webcam.model.AppThemeMode
import com.androidcamera.webcam.model.ConnectionMode
import com.androidcamera.webcam.model.JpegQuality
import com.androidcamera.webcam.model.StreamConfig
import com.androidcamera.webcam.model.StreamFormat
import com.androidcamera.webcam.model.StreamFps
import com.androidcamera.webcam.model.StreamOrientation
import com.androidcamera.webcam.model.StreamResolution

/**
 * Persists user preferences. UI and managers read from here instead of
 * hard-coding defaults across the app.
 */
class PreferencesRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // One-shot cleanup: frame batching was removed; the legacy key is no
        // longer read, so we drop it from existing installs to avoid carrying
        // around a value that no longer has a UI surface.
        if (prefs.contains(KEY_FRAME_BATCH_SIZE)) {
            prefs.edit().remove(KEY_FRAME_BATCH_SIZE).apply()
        }
    }

    var connectionMode: ConnectionMode
        get() = runCatching {
            ConnectionMode.valueOf(
                prefs.getString(KEY_CONNECTION_MODE, ConnectionMode.USB_DEVICE.name)
                    ?: ConnectionMode.USB_DEVICE.name
            )
        }.getOrDefault(ConnectionMode.USB_DEVICE)
        set(value) = prefs.edit().putString(KEY_CONNECTION_MODE, value.name).apply()

    /**
     * Camera2 camera id selected by the user, or null to pick a default at runtime.
     * Writing clears the legacy FRONT/BACK preference key.
     */
    var selectedCameraId: String?
        get() = prefs.getString(KEY_CAMERA_ID, null)?.trim()?.takeIf { it.isNotEmpty() }
        set(value) {
            val cleaned = value?.trim().orEmpty()
            prefs.edit()
                .putString(KEY_CAMERA_ID, cleaned.ifEmpty { null })
                .remove(KEY_CAMERA_SELECTION)
                .apply()
        }

    /** Legacy facing token: "FRONT", "BACK", "BOTH", or null if never set / already migrated. */
    fun legacyCameraFacing(): String? =
        prefs.getString(KEY_CAMERA_SELECTION, null)

    var httpPort: Int
        get() = prefs.getInt(KEY_HTTP_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_HTTP_PORT, value).apply()

    var themeMode: AppThemeMode
        get() = AppThemeMode.valueOf(
            prefs.getString(KEY_THEME, AppThemeMode.LIGHT.name) ?: AppThemeMode.LIGHT.name
        )
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    var language: AppLanguage
        get() = AppLanguage.fromCode(prefs.getString(KEY_LANGUAGE, AppLanguage.ENGLISH.code)!!)
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value.code).apply()

    var streamResolution: StreamResolution
        get() = StreamResolution.fromName(
            prefs.getString(KEY_STREAM_RESOLUTION, StreamResolution.RES_480P.name)
                ?: StreamResolution.RES_480P.name
        )
        set(value) = prefs.edit().putString(KEY_STREAM_RESOLUTION, value.name).apply()

    var streamFps: StreamFps
        get() = StreamFps.fromInt(prefs.getInt(KEY_STREAM_FPS, StreamFps.FPS_60.fps))
        set(value) = prefs.edit().putInt(KEY_STREAM_FPS, value.fps).apply()

    var streamOrientation: StreamOrientation
        get() = runCatching {
            StreamOrientation.valueOf(
                prefs.getString(KEY_STREAM_ORIENTATION, StreamOrientation.PORTRAIT.name)!!
            )
        }.getOrDefault(StreamOrientation.PORTRAIT)
        set(value) = prefs.edit().putString(KEY_STREAM_ORIENTATION, value.name).apply()

    var linearZoom: Float
        get() = prefs.getFloat(KEY_LINEAR_ZOOM, 0f).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_LINEAR_ZOOM, value.coerceIn(0f, 1f)).apply()

    var deviceName: String
        get() {
            val raw = prefs.getString(KEY_DEVICE_NAME, StreamConfig.DEFAULT_DEVICE_NAME)
                ?.trim()
                .orEmpty()
            return raw.ifEmpty { StreamConfig.DEFAULT_DEVICE_NAME }
        }
        set(value) {
            val cleaned = value.trim().ifEmpty { StreamConfig.DEFAULT_DEVICE_NAME }
            prefs.edit().putString(KEY_DEVICE_NAME, cleaned).apply()
        }

    var jpegQuality: JpegQuality
        get() = JpegQuality.fromInt(prefs.getInt(KEY_JPEG_QUALITY, JpegQuality.QUALITY_40.quality))
        set(value) = prefs.edit().putInt(KEY_JPEG_QUALITY, value.quality).apply()

    var streamFormat: StreamFormat
        get() = runCatching {
            StreamFormat.valueOf(
                prefs.getString(KEY_STREAM_FORMAT, StreamFormat.JPEG.name) ?: StreamFormat.JPEG.name
            )
        }.getOrDefault(StreamFormat.JPEG)
        set(value) = prefs.edit().putString(KEY_STREAM_FORMAT, value.name).apply()

    var manualFocusEnabled: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_FOCUS, false)
        set(value) = prefs.edit().putBoolean(KEY_MANUAL_FOCUS, value).apply()

    /** True when the user has ever toggled the manual-focus checkbox.
     *  Used so the activity can honour the saved value on subsequent launches
     *  while still defaulting to automatic (off) on the very first run. */
    var manualFocusTouched: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_FOCUS_TOUCHED, false)
        set(value) = prefs.edit().putBoolean(KEY_MANUAL_FOCUS_TOUCHED, value).apply()

    /** Whether the user has enabled manual ISO/exposure overrides. */
    var manualAdjustmentsEnabled: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_ADJUSTMENTS, false)
        set(value) = prefs.edit().putBoolean(KEY_MANUAL_ADJUSTMENTS, value).apply()

    /** True when the user has ever toggled the manual-adjustments checkbox. */
    var manualAdjustmentsTouched: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_ADJUSTMENTS_TOUCHED, false)
        set(value) = prefs.edit().putBoolean(KEY_MANUAL_ADJUSTMENTS_TOUCHED, value).apply()

    var focusDistance: Float
        get() = prefs.getFloat(KEY_FOCUS_DISTANCE, 0f).coerceIn(0f, 1f)
        set(value) = prefs.edit().putFloat(KEY_FOCUS_DISTANCE, value.coerceIn(0f, 1f)).apply()

    var iso: Int
        get() = prefs.getInt(KEY_ISO, 0)
        set(value) = prefs.edit().putInt(KEY_ISO, value).apply()

    var exposureTimeUs: Long
        get() = prefs.getLong(KEY_EXPOSURE_TIME_US, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPOSURE_TIME_US, value).apply()

    var flashTorchEnabled: Boolean
        get() = prefs.getBoolean(KEY_FLASH_TORCH, false)
        set(value) = prefs.edit().putBoolean(KEY_FLASH_TORCH, value).apply()

    /**
     * When true, the outgoing stream is capped to the user-selected FPS;
     * extra frames produced by the sensor are dropped before reaching the PC.
     * When false, every frame from the sensor is forwarded (default behaviour).
     */
    var limitFps: Boolean
        get() = prefs.getBoolean(KEY_LIMIT_FPS, false)
        set(value) = prefs.edit().putBoolean(KEY_LIMIT_FPS, value).apply()

    fun streamConfig(): StreamConfig = StreamConfig(
        resolution = streamResolution,
        fps = streamFps,
        orientation = streamOrientation,
        deviceName = deviceName,
        linearZoom = linearZoom,
        jpegQuality = jpegQuality,
        streamFormat = streamFormat,
        manualFocusEnabled = manualFocusEnabled,
        manualAdjustmentsEnabled = manualAdjustmentsEnabled,
        focusDistance = focusDistance,
        iso = iso,
        exposureTimeUs = exposureTimeUs,
        flashTorchEnabled = flashTorchEnabled,
        limitFps = limitFps,
        cameraId = selectedCameraId,
    )

    companion object {
        private const val PREFS_NAME = "android_camera_prefs"
        private const val KEY_CONNECTION_MODE = "connection_mode"
        private const val KEY_CAMERA_SELECTION = "camera_selection"
        private const val KEY_CAMERA_ID = "camera_id"
        private const val KEY_HTTP_PORT = "http_port"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_STREAM_RESOLUTION = "stream_resolution"
        private const val KEY_STREAM_FPS = "stream_fps"
        private const val KEY_STREAM_ORIENTATION = "stream_orientation"
        private const val KEY_LINEAR_ZOOM = "linear_zoom"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_JPEG_QUALITY = "jpeg_quality"
        private const val KEY_FRAME_BATCH_SIZE = "frame_batch_size"
        private const val KEY_STREAM_FORMAT = "stream_format"
        private const val KEY_MANUAL_FOCUS = "manual_focus"
        private const val KEY_MANUAL_FOCUS_TOUCHED = "manual_focus_touched"
        private const val KEY_MANUAL_ADJUSTMENTS = "manual_adjustments"
        private const val KEY_MANUAL_ADJUSTMENTS_TOUCHED = "manual_adjustments_touched"
        private const val KEY_FOCUS_DISTANCE = "focus_distance"
        private const val KEY_ISO = "iso"
        private const val KEY_EXPOSURE_TIME_US = "exposure_time_us"
        private const val KEY_FLASH_TORCH = "flash_torch"
        private const val KEY_LIMIT_FPS = "limit_fps"
        const val DEFAULT_PORT = 2743
    }
}
