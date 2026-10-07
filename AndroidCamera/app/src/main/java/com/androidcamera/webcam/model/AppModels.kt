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

/** Shape of the stream (and of the preview, so it shows what is streamed). */
enum class AspectRatio(private val ratio: Double) {
    RATIO_4_3(4.0 / 3.0),
    RATIO_16_9(16.0 / 9.0);

    /** True when [width] x [height] (either way round) has this shape, 1% tolerance. */
    fun matches(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val r = maxOf(width, height).toDouble() / minOf(width, height)
        return kotlin.math.abs(r - ratio) <= ratio * 0.01
    }
}

/**
 * Stream resolutions, landscape (portrait orientations swap the dimensions, e.g.
 * 1280×960 → 960×1280), 4:3 and 16:9 up to 4K. The spinner lists the ones of the selected
 * shape that the selected camera supports. The entry name is what the preferences store;
 * the names used by earlier versions are mapped in [fromName].
 */
enum class StreamResolution(val landscapeWidth: Int, val landscapeHeight: Int, val aspect: AspectRatio) {
    R640X480(640, 480, AspectRatio.RATIO_4_3),
    R768X576(768, 576, AspectRatio.RATIO_4_3),
    R800X600(800, 600, AspectRatio.RATIO_4_3),
    R1024X768(1024, 768, AspectRatio.RATIO_4_3),
    R1280X960(1280, 960, AspectRatio.RATIO_4_3),
    R1440X1080(1440, 1080, AspectRatio.RATIO_4_3),
    R1600X1200(1600, 1200, AspectRatio.RATIO_4_3),
    R1920X1440(1920, 1440, AspectRatio.RATIO_4_3),
    R2048X1536(2048, 1536, AspectRatio.RATIO_4_3),
    R2304X1728(2304, 1728, AspectRatio.RATIO_4_3),
    R2560X1920(2560, 1920, AspectRatio.RATIO_4_3),
    R2592X1944(2592, 1944, AspectRatio.RATIO_4_3),
    R2880X2160(2880, 2160, AspectRatio.RATIO_4_3),
    R3200X2400(3200, 2400, AspectRatio.RATIO_4_3),
    R3264X2448(3264, 2448, AspectRatio.RATIO_4_3),
    R3840X2880(3840, 2880, AspectRatio.RATIO_4_3),
    R4000X3000(4000, 3000, AspectRatio.RATIO_4_3),
    R4032X3024(4032, 3024, AspectRatio.RATIO_4_3),

    R640X360(640, 360, AspectRatio.RATIO_16_9),
    R854X480(854, 480, AspectRatio.RATIO_16_9),
    R960X540(960, 540, AspectRatio.RATIO_16_9),
    R1024X576(1024, 576, AspectRatio.RATIO_16_9),
    R1280X720(1280, 720, AspectRatio.RATIO_16_9),
    R1600X900(1600, 900, AspectRatio.RATIO_16_9),
    R1920X1080(1920, 1080, AspectRatio.RATIO_16_9),
    R2560X1440(2560, 1440, AspectRatio.RATIO_16_9),
    R2688X1512(2688, 1512, AspectRatio.RATIO_16_9),
    R3200X1800(3200, 1800, AspectRatio.RATIO_16_9),
    R3840X2160(3840, 2160, AspectRatio.RATIO_16_9);

    val pixels: Int get() = landscapeWidth * landscapeHeight

    fun widthFor(orientation: StreamOrientation): Int =
        if (orientation.isPortrait) landscapeHeight else landscapeWidth

    fun heightFor(orientation: StreamOrientation): Int =
        if (orientation.isPortrait) landscapeWidth else landscapeHeight

    fun labelFor(orientation: StreamOrientation): String =
        "${widthFor(orientation)}×${heightFor(orientation)}"

    /**
     * The resolution of [shape] corresponding to this one: the same width when it exists
     * (1280×960 ↔ 1280×720), otherwise the closest width, then the closest pixel count.
     */
    fun counterpart(shape: AspectRatio): StreamResolution =
        if (aspect == shape) this
        else of(shape).minWith(
            compareBy({ kotlin.math.abs(it.landscapeWidth - landscapeWidth) }, { kotlin.math.abs(it.pixels - pixels) })
        )

    companion object {
        val DEFAULT = R640X480

        fun of(shape: AspectRatio): List<StreamResolution> = entries.filter { it.aspect == shape }

        /** Saved name → resolution; earlier versions saved "RES_480P"-style names (all 4:3). */
        fun fromName(name: String?): StreamResolution? =
            entries.firstOrNull { it.name == name } ?: when (name) {
                "RES_480P" -> R640X480
                "RES_576P" -> R768X576
                "RES_768P" -> R1024X768
                "RES_960P" -> R1280X960
                "RES_1200P" -> R1600X1200
                "RES_1440P" -> R1920X1440
                "RES_1920P" -> R2560X1920
                "RES_2400P" -> R3200X2400
                "RES_2880P" -> R3840X2880
                else -> null
            }
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
    /** Shape of the stream (the "16:9" checkbox): picks which resolutions are offered. */
    val aspect: AspectRatio = AspectRatio.RATIO_4_3,
    val resolution: StreamResolution = StreamResolution.DEFAULT,
    /** Frames per second; the spinner offers only the rates the selected camera can hold. */
    val fps: Int = 30,
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
    /**
     * Normalized manual focus 0f..1f across the lens range: 0 = infinity, 1 = the closest
     * focus distance the lens supports (see LENS_INFO_MINIMUM_FOCUS_DISTANCE).
     */
    val focusDistance: Float = 0f,
    /** ISO sensitivity (0 = auto). */
    val iso: Int = 0,
    /** Exposure time in microseconds (0 = auto). */
    val exposureTimeUs: Long = 0,
    /** Whether the rear flash LED is on as a torch. */
    val flashTorchEnabled: Boolean = false,
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
