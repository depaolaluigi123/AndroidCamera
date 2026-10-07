package com.androidcamera.webcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.camera.view.PreviewView
import com.androidcamera.webcam.model.StreamConfig
import com.androidcamera.webcam.model.StreamFormat
import com.androidcamera.webcam.streaming.FrameBroker
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Camera2 capture path for OEM aux camera IDs that CameraX does not register.
 * Publishes I420 (or JPEG made from it) frames into [frameBroker] and mirrors the preview
 * onto a [TextureView] (PreviewView is CameraX-only).
 *
 * Every capture request — the repeating one and the one-shot autofocus requests — is built
 * by [newRequest] from the complete current state (focus, exposure, frame rate, zoom,
 * metering region, torch), so changing one setting never resets the others: changing the
 * FPS used to switch manual exposure back to auto and turn the torch off, and a tap to
 * focus turned the torch off.
 */
class Camera2StreamSession(
    private val context: Context,
    private val cameraId: String,
    private val frameBroker: FrameBroker,
    initialConfig: StreamConfig,
    private val previewView: PreviewView?,
    /**
     * The TextureView currently used for live preview. Mutable because the
     * host Activity swaps it (embedded card ↔ fullscreen overlay) via
     * [swapTextureView] without recreating the camera device.
     */
    private var textureView: TextureView?
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    /** Written on the main thread, read on the camera thread. */
    @Volatile
    private var streamConfig: StreamConfig = initialConfig
    private val running = AtomicBoolean(false)
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    /**
     * Frames are converted / compressed on their own thread: on the camera thread they
     * delayed the capture callbacks by seconds (a tap-to-focus scan answered ~4 s late).
     */
    private var imageThread: HandlerThread? = null
    private var imageHandler: Handler? = null
    @Volatile
    private var cameraDevice: CameraDevice? = null
    @Volatile
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    /**
     * A small JPEG stream that is configured in the session but never targeted. Without it,
     * on the tested Redmi Note 9 Pro (aux lenses, large YUV streams) turning the torch off
     * intermittently stopped the camera for good ("wait for output buffer return timed
     * out"): with a JPEG stream in the session the camera HAL picks a configuration where
     * that does not happen (0 freezes in 12 torch toggles vs. freezes within 8 without).
     */
    private var idleJpegReader: ImageReader? = null
    @Volatile
    private var previewSurface: Surface? = null
    private val yuvExtractor = YuvMediaImageExtractor()
    private var sensorOrientation = 0
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    /** Digital-zoom crop of the sensor active array; full frame when null. */
    @Volatile
    private var currentCrop: Rect? = null
    /** Last tapped AF/AE region, kept for continuous AF/AE until zoom or the next tap. */
    @Volatile
    private var meteringRegion: MeteringRectangle? = null
    /** A tap-to-focus scan is running (its requests use AF_MODE_AUTO). */
    @Volatile
    private var afScanActive = false
    /** Incremented per tap: callbacks of a superseded scan are ignored. */
    @Volatile
    private var afScanGeneration = 0
    private var lastPreviewSize: Size? = null
    /**
     * Whether this lens has a flash unit. FLASH_MODE is only sent to lenses that have one:
     * some OEM aux lenses without a flash wedge the session when it is toggled.
     */
    private var flashModeSupported = false
    private var afModes = IntArray(0)
    private var maxAfRegions = 0
    private var maxAeRegions = 0
    private var minimumFocusDistance = 0f

    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (manager == null) {
            running.set(false)
            error("CameraManager unavailable")
        }
        startBackgroundThread()
        val chars = manager.getCameraCharacteristics(cameraId)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        lensFacing = chars.get(CameraCharacteristics.LENS_FACING)
            ?: CameraCharacteristics.LENS_FACING_BACK
        flashModeSupported = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: IntArray(0)
        maxAfRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        maxAeRegions = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        minimumFocusDistance = chars.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        currentCrop = cropForLinearZoom(streamConfig.linearZoom)

        // Prefer TextureView preview; hide CameraX PreviewView while Camera2 owns the lens.
        previewView?.post {
            previewView.visibility = View.INVISIBLE
        }
        val initialTextureView = textureView
        initialTextureView?.post {
            initialTextureView.visibility = View.VISIBLE
            applyPreviewTransform(initialTextureView)
        }

        // Brief delay so a previous Camera2 close can finish (OEM aux cameras are picky).
        val openAction = Runnable {
            if (!running.get()) return@Runnable
            val tv = textureView
            when {
                tv?.isAvailable == true -> openCamera(tv.surfaceTexture!!)
                tv != null -> tv.surfaceTextureListener = surfaceListener(tv) { st -> openCamera(st) }
                else -> openCamera(previewTexture = null)
            }
        }
        (backgroundHandler ?: Handler(context.mainLooper)).postDelayed(openAction, 200)
    }

    private fun surfaceListener(view: TextureView, onAvailable: (SurfaceTexture) -> Unit) =
        object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
                if (running.get()) onAvailable(surface)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, w: Int, h: Int) {
                applyPreviewTransform(view)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                previewSurface?.release()
                previewSurface = null
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

    /** New stream settings (FPS, orientation, rotation, JPEG quality...): applied from the next frame. */
    fun updateConfig(streamConfig: StreamConfig) {
        val zoomChanged = streamConfig.linearZoom != this.streamConfig.linearZoom
        this.streamConfig = streamConfig
        if (zoomChanged) {
            currentCrop = cropForLinearZoom(streamConfig.linearZoom)
            meteringRegion = null
        }
        val tv = textureView
        tv?.post { applyPreviewTransform(tv) }
        submitRepeating("update config")
    }

    /**
     * Re-target the live preview onto a different [TextureView] (embedded card ↔
     * fullscreen overlay) without closing the camera: only the capture session is rebuilt
     * with the new preview surface.
     */
    fun swapTextureView(newTextureView: TextureView?) {
        val previousTextureView = textureView
        if (previousTextureView === newTextureView) return
        if (!running.get()) return
        val device = cameraDevice ?: return
        val newView = newTextureView ?: return

        previousTextureView?.post { previousTextureView.visibility = View.GONE }
        newView.post { newView.visibility = View.VISIBLE }

        val resume: (SurfaceTexture) -> Unit = { st -> rebuildPreviewSurface(device, newView, st) }
        if (newView.isAvailable) {
            resume(newView.surfaceTexture!!)
        } else {
            newView.surfaceTextureListener = surfaceListener(newView, resume)
        }
    }

    private fun rebuildPreviewSurface(device: CameraDevice, newView: TextureView, surfaceTexture: SurfaceTexture) {
        val previewSize = CameraCapabilities.previewSize(context, cameraId, streamConfig.aspect)
        lastPreviewSize = previewSize
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        previewSurface = Surface(surfaceTexture)
        newView.post { applyPreviewTransform(newView, previewSize) }
        textureView = newView
        createSession(device, sessionSurfaces())
    }

    fun setManualFocusEnabled(enabled: Boolean) {
        streamConfig = streamConfig.copy(manualFocusEnabled = enabled)
        submitRepeating("manual focus")
    }

    fun setManualAdjustmentsEnabled(enabled: Boolean) {
        streamConfig = streamConfig.copy(manualAdjustmentsEnabled = enabled)
        submitRepeating("manual adjustments")
    }

    /** Normalized manual focus (0 = infinity, 1 = closest focus distance). */
    fun setFocusDistance(distance: Float) {
        streamConfig = streamConfig.copy(focusDistance = distance)
        submitRepeating("focus distance")
    }

    fun setIso(iso: Int) {
        streamConfig = streamConfig.copy(iso = iso)
        submitRepeating("ISO")
    }

    fun setExposureTimeUs(exposureTimeUs: Long) {
        streamConfig = streamConfig.copy(exposureTimeUs = exposureTimeUs)
        submitRepeating("exposure time")
    }

    fun setFlashTorch(enabled: Boolean) {
        streamConfig = streamConfig.copy(flashTorchEnabled = enabled)
        if (!flashModeSupported) {
            Log.i(TAG, "Camera2 flash skipped: lens has no flash unit (camera $cameraId)")
            return
        }
        submitRepeating("torch")
    }

    fun setLinearZoom(linearZoom: Float) {
        streamConfig = streamConfig.copy(linearZoom = linearZoom)
        currentCrop = cropForLinearZoom(linearZoom)
        meteringRegion = null
        submitRepeating("zoom")
    }

    private fun cropForLinearZoom(linearZoom: Float): Rect? {
        if (linearZoom <= 0f) return null
        val chars = manager?.getCameraCharacteristics(cameraId) ?: return null
        val range = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = 1f + (range - 1f) * linearZoom.coerceIn(0f, 1f)
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val centerX = sensor.width() / 2
        val centerY = sensor.height() / 2
        val halfW = (sensor.width() / (2f * zoom)).toInt()
        val halfH = (sensor.height() / (2f * zoom)).toInt()
        return Rect(centerX - halfW, centerY - halfH, centerX + halfW, centerY + halfH)
    }

    /**
     * Tap-to-focus at ([x], [y]) of the preview [view] that received the tap. With manual
     * focus on, the manual distance is simply re-applied. Otherwise an AF scan is run on the
     * tapped region (cancel → start → poll until done → continuous AF on that region), with
     * every request carrying the complete state, torch included. On fixed-focus lenses only
     * the exposure region is set.
     */
    fun focusAt(view: View, x: Float, y: Float) {
        val session = captureSession ?: return
        if (cameraDevice == null) return
        if (streamConfig.manualFocusEnabled) {
            submitRepeating("re-apply manual focus")
            return
        }
        val textureView = view as? TextureView ?: return
        meteringRegion = meteringRegionForTap(x, y, view.width, view.height, textureView.getTransform(null))
            ?: return
        if (!supportsAutoFocusScan()) {
            submitRepeating("metering region")
            return
        }
        try {
            afScanActive = true
            val generation = ++afScanGeneration
            // 1) Cancel any AF in progress (one request), 2) hold AF_MODE_AUTO on the tapped
            //    region with the trigger idle, 3) start the scan with ONE trigger. A repeating
            //    request carrying TRIGGER_START restarted the scan on every frame, so it never
            //    settled.
            val cancel = newRequest()?.apply {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            }?.build() ?: return
            val hold = newRequest()?.apply {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }?.build() ?: return
            val trigger = newRequest()?.apply {
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            }?.build() ?: return
            val startedAt = android.os.SystemClock.elapsedRealtime()
            val poll = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                    if (generation != afScanGeneration || !afScanActive) return
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    val done = afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                        afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                    if (done || android.os.SystemClock.elapsedRealtime() - startedAt > AF_SCAN_TIMEOUT_MS) {
                        resumeContinuousAf()
                    }
                }
            }
            session.capture(cancel, null, backgroundHandler)
            session.setRepeatingRequest(hold, poll, backgroundHandler)
            session.capture(trigger, poll, backgroundHandler)
        } catch (e: Exception) {
            afScanActive = false
            Log.w(TAG, "Camera2 focus failed: ${e.message}")
        }
    }

    private fun supportsAutoFocusScan(): Boolean =
        maxAfRegions > 0 && afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) && minimumFocusDistance > 0f

    /** Back to continuous AF on the tapped region, with the complete state. */
    private fun resumeContinuousAf() {
        afScanActive = false
        val session = captureSession ?: return
        runCatching {
            val resume = newRequest()?.apply {
                // AF_TRIGGER=IDLE releases the START latch of the previous request.
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            } ?: return
            session.setRepeatingRequest(resume.build(), null, backgroundHandler)
        }.onFailure { Log.w(TAG, "Camera2 AF resume failed: ${it.message}") }
    }

    /**
     * Map a tap on the preview to a metering rectangle in active-array coordinates: back
     * through the TextureView transform ([viewMatrix]), then through the camera's own
     * preview transform (sensor rotation, plus horizontal mirroring for front cameras), into
     * the part of the zoom crop the preview stream shows (its own shape: the middle band
     * for 16:9). A 180° flip of the preview wrapper is already undone by Android, which
     * delivers the tap in the view's own coordinates.
     */
    private fun meteringRegionForTap(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
        viewMatrix: Matrix
    ): MeteringRectangle? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        val chars = runCatching { manager?.getCameraCharacteristics(cameraId) }.getOrNull() ?: return null
        val activeArray = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val previewSize = lastPreviewSize ?: return null
        val inverse = Matrix()
        if (!viewMatrix.invert(inverse)) return null
        val point = floatArrayOf(x, y)
        inverse.mapPoints(point)
        val mirrored = lensFacing == CameraCharacteristics.LENS_FACING_FRONT
        val rotation = if (mirrored) (360 - sensorOrientation) % 360 else sensorOrientation
        val toDisplay = Matrix().apply {
            if (mirrored) postScale(-1f, 1f, 0.5f, 0.5f)
            postRotate(rotation.toFloat(), 0.5f, 0.5f)
        }
        val toSensor = Matrix()
        if (!toDisplay.invert(toSensor)) return null
        val normalized = floatArrayOf(
            (point[0] / viewWidth).coerceIn(0f, 1f),
            (point[1] / viewHeight).coerceIn(0f, 1f)
        )
        toSensor.mapPoints(normalized)
        val crop = currentCrop ?: Rect(0, 0, activeArray.width(), activeArray.height())
        val visible = visibleCrop(crop, previewSize.width.toFloat() / previewSize.height)
        val cx = visible.left + normalized[0].coerceIn(0f, 1f) * visible.width()
        val cy = visible.top + normalized[1].coerceIn(0f, 1f) * visible.height()
        val half = maxOf(crop.width(), crop.height()) * REGION_FRACTION / 2f
        val rect = RectF(cx - half, cy - half, cx + half, cy + half)
        val bounds = RectF(0f, 0f, activeArray.width().toFloat() - 1, activeArray.height().toFloat() - 1)
        if (!rect.intersect(bounds)) return null
        Log.d(TAG, "Tap ($x, $y) on $cameraId -> sensor region $rect")
        return MeteringRectangle(
            rect.left.toInt(), rect.top.toInt(),
            rect.width().toInt().coerceAtLeast(1), rect.height().toInt().coerceAtLeast(1),
            MeteringRectangle.METERING_WEIGHT_MAX - 1
        )
    }

    /** Largest centred rectangle of [crop] with the aspect ratio [streamAspect] (width / height). */
    private fun visibleCrop(crop: Rect, streamAspect: Float): RectF {
        val cropAspect = crop.width().toFloat() / crop.height()
        return if (streamAspect > cropAspect) {
            val height = crop.width() / streamAspect
            RectF(crop.left.toFloat(), crop.exactCenterY() - height / 2f, crop.right.toFloat(), crop.exactCenterY() + height / 2f)
        } else {
            val width = crop.height() * streamAspect
            RectF(crop.exactCenterX() - width / 2f, crop.top.toFloat(), crop.exactCenterX() + width / 2f, crop.bottom.toFloat())
        }
    }

    fun close() {
        running.set(false)
        val session = captureSession
        val device = cameraDevice
        val reader = imageReader
        val jpegReader = idleJpegReader
        val surface = previewSurface
        captureSession = null
        cameraDevice = null
        imageReader = null
        idleJpegReader = null
        previewSurface = null
        currentCrop = null
        meteringRegion = null
        lastPreviewSize = null
        runCatching { session?.stopRepeating() }
        runCatching { session?.abortCaptures() }
        runCatching { session?.close() }
        runCatching { device?.close() }
        runCatching { reader?.close() }
        runCatching { jpegReader?.close() }
        runCatching { surface?.release() }
        stopBackgroundThread()
        previewView?.post {
            previewView.visibility = View.VISIBLE
        }
        val tv = textureView
        tv?.post {
            tv.visibility = View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(previewTexture: SurfaceTexture?) {
        val handler = backgroundHandler ?: return
        val mgr = manager ?: return
        Log.i(TAG, "Camera $cameraId flashModeSupported=$flashModeSupported minFocus=$minimumFocusDistance")

        val analysisSize = analysisSize()
        imageReader = ImageReader.newInstance(
            analysisSize.width,
            analysisSize.height,
            ImageFormat.YUV_420_888,
            2
        ).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                handleImage(image)
            }, imageHandler ?: handler)
        }

        idleJpegReader = smallestJpegSize()?.let { size ->
            ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1).also { reader ->
                reader.setOnImageAvailableListener({ r -> r.acquireLatestImage()?.close() }, handler)
            }
        }

        if (previewTexture != null) {
            val previewSize = CameraCapabilities.previewSize(context, cameraId, streamConfig.aspect)
            lastPreviewSize = previewSize
            previewTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(previewTexture)
            val tv = textureView
            tv?.post { applyPreviewTransform(tv, previewSize) }
        }
        Log.i(TAG, "Camera $cameraId analysis ${analysisSize.width}x${analysisSize.height} preview=$lastPreviewSize")

        mgr.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!running.get()) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createSession(camera, sessionSurfaces())
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    cameraDevice = null
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera2 open error=$error id=$cameraId")
                    camera.close()
                    cameraDevice = null
                }
            },
            handler
        )
    }

    private fun sessionSurfaces(): List<Surface> =
        listOfNotNull(imageReader?.surface, idleJpegReader?.surface, previewSurface)

    private fun smallestJpegSize(): Size? =
        manager?.getCameraCharacteristics(cameraId)
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG)?.minByOrNull { it.width * it.height }

    /** The selected resolution when the camera delivers it, else the closest one of the same shape. */
    private fun analysisSize(): Size {
        val res = streamConfig.resolution
        if (CameraCapabilities.isResolutionSupported(context, cameraId, res)) {
            return Size(res.landscapeWidth, res.landscapeHeight)
        }
        val sizes = manager?.getCameraCharacteristics(cameraId)
            ?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.YUV_420_888)?.toList().orEmpty()
        val sameShape = sizes.filter { res.aspect.matches(it.width, it.height) }.ifEmpty { sizes }
        return sameShape.minByOrNull { abs(it.width * it.height - res.pixels) } ?: Size(1280, 720)
    }

    private fun createSession(camera: CameraDevice, surfaces: List<Surface>) {
        runCatching {
            camera.createCaptureSession(
                surfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running.get()) {
                            session.close()
                            return
                        }
                        captureSession = session
                        afScanActive = false
                        submitRepeating("session start")
                        Log.i(
                            TAG,
                            "Camera2 session started id=$cameraId surfaces=${surfaces.size} " +
                                "fps=${streamConfig.fps}"
                        )
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera2 configure failed id=$cameraId")
                    }
                },
                backgroundHandler
            )
        }.onFailure {
            Log.e(TAG, "createCaptureSession failed id=$cameraId: ${it.message}", it)
        }
    }

    /** Push the repeating request with the complete current state. */
    private fun submitRepeating(reason: String) {
        val session = captureSession ?: return
        // A running tap-to-focus scan re-applies the state itself when it ends.
        if (afScanActive) return
        val request = newRequest()?.build() ?: return
        try {
            session.setRepeatingRequest(request, null, backgroundHandler)
        } catch (e: Exception) {
            // Some aux lenses need a single-shot kick before the repeating request re-arms.
            Log.w(TAG, "setRepeatingRequest failed ($reason), kicking with capture(): ${e.message}")
            runCatching { session.capture(request, null, backgroundHandler) }
            runCatching { session.setRepeatingRequest(request, null, backgroundHandler) }
        }
    }

    /** A request targeting the analysis reader and the preview, carrying the complete state. */
    private fun newRequest(): CaptureRequest.Builder? {
        val device = cameraDevice ?: return null
        val builder = createRequestBuilder(device) ?: return null
        imageReader?.surface?.let { builder.addTarget(it) }
        previewSurface?.let { builder.addTarget(it) }
        applyState(builder)
        return builder
    }

    /**
     * The complete state: AF (continuous video, or manual distance across the real lens
     * range), AE (auto, or manual ISO / exposure time), frame rate, zoom crop, metering
     * region, and the torch LAST — the OEM aux-lens quirk worked around earlier dropped the
     * options set after FLASH_MODE in the same request.
     */
    private fun applyState(builder: CaptureRequest.Builder) {
        val cfg = streamConfig
        val manualFocus = cfg.manualFocusEnabled && minimumFocusDistance > 0f
        if (manualFocus) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, cfg.focusDistance.coerceIn(0f, 1f) * minimumFocusDistance)
        } else if (afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)) {
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        }
        if (cfg.manualAdjustmentsEnabled) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            if (cfg.iso > 0) builder.set(CaptureRequest.SENSOR_SENSITIVITY, cfg.iso)
            if (cfg.exposureTimeUs > 0L) builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, cfg.exposureTimeUs * 1000L)
            // With AE off the frame rate comes from the frame duration.
            if (cfg.fps > 0) builder.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / cfg.fps)
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        CameraCapabilities.fpsRange(context, cameraId, cfg.fps)?.let {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }
        currentCrop?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, it) }
        meteringRegion?.let { region ->
            if (maxAfRegions > 0 && !manualFocus && minimumFocusDistance > 0f) {
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
            }
            if (maxAeRegions > 0 && !cfg.manualAdjustmentsEnabled) {
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
            }
        }
        if (flashModeSupported) {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (cfg.flashTorchEnabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
        }
    }

    /** Some OEM aux cameras reject TEMPLATE_PREVIEW; try safer fallbacks. */
    private var workingTemplate: Int? = null

    private fun createRequestBuilder(camera: CameraDevice): CaptureRequest.Builder? {
        workingTemplate?.let { template ->
            runCatching { return camera.createCaptureRequest(template) }
        }
        // RECORD first: Xiaomi aux lenses often reject TEMPLATE_PREVIEW (-38).
        val templates = intArrayOf(
            CameraDevice.TEMPLATE_RECORD,
            CameraDevice.TEMPLATE_PREVIEW,
            CameraDevice.TEMPLATE_STILL_CAPTURE,
            CameraDevice.TEMPLATE_MANUAL
        )
        for (template in templates) {
            val builder = runCatching { camera.createCaptureRequest(template) }
                .onFailure { Log.w(TAG, "template=$template failed for $cameraId: ${it.message}") }
                .getOrNull()
            if (builder != null) {
                Log.i(TAG, "Using capture template=$template for camera $cameraId")
                workingTemplate = template
                return builder
            }
        }
        return null
    }

    private fun handleImage(image: Image) {
        try {
            if (!running.get()) return
            val cfg = streamConfig
            val rotation = cfg.orientation.bufferRotationDegrees(
                sensorOrientation,
                frontFacing = lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
            )
            // Independent 180° flip on top of the orientation-driven rotation.
            val finalRotation = if (cfg.rotate180) (rotation + 180) % 360 else rotation
            yuvExtractor.processToOutput(
                image,
                cfg.outputWidth,
                cfg.outputHeight,
                finalRotation
            )?.let { (w, h, i420) ->
                if (cfg.streamFormat == StreamFormat.JPEG) {
                    JpegEncoder.encode(i420, w, h, cfg.jpegQuality.quality)?.let {
                        frameBroker.publish(
                            format = StreamFormat.JPEG,
                            width = w,
                            height = h,
                            rotationDegrees = 0,
                            payload = it
                        )
                    }
                } else {
                    frameBroker.publish(
                        format = StreamFormat.YUV,
                        width = w,
                        height = h,
                        rotationDegrees = 0,
                        payload = i420
                    )
                }
            }
        } finally {
            image.close()
        }
    }

    /**
     * Called externally (via [CameraStreamController]) when the rotate180 toggle changes
     * mid-stream so the TextureView preview re-applies its transform.
     */
    fun forceReapplyPreviewTransform() {
        val tv = textureView
        tv?.post { applyPreviewTransform(tv) }
    }

    /**
     * Fit the (landscape) preview buffer inside the portrait view, centred. The 180°
     * rotation toggle is applied on the wrapper container (see MainActivity), not here.
     */
    private fun applyPreviewTransform(view: TextureView, previewSize: Size? = null) {
        val size = previewSize ?: lastPreviewSize ?: return
        if (view.width == 0 || view.height == 0) return
        val viewW = view.width.toFloat()
        val viewH = view.height.toFloat()
        val previewW = size.height.toFloat() // sensor is landscape; view is portrait
        val previewH = size.width.toFloat()
        val scale = minOf(viewW / previewW, viewH / previewH)
        val scaledW = previewW * scale
        val scaledH = previewH * scale
        val matrix = Matrix()
        matrix.setScale(scaledW / viewW, scaledH / viewH)
        matrix.postTranslate((viewW - scaledW) / 2f, (viewH - scaledH) / 2f)
        view.setTransform(matrix)
    }

    private fun startBackgroundThread() {
        val thread = HandlerThread("Camera2Stream-$cameraId").also { it.start() }
        backgroundThread = thread
        backgroundHandler = Handler(thread.looper)
        val frames = HandlerThread("Camera2Frames-$cameraId").also { it.start() }
        imageThread = frames
        imageHandler = Handler(frames.looper)
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        imageThread?.quitSafely()
        runCatching { backgroundThread?.join(500) }
        runCatching { imageThread?.join(500) }
        backgroundThread = null
        backgroundHandler = null
        imageThread = null
        imageHandler = null
    }

    companion object {
        private const val TAG = "Camera2StreamSession"
        /** Side of the tap metering region, as a fraction of the crop's longer side. */
        private const val REGION_FRACTION = 0.12f
        /** Give up waiting for the AF lock after this long and resume continuous AF. */
        private const val AF_SCAN_TIMEOUT_MS = 3000L
    }
}
