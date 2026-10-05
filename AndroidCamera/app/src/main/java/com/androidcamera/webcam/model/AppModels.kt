package com.androidcamera.webcam.model

enum class ConnectionMode {
    /**
     * USB debugging only: binary ACUS stream on localhost, exposed on the PC
     * by the AndroidCameraUsbDriver via adb forward → v4l2loopback webcam.
     */
    USB_DEVICE,
    /** USB tethering (RNDIS) + HTTP YUV stream. */
    USB,
    /** Wi‑Fi / LAN IP + HTTP YUV stream. */
    IP
}

enum class CameraFacing {
    BACK,
    FRONT,
    EXTERNAL,
    UNKNOWN
}

/**
 * One physical/logical camera exposed by the device Camera2 API.
 * [indexAmongFacing] is 1-based among cameras that share the same [facing].
 */
data class AvailableCamera(
    val id: String,
    val facing: CameraFacing,
    val indexAmongFacing: Int,
    val focalLengthMm: Float? = null
)

enum class AppThemeMode {
    LIGHT,
    DARK
}

enum class AppLanguage(val code: String) {
    ENGLISH("en"),
    ITALIAN("it");

    companion object {
        fun fromCode(code: String): AppLanguage =
            entries.firstOrNull { it.code == code } ?: ENGLISH
    }
}

/** Transmission format – YUV (raw I420) or JPEG (compressed). */
enum class StreamFormat {
    YUV,
    JPEG
}

/**
 * Base landscape resolutions (4:3 aspect ratio). Portrait orientations use swapped dimensions
 * (e.g. 1280×960 → 960×1280).
 */
enum class StreamResolution(val landscapeWidth: Int, val landscapeHeight: Int) {
    RES_480P(640, 480),
    RES_576P(768, 576),
    RES_768P(1024, 768),
    RES_960P(1280, 960),
    RES_1200P(1600, 1200),
    RES_1440P(1920, 1440),
    RES_1920P(2560, 1920),
    RES_2400P(3200, 2400),
    RES_2880P(3840, 2880);

    fun widthFor(orientation: StreamOrientation): Int =
        if (orientation.isPortrait) landscapeHeight else landscapeWidth

    fun heightFor(orientation: StreamOrientation): Int =
        if (orientation.isPortrait) landscapeWidth else landscapeHeight

    fun labelFor(orientation: StreamOrientation): String =
        "${widthFor(orientation)}×${heightFor(orientation)}"

    companion object {
        fun fromName(name: String): StreamResolution =
            entries.firstOrNull { it.name == name } ?: RES_960P
    }
}

enum class StreamFps(val fps: Int) {
    FPS_15(15),
    FPS_24(24),
    FPS_25(25),
    FPS_30(30),
    FPS_48(48),
    FPS_50(50),
    FPS_60(60),
    FPS_75(75),
    FPS_90(90),
    FPS_120(120),
    FPS_144(144),
    FPS_160(160),
    FPS_180(180),
    FPS_200(200),
    FPS_240(240),
    FPS_300(300);

    companion object {
        fun fromInt(value: Int): StreamFps =
            entries.firstOrNull { it.fps == value } ?: FPS_30
    }
}

/**
 * JPEG quality levels for compressed streaming.
 * Higher quality = larger frames = more bandwidth = potentially lower framerate on WiFi.
 * Lower quality = smaller frames = less bandwidth = potentially higher framerate on WiFi.
 */
enum class JpegQuality(val quality: Int, val label: String) {
    QUALITY_10(10, "10% (Min size, max fps)"),
    QUALITY_20(20, "20%"),
    QUALITY_30(30, "30%"),
    QUALITY_40(40, "40%"),
    QUALITY_50(50, "50% (Balanced)"),
    QUALITY_60(60, "60%"),
    QUALITY_70(70, "70%"),
    QUALITY_75(75, "75%"),
    QUALITY_80(80, "80%"),
    QUALITY_85(85, "85%"),
    QUALITY_90(90, "90% (High quality)"),
    QUALITY_95(95, "95% (Near lossless)"),
    QUALITY_100(100, "100% (Max quality)");

    companion object {
        fun fromInt(value: Int): JpegQuality =
            entries.firstOrNull { it.quality == value } ?: QUALITY_75
    }
}

/**
 * How to orient frames relative to the device natural (portrait) upright.
 *
 * Hold the phone to match the mode:
 * - [PORTRAIT] / [PORTRAIT_INVERTED]: phone upright (vertical)
 * - [LANDSCAPE] / [LANDSCAPE_INVERTED]: phone on its side (horizontal)
 *
 * "Inverted" is a digital 180° flip while keeping that same physical hold —
 * you do not need to flip the phone upside-down.
 */
enum class StreamOrientation(private val holdExtraDegrees: Int) {
    /** Phone upright → vertical output (e.g. 960×1280). */
    PORTRAIT(0),
    /** Phone on its side → horizontal output (e.g. 1280×960), full landscape FOV. */
    LANDSCAPE(90),
    /** Phone upright, digitally upside-down. */
    PORTRAIT_INVERTED(180),
    /** Phone on its side, digitally upside-down. */
    LANDSCAPE_INVERTED(270);

    val isPortrait: Boolean
        get() = this == PORTRAIT || this == PORTRAIT_INVERTED

    val isInverted: Boolean
        get() = this == PORTRAIT_INVERTED || this == LANDSCAPE_INVERTED

    /**
     * Rotation for the sensor ImageProxy buffer.
     * Landscape modes add 90° so the stream matches a phone held horizontally
     * (not a top/bottom crop of a vertical shot).
     *
     * Front-facing cameras ship naturally mirrored, so the user expects the
     * "Vertical" label to emit the image that the back camera would emit
     * under "Vertical inverted", and similarly for landscape. We add a 180°
     * offset for [frontFacing] so the label displayed in the UI always
     * matches the stream direction the user is holding the phone in.
     *
     * 
     * 
     * 
     * REMOVED (currently does not apply the rotation; the code
     * was commented out because it might be useful in the future):
     *      The final extra 180° flip applies when BOTH conditions are true:
     *      the active camera is front-facing AND the orientation is one of the
     *      horizontal ones ([LANDSCAPE] / [LANDSCAPE_INVERTED]). This is the
     *      user-requested rule "rotate the front-camera stream by 180° when held
     *      horizontally" (whether right-side up or upside down), so the outgoing
     *      video always matches what the user sees on the phone screen.
     */
    fun bufferRotationDegrees(sensorRotationDegrees: Int, frontFacing: Boolean = false): Int {
        val toNaturalUpright = ((sensorRotationDegrees % 360) + 360) % 360
            //val frontOffset = if (frontFacing) 180 else 0
            // Extra 180° rotation: fires only when both the active camera is
            // front-facing AND the user picked a horizontal orientation
            // (LANDSCAPE or LANDSCAPE_INVERTED). Without it the front camera
            // would deliver a horizontally flipped stream to the PC because
            // front-facing sensors ship mirrored.
        val landscapeFlip = if (frontFacing && (this == LANDSCAPE || this == LANDSCAPE_INVERTED)) 180 else 0
            //return (toNaturalUpright + holdExtraDegrees + frontOffset + landscapeFlip) % 360
        return (toNaturalUpright + holdExtraDegrees + landscapeFlip) % 360
    }
}

data class StreamConfig(
    val format: StreamFormat = StreamFormat.YUV,
    val resolution: StreamResolution = StreamResolution.RES_480P,
    val fps: StreamFps = StreamFps.FPS_60,
    val orientation: StreamOrientation = StreamOrientation.PORTRAIT,
    val deviceName: String = DEFAULT_DEVICE_NAME,
    /** CameraX linear zoom in 0f..1f (0 = min zoom, 1 = max). */
    val linearZoom: Float = 0f,
    /** JPEG quality for compressed streaming (10-100). Only used when
     *  [streamFormat] is JPEG; ignored for raw YUV. */
    val jpegQuality: JpegQuality = JpegQuality.QUALITY_40,
    /** Streaming format the user picked in the app: YUV (raw I420) or
     *  JPEG (compressed). The legacy [format] field above predates this
     *  toggle and is no longer read anywhere — keep it for binary
     *  compatibility with older callers but the active flag is this one. */
    val streamFormat: StreamFormat = StreamFormat.JPEG,
    /** When true, manual focus/exposure controls are active (AF and AE locked to manual). */
    val manualFocusEnabled: Boolean = false,
    /** When true, manual ISO/exposure-time overrides are active (AE locked). */
    val manualAdjustmentsEnabled: Boolean = false,
    /** Normalized focus distance 0f..1f (0 = closest, 1 = farthest). */
    val focusDistance: Float = 0f,
    /** ISO sensitivity (0 = auto). */
    val iso: Int = 0,
    /** Exposure time in microseconds (0 = auto). */
    val exposureTimeUs: Long = 0,
    /** Whether the rear flash LED is on as a torch. */
    val flashTorchEnabled: Boolean = false,
    /**
     * When true, the outgoing stream is capped to [fps]: any extra frames
     * the camera sensor produces on top of [fps] are dropped before being
     * forwarded to the PC. When false (default) every frame produced by
     * the sensor is forwarded, even when the sensor runs faster than the
     * user-selected [fps] (e.g. user picks 60fps and the sensor delivers 64).
     */
    val limitFps: Boolean = false,
    /**
     * When true, every outgoing frame is rotated an additional 180° on top of
     * the rotation already implied by [orientation]. This is independent from
     * the orientation spinner (which is locked while the service is running)
     * so the user can flip the video upside-down mid-stream without
     * restarting the camera. Lives in the StreamConfig (not in preferences)
     * because it is a live, transient setting that the user typically wants
     * to reset between sessions.
     */
    val rotate180: Boolean = false,
    /** Camera2 camera id currently selected by the user. `null` means
     *  "let the runtime pick a default" (e.g. first rear camera).
     *  Stored on the StreamConfig so a single object carries every
     *  piece of webcam state — main UI and fullscreen overlay both
     *  observe this single source of truth. */
    val cameraId: String? = null,
) {
    val outputWidth: Int get() = resolution.widthFor(orientation)
    val outputHeight: Int get() = resolution.heightFor(orientation)

    companion object {
        const val DEFAULT_DEVICE_NAME = "AndroidCamera"
    }
}

data class StreamEndpoints(
    val host: String,
    val port: Int,
    val streamUrl: String?,
    val format: StreamFormat = StreamFormat.YUV
)

data class ServiceUiState(
    val isRunning: Boolean = false,
    val camerasInUseLabel: String = "",
    val endpoints: StreamEndpoints? = null,
    val errorMessage: String? = null
)
