package com.androidcamera.webcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Camera2 capture path for OEM aux camera IDs that CameraX does not register.
 * Publishes I420 frames into [frameBroker] and mirrors preview onto a [TextureView]
 * (PreviewView is CameraX-only).
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
     * [swapTextureView] without recreating the capture session.
     */
    private var textureView: TextureView?
) {
    private val manager = context.getSystemService(CameraManager::class.java)
    /** Stream configuration. Written on the main thread (via [updateConfig])
     *  and read on the background ImageReader thread. Marked @Volatile so
     *  the reader sees the updated value without stale-cache issues. */
    @Volatile
    private var _streamConfig: StreamConfig = initialConfig
    /** Public read-only accessor so the rest of the class can use
     *  ``streamConfig`` as before; the backing field is @Volatile. */
    private var streamConfig: StreamConfig
        get() = _streamConfig
        set(v) { _streamConfig = v }
    private val running = AtomicBoolean(false)
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    /** Second ImageReader for JPEG-stream mode: the camera ISP encodes frames directly as JPEG,
     * giving us HW compression for free on every Android device (no MediaCodec dependency). */
    private var jpegImageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private val yuvExtractor = YuvMediaImageExtractor()
    private val lastPublishNs = AtomicLong(0L)
    /**
     * Publish-side gate for the "Limit FPS" toggle. When [StreamConfig.limitFps]
     * is true, frames arriving before the configured inter-frame interval
     * has elapsed since the last published frame are dropped before they
     * reach the [frameBroker]. Reset to 0 by [updateConfig] (when the user
     * changes FPS or toggles the limit) and by [setLimitFps] so the new
     * setting takes effect from the very next frame.
     */
    private val nextPublishAllowedNs = AtomicLong(0L)
    private var sensorOrientation = 0
    private var lensFacing = CameraCharacteristics.LENS_FACING_BACK
    /** Last digital-zoom crop applied to the sensor active array; full frame when null. */
    private var currentCrop: android.graphics.Rect? = null
    private var lastPreviewSize: Size? = null
    /**
     * Whether this lens accepts FLASH_MODE_TORCH / FLASH_MODE_OFF on its
     * capture requests. Some OEM aux (ultrawide / tele) lenses advertise no
     * flash unit but still parse the option, then deadlock the capture
     * session when it is toggled. We probe via
     * [CameraCharacteristics.FLASH_INFO_AVAILABLE] at open time and refuse
     * to push FLASH_MODE when the lens does not support it.
     */
    private var flashModeSupported = false
    /** Whether the active preview surface is currently bound to the session.
     *  Used by [setFlashTorch] to decide whether to issue a kick capture. */
    private var captureSessionConfigured = false

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

        // Prefer TextureView preview; hide CameraX PreviewView while Camera2 owns the lens.
        previewView?.post {
            previewView.visibility = android.view.View.INVISIBLE
        }
        // Snapshot textureView into a local val so the smart cast below
        // survives the post() — the property is `var` (we re-target it
        // in swapTextureView) and Kotlin refuses to smart-cast through
        // a mutable property across a closure boundary.
        val initialTextureView = textureView
        initialTextureView?.post {
            initialTextureView.visibility = android.view.View.VISIBLE
            applyPreviewTransform(initialTextureView)
        }

        // Brief delay so a previous Camera2 close can finish (OEM aux cameras are picky).
        val openAction = Runnable {
            if (!running.get()) return@Runnable
            val tv = textureView
            when {
                tv?.isAvailable == true -> openCamera(tv.surfaceTexture!!)
                tv != null -> {
                    tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surface: SurfaceTexture,
                            w: Int,
                            h: Int
                        ) {
                            if (running.get()) openCamera(surface)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            w: Int,
                            h: Int
                        ) {
                            applyPreviewTransform(tv)
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            previewSurface?.release()
                            previewSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                }
                else -> openCamera(previewTexture = null)
            }
        }
        (backgroundHandler ?: Handler(context.mainLooper)).postDelayed(openAction, 200)
    }

    fun updateConfig(streamConfig: StreamConfig) {
        this.streamConfig = streamConfig
        // Snapshot textureView into a local val for the post() closure
        // (the property is `var`).
        val tv = textureView
        tv?.post { applyPreviewTransform(tv) }
        // Reset the last publish timestamp when FPS changes so the next frame
        // is processed without stale comparison.
        lastPublishNs.set(0L)
        // Also reset the limit-FPS gate so a tighter / looser limit
        // (or a toggled checkbox) takes effect from the very next frame.
        nextPublishAllowedNs.set(0L)
        updateCaptureRequestFps()
    }

    /**
     * Toggles the outgoing-stream FPS cap. See
     * [com.androidcamera.webcam.camera.CameraStreamController.setLimitFps]
     * for the semantic — this is the Camera2 fallback path that mirrors it.
     */
    fun setLimitFps(enabled: Boolean) {
        streamConfig = streamConfig.copy(limitFps = enabled)
        nextPublishAllowedNs.set(0L)
    }

    /**
     * Re-target the live preview onto a different [TextureView] without
     * tearing down the capture session or the analysis pipeline.
     *
     * Required when the host Activity swaps between the embedded preview
     * card and the fullscreen overlay: the [PreviewView] / [TextureView]
     * the user actually sees changes, but the YUV/JPEG stream must keep
     * flowing and the preview must keep updating on the new surface.
     *
     * No-op when [newTextureView] is the same instance we're already
     * rendering to, and when the session has not been started yet
     * (caller is expected to pass the new view to [start] in that case).
     *
     * Implementation: hide the old view, show the new view, point the
     * capture session at the new SurfaceTexture and apply the FIT_CENTER
     * transform on the new view. The capture session itself is kept
     * alive — only the preview [Surface] in the request target list is
     * rebuilt via a single `createCaptureSession` round-trip.
     */
    fun swapTextureView(newTextureView: TextureView?) {
        val previousTextureView = textureView
        if (previousTextureView === newTextureView) return
        if (!running.get()) return
        val device = cameraDevice ?: return
        val newView = newTextureView ?: return

        // Hide the old view, show the new one. Posting to main thread
        // because visibility changes are not safe from arbitrary threads.
        previousTextureView?.post { previousTextureView.visibility = android.view.View.GONE }
        newView.post { newView.visibility = android.view.View.VISIBLE }

        // Wait for the new SurfaceTexture to be ready before we rebuild
        // the capture session. TextureView.isAvailable is true after
        // SurfaceTextureListener.onSurfaceTextureAvailable fires; if it
        // is not, register a one-shot listener that calls back into the
        // session once the surface is allocated.
        val resume: (SurfaceTexture) -> Unit = { st ->
            rebuildPreviewSurface(device, newView, st)
        }
        if (newView.isAvailable) {
            resume(newView.surfaceTexture!!)
        } else {
            newView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: SurfaceTexture,
                    w: Int,
                    h: Int
                ) {
                    if (running.get()) resume(surface)
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: SurfaceTexture,
                    w: Int,
                    h: Int
                ) {
                    applyPreviewTransform(newView)
                }

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    previewSurface?.release()
                    previewSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            }
        }
    }

    /**
     * Rebuild the capture session with the new preview Surface and keep
     * the analysis ImageReader / JPEG ImageReader targets intact.
     *
     * Used by [swapTextureView] when the host Activity swaps preview
     * surfaces (small card ↔ fullscreen overlay).
     */
    private fun rebuildPreviewSurface(
        device: CameraDevice,
        newView: TextureView,
        surfaceTexture: SurfaceTexture
    ) {
        val handler = backgroundHandler ?: return
        // Pick the preview size from the existing camera characteristics.
        val chars = manager?.getCameraCharacteristics(cameraId) ?: return
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return
        val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
        val previewSize = chooseSize(previewSizes)
        lastPreviewSize = previewSize
        surfaceTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface?.release()
        previewSurface = Surface(surfaceTexture)
        newView.post { applyPreviewTransform(newView, previewSize) }

        val targets = mutableListOf<Surface>()
        imageReader?.surface?.let { targets += it }
        jpegImageReader?.surface?.let { targets += it }
        targets += previewSurface!!

        runCatching {
            device.createCaptureSession(
                targets,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!running.get()) {
                            session.close()
                            return
                        }
                        captureSession = session
                        captureSessionConfigured = true
                        val builder = createRequestBuilder(device)
                        if (builder != null) {
                            runCatching {
                                targets.forEach { builder.addTarget(it) }
                                builder.set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                                )
                                builder.set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_ON
                                )
                                builder.set(
                                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    android.util.Range(streamConfig.fps.fps, streamConfig.fps.fps)
                                )
                                session.setRepeatingRequest(builder.build(), null, handler)
                                currentCrop?.let {
                                    builder.set(CaptureRequest.SCALER_CROP_REGION, it)
                                    session.setRepeatingRequest(builder.build(), null, handler)
                                }
                                
                                //Re-apply flash setting
                                setFlashTorch(streamConfig.flashTorchEnabled)

                                setLinearZoom(streamConfig.linearZoom)

                                // Re-apply manual focus / ISO / exposure / flash
                                // settings that may have been lost when the
                                // capture session was torn down and rebuilt.
                                applyManualMode(
                                    streamConfig.manualFocusEnabled,
                                    streamConfig.manualAdjustmentsEnabled
                                )

                                Log.i(
                                    TAG,
                                    "Camera2 preview rebound to new texture view " +
                                        "(${previewSize.width}x${previewSize.height})"
                                )
                            }.onFailure {
                                Log.w(TAG, "Camera2 preview rebind repeating request failed: ${it.message}")
                            }
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e(TAG, "Camera2 preview rebind configure failed id=$cameraId")
                    }
                },
                handler
            )
        }.onFailure {
            Log.e(TAG, "Camera2 preview rebind createCaptureSession failed: ${it.message}", it)
        }
        textureView = newView
    }

    private fun updateCaptureRequestFps() {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val builder = createRequestBuilder(device) ?: return
        try {
            builder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            )
            builder.set(
                CaptureRequest.CONTROL_AE_MODE,
                CaptureRequest.CONTROL_AE_MODE_ON
            )
            // Here a request is made to the camera sensor to update
            // the number of frames per second to the new value chosen by the user.
            // The sensor automatically selects the closest supported value
            // to the one requested (it may therefore be slightly different).
            builder.set(
                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                android.util.Range(streamConfig.fps.fps, streamConfig.fps.fps)
            )
            try {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                Log.w(TAG, "setRepeatingRequest failed during FPS update, kicking with capture(): ${e.message}")
                runCatching { session.capture(builder.build(), null, backgroundHandler) }
                runCatching { session.setRepeatingRequest(builder.build(), null, backgroundHandler) }
            }
            Log.i(TAG, "Camera2 FPS updated to ${streamConfig.fps.fps}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update FPS: ${e.message}")
        }
    }

    /**
     * Toggles manual focus mode. When [enabled], autofocus is disabled (AF_MODE_OFF)
     * and the camera can be controlled manually via [setFocusDistance], [setIso],
     * and [setExposureTimeUs].
     */
    fun setManualFocusEnabled(enabled: Boolean) {
        streamConfig = streamConfig.copy(manualFocusEnabled = enabled)
        applyManualMode(enabled, streamConfig.manualAdjustmentsEnabled)
    }

    /** Toggles manual exposure mode (ISO / exposure time). */
    fun setManualAdjustmentsEnabled(enabled: Boolean) {
        streamConfig = streamConfig.copy(manualAdjustmentsEnabled = enabled)
        applyManualMode(streamConfig.manualFocusEnabled, enabled)
    }

    private fun applyManualMode(manualFocus: Boolean, manualAdjustments: Boolean) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        // AF and AE are now controlled independently. AF is the user's
        // "manual focus" toggle; AE is the user's "manual adjustments"
        // toggle. Each one is honoured on its own, so a user who only
        // ticks "Manual adjustments" still gets the AE lock + ISO/exposure
        // values applied, without needing to also tick "Manual focus".
        val afMode = if (manualFocus) CaptureRequest.CONTROL_AF_MODE_OFF else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        val aeMode = if (manualAdjustments) CaptureRequest.CONTROL_AE_MODE_OFF else CaptureRequest.CONTROL_AE_MODE_ON
        runCatching {
            val builder = createRequestBuilder(device)?.apply {
                imageReader?.surface?.let { addTarget(it) }
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, afMode)
                set(CaptureRequest.CONTROL_AE_MODE, aeMode)
                // Re-apply the user's manual values so AE_OFF has concrete
                // ISO / exposure-time / focus distance to drive the sensor.
                if (manualAdjustments) {
                    if (streamConfig.iso > 0) {
                        set(CaptureRequest.SENSOR_SENSITIVITY, streamConfig.iso)
                    }
                    if (streamConfig.exposureTimeUs > 0L) {
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, streamConfig.exposureTimeUs * 1000L)
                    }
                }
                if (manualFocus && streamConfig.focusDistance > 0f) {
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, streamConfig.focusDistance)
                }
                currentCrop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                if (streamConfig.fps.fps > 0) {
                    set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(streamConfig.fps.fps, streamConfig.fps.fps)
                    )
                }
                // Preserve FLASH_MODE_TORCH when flash is on. Without
                // this the default FLASH_MODE_OFF kills the torch and
                // on OEM aux lenses the AE_OFF + FLASH_OFF transition
                // can deadlock the capture session.
                if (flashModeSupported && streamConfig.flashTorchEnabled) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            } ?: return
            try {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                Log.w(TAG, "setRepeatingRequest failed during manual mode change, kicking with capture(): ${e.message}")
                runCatching { session.capture(builder.build(), null, backgroundHandler) }
                runCatching { session.setRepeatingRequest(builder.build(), null, backgroundHandler) }
            }
            Log.i(TAG, "Camera2 manual mode focus=$manualFocus adjust=$manualAdjustments")
        }.onFailure {
            Log.w(TAG, "Camera2 manual mode toggle failed: ${it.message}")
        }
    }

    /** Sets focus distance in meters (0 = closest focus). */
    fun setFocusDistance(distance: Float) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        streamConfig = streamConfig.copy(focusDistance = distance)
        runCatching {
            val builder = createRequestBuilder(device)?.apply {
                imageReader?.surface?.let { addTarget(it) }
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.LENS_FOCUS_DISTANCE, distance)
                currentCrop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                // Only force AF off when the user has actually enabled
                // manual focus; otherwise we use the user's current AF
                // preference. Critically, do NOT force AE off here: that
                // would lock the exposure at the last auto value, and the
                // manual-adjustments default is auto — so the image would
                // freeze on a black frame whenever the user touches the
                // focus slider without first ticking the "Manual
                // adjustments" checkbox.
                if (streamConfig.manualFocusEnabled) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                if (streamConfig.manualAdjustmentsEnabled) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    if (streamConfig.iso > 0) {
                        set(CaptureRequest.SENSOR_SENSITIVITY, streamConfig.iso)
                    }
                    if (streamConfig.exposureTimeUs > 0L) {
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, streamConfig.exposureTimeUs * 1000L)
                    }
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                if (streamConfig.fps.fps > 0) {
                    set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(streamConfig.fps.fps, streamConfig.fps.fps)
                    )
                }
                // Preserve FLASH_MODE_TORCH when flash is on. Without
                // this the default FLASH_MODE_OFF kills the torch and
                // on OEM aux lenses the AE_OFF + FLASH_OFF transition
                // can deadlock the capture session.
                if (flashModeSupported && streamConfig.flashTorchEnabled) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            } ?: return
            try {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                Log.w(TAG, "setRepeatingRequest failed during focus change, kicking with capture(): ${e.message}")
                runCatching { session.capture(builder.build(), null, backgroundHandler) }
                runCatching { session.setRepeatingRequest(builder.build(), null, backgroundHandler) }
            }
        }.onFailure {
            Log.w(TAG, "Camera2 focus distance failed: ${it.message}")
        }
    }

    /** Sets ISO sensitivity. 0 = auto. */
    fun setIso(iso: Int) {
        if (iso <= 0) return
        streamConfig = streamConfig.copy(iso = iso)
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        runCatching {
            val builder = createRequestBuilder(device)?.apply {
                imageReader?.surface?.let { addTarget(it) }
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                currentCrop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                // Lock AE only when manual-adjustments mode is on; otherwise
                // leave AE on so the sensor keeps auto-exposing and the user
                // sees the slider take effect instead of the screen freezing
                // on a black frame.
                if (streamConfig.manualAdjustmentsEnabled) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    if (streamConfig.exposureTimeUs > 0L) {
                        set(
                            CaptureRequest.SENSOR_EXPOSURE_TIME,
                            streamConfig.exposureTimeUs * 1000L
                        )
                    }
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                if (streamConfig.manualFocusEnabled) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    if (streamConfig.focusDistance > 0f) {
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, streamConfig.focusDistance)
                    }
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                if (streamConfig.fps.fps > 0) {
                    set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(streamConfig.fps.fps, streamConfig.fps.fps)
                    )
                }
                // Preserve FLASH_MODE_TORCH when flash is on. Without this,
                // the default FLASH_MODE_OFF in the new request kills the
                // torch, and on OEM aux lenses the simultaneous AE_OFF +
                // FLASH_OFF transition can deadlock the capture session.
                if (flashModeSupported && streamConfig.flashTorchEnabled) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            } ?: return
            try {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                // Some aux lenses need a single-shot kick before the
                // repeating request can be re-armed after an ISO change.
                Log.w(TAG, "setRepeatingRequest failed during ISO change, kicking with capture(): ${e.message}")
                runCatching { session.capture(builder.build(), null, backgroundHandler) }
                runCatching { session.setRepeatingRequest(builder.build(), null, backgroundHandler) }
            }
        }.onFailure {
            Log.w(TAG, "Camera2 ISO failed: ${it.message}")
        }
    }

    /** Sets exposure time in microseconds. 0 = auto. */
    fun setExposureTimeUs(exposureTimeUs: Long) {
        if (exposureTimeUs <= 0L) return
        streamConfig = streamConfig.copy(exposureTimeUs = exposureTimeUs)
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        runCatching {
            val exposureNs = exposureTimeUs * 1000L
            val builder = createRequestBuilder(device)?.apply {
                imageReader?.surface?.let { addTarget(it) }
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs)
                currentCrop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                if (streamConfig.manualAdjustmentsEnabled) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    if (streamConfig.iso > 0) {
                        set(CaptureRequest.SENSOR_SENSITIVITY, streamConfig.iso)
                    }
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                if (streamConfig.manualFocusEnabled) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    if (streamConfig.focusDistance > 0f) {
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, streamConfig.focusDistance)
                    }
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                if (streamConfig.fps.fps > 0) {
                    set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(streamConfig.fps.fps, streamConfig.fps.fps)
                    )
                }
                // Preserve FLASH_MODE_TORCH when flash is on. Without
                // this the default FLASH_MODE_OFF kills the torch and
                // on OEM aux lenses the AE_OFF + FLASH_OFF transition
                // can deadlock the capture session.
                if (flashModeSupported && streamConfig.flashTorchEnabled) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            } ?: return
            try {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                // Some aux lenses need a single-shot kick before the
                // repeating request can be re-armed after an exposure-time change.
                Log.w(TAG, "setRepeatingRequest failed during exposure change, kicking with capture(): ${e.message}")
                runCatching { session.capture(builder.build(), null, backgroundHandler) }
                runCatching { session.setRepeatingRequest(builder.build(), null, backgroundHandler) }
            }
        }.onFailure {
            Log.w(TAG, "Camera2 exposure time failed: ${it.message}")
        }
    }

    /**
     * Toggles torch mode (continuous flash) without disturbing the rest of
     * the capture-request state in a way that locks up OEM aux lenses.
     *
     * Why this is fiddly: the previous implementation re-applied the entire
     * AF / AE / ISO / exposure-time state around the FLASH_MODE change. On
     * the 3rd / 4th OEM rear lenses (ultrawide on Xiaomi-class hardware) the
     * firmware appears to process the FLASH_MODE option **before** the
     * AF / AE overrides it receives in the same request, then drop the
     * subsequent ones. The visible symptom is the preview freezing on a
     * single frame after the user turns the torch off, and the torch can no
     * longer be re-armed without changing lens.
     *
     * The fix is two-fold:
     *  1. Push FLASH_MODE **last** in the request builder, so the OEM
     *     firmware's "consume FLASH_MODE then revert other state" quirk
     *     still leaves our overrides in place.
     *  2. If the lens does not advertise [FLASH_INFO_AVAILABLE] = true, do
     *     not push FLASH_MODE at all — pushing it to a lens that has no
     *     flash unit is the path that gets the OEM firmware into a wedged
     *     state on some devices.
     *
     * A one-shot `capture()` is used as a fallback when `setRepeatingRequest`
     * fails (rare, but some devices need a single-shot kick to leave the
     * FLASH_MODE_TORCH state on the second transition).
     */
    fun setFlashTorch(enabled: Boolean) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        if (!captureSessionConfigured) return
        if (!flashModeSupported) {
            Log.i(TAG, "Camera2 flash skipped: lens has no flash unit (camera $cameraId)")
            return
        }
        streamConfig = streamConfig.copy(flashTorchEnabled = enabled)
        runCatching {
            val builder = createRequestBuilder(device)?.apply {
                imageReader?.surface?.let { addTarget(it) }
                previewSurface?.let { addTarget(it) }
                // Re-apply AF / AE / ISO / exposure / focus / fps / crop
                // FIRST so FLASH_MODE arrives last in the builder. The OEM
                // aux-lens quirk we worked around earlier dropped every
                // option listed after FLASH_MODE in the same request.
                if (streamConfig.manualAdjustmentsEnabled) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    if (streamConfig.iso > 0) {
                        set(CaptureRequest.SENSOR_SENSITIVITY, streamConfig.iso)
                    }
                    if (streamConfig.exposureTimeUs > 0L) {
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, streamConfig.exposureTimeUs * 1000L)
                    }
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                if (streamConfig.manualFocusEnabled) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    if (streamConfig.focusDistance > 0f) {
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, streamConfig.focusDistance)
                    }
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                if (streamConfig.fps.fps > 0) {
                    set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(streamConfig.fps.fps, streamConfig.fps.fps)
                    )
                }
                currentCrop?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                // FLASH_MODE deliberately LAST.
                set(
                    CaptureRequest.FLASH_MODE,
                    if (enabled) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
                )
            } ?: return
            try {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                // Some aux lenses need a single-shot kick after a
                // FLASH_MODE_TORCH → OFF transition. Try one capture()
                // before re-arming the repeating request.
                Log.w(TAG, "setRepeatingRequest failed during flash toggle, kicking with capture(): ${e.message}")
                runCatching { session.capture(builder.build(), null, backgroundHandler) }
                runCatching { session.setRepeatingRequest(builder.build(), null, backgroundHandler) }
            }
            Log.i(TAG, "Camera2 flash torch=$enabled (last in builder)")
        }.onFailure {
            Log.w(TAG, "Camera2 flash toggle failed: ${it.message}")
        }
    }

    fun setLinearZoom(linearZoom: Float) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        streamConfig = streamConfig.copy(linearZoom = linearZoom)
        val chars = manager?.getCameraCharacteristics(cameraId) ?: return
        val range = chars.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = 1f + (range - 1f) * linearZoom.coerceIn(0f, 1f)
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val centerX = sensor.width() / 2
        val centerY = sensor.height() / 2
        val halfW = (sensor.width() / (2f * zoom)).toInt()
        val halfH = (sensor.height() / (2f * zoom)).toInt()
        val crop = android.graphics.Rect(
            centerX - halfW,
            centerY - halfH,
            centerX + halfW,
            centerY + halfH
        )
        currentCrop = crop
        runCatching {
            val builder = createRequestBuilder(device)?.apply {
                imageReader?.surface?.let { addTarget(it) }
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.SCALER_CROP_REGION, crop)
                // Preserve all manual settings (AF, AE, ISO, exposure,
                // focus distance, FPS, flash) when zoom changes so the
                // capture session doesn't revert to auto mode.
                if (streamConfig.manualFocusEnabled) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    if (streamConfig.focusDistance > 0f) {
                        set(CaptureRequest.LENS_FOCUS_DISTANCE, streamConfig.focusDistance)
                    }
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
                }
                if (streamConfig.manualAdjustmentsEnabled) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    if (streamConfig.iso > 0) {
                        set(CaptureRequest.SENSOR_SENSITIVITY, streamConfig.iso)
                    }
                    if (streamConfig.exposureTimeUs > 0L) {
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, streamConfig.exposureTimeUs * 1000L)
                    }
                } else {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                }
                if (streamConfig.fps.fps > 0) {
                    set(
                        CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                        android.util.Range(streamConfig.fps.fps, streamConfig.fps.fps)
                    )
                }
                if (flashModeSupported && streamConfig.flashTorchEnabled) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
            } ?: return
            try {
                session.setRepeatingRequest(builder.build(), null, backgroundHandler)
            } catch (e: Exception) {
                Log.w(TAG, "setRepeatingRequest failed during zoom change, kicking with capture(): ${e.message}")
                runCatching { session.capture(builder.build(), null, backgroundHandler) }
                runCatching { session.setRepeatingRequest(builder.build(), null, backgroundHandler) }
            }
        }.onFailure {
            Log.w(TAG, "Camera2 zoom failed: ${it.message}")
        }
    }

    /**
     * One-shot tap-to-focus. [view] is the preview [TextureView] that received the
     * tap; [x]/[y] are in that view's coordinate space. Maps the tap through the
     * FIT_CENTER letterbox and current digital-zoom crop into sensor active-array
     * space, then drives a Camera2 AF trigger (with an AF region if supported).
     */
    fun focusAt(view: View, x: Float, y: Float) {
        val device = cameraDevice ?: return
        val session = captureSession ?: return
        val mgr = manager ?: return
        val chars = runCatching { mgr.getCameraCharacteristics(cameraId) }.getOrNull() ?: return
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val crop = currentCrop ?: sensor
        if (view.width <= 0 || view.height <= 0) return

        val point = mapViewToSensor(view, crop, x, y) ?: return
        val size = (sensor.width().coerceAtMost(sensor.height()) / 6)
            .coerceAtLeast(MeteringRectangle.METERING_WEIGHT_MIN)
        val region = MeteringRectangle(
            (point.x - size / 2).coerceIn(0, sensor.width() - size),
            (point.y - size / 2).coerceIn(0, sensor.height() - size),
            size,
            size,
            MeteringRectangle.METERING_WEIGHT_MAX
        )

        val maxAf = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxAe = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val handler = backgroundHandler
        runCatching {
            // Trigger: AF_START with the focus region (if supported), preserving zoom.
            val trigger = createRequestBuilder(device)?.apply {
                imageReader?.surface?.let { addTarget(it) }
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                if (maxAf > 0) {
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                }
                if (maxAe > 0) {
                    set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(region))
                }
                set(CaptureRequest.SCALER_CROP_REGION, crop)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            } ?: return
            session.capture(trigger.build(), null, handler)
            // Resume: AF idle (auto, locked to the region), still preserving zoom.
            val resume = createRequestBuilder(device)?.apply {
                imageReader?.surface?.let { addTarget(it) }
                previewSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                if (maxAf > 0) {
                    set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(region))
                }
                set(CaptureRequest.SCALER_CROP_REGION, crop)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            } ?: return
            session.setRepeatingRequest(resume.build(), null, handler)
        }.onFailure {
            Log.w(TAG, "Camera2 focus failed: ${it.message}")
        }
    }

    /**
     * Maps a tap in the preview view's coordinate space to sensor active-array
     * pixels inside the current [crop] rect, accounting for the FIT_CENTER letterbox.
     * Returns null when the preview geometry is unknown.
     */
    private fun mapViewToSensor(
        view: View,
        crop: android.graphics.Rect,
        x: Float,
        y: Float
    ): android.graphics.Point? {
        val previewSize = lastPreviewSize ?: return null
        // The sensor buffer is landscape; the TextureView is typically portrait so
        // the effective drawn buffer dimensions are (previewSize.height × previewSize.width),
        // mirroring applyPreviewTransform().
        val bufW = previewSize.height.toFloat()
        val bufH = previewSize.width.toFloat()
        if (bufW <= 0f || bufH <= 0f) return null
        val viewW = view.width.toFloat()
        val viewH = view.height.toFloat()
        val scale = minOf(viewW / bufW, viewH / bufH)
        val drawnW = bufW * scale
        val drawnH = bufH * scale
        val offsetX = (viewW - drawnW) / 2f
        val offsetY = (viewH - drawnH) / 2f
        var nx = ((x - offsetX) / drawnW).coerceIn(0f, 1f)
        var ny = ((y - offsetY) / drawnH).coerceIn(0f, 1f)
        // The TextureView transform visually mirrors front cameras; tap mapping must
        // follow what the user sees.
        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) nx = 1f - nx
        val sx = (crop.left + nx * crop.width()).toInt().coerceIn(crop.left, crop.right - 1)
        val sy = (crop.top + ny * crop.height()).toInt().coerceIn(crop.top, crop.bottom - 1)
        return android.graphics.Point(sx, sy)
    }

    fun close() {
        running.set(false)
        val session = captureSession
        val device = cameraDevice
        val reader = imageReader
        val jpegReader = jpegImageReader
        val surface = previewSurface
        captureSession = null
        captureSessionConfigured = false
        cameraDevice = null
        imageReader = null
        jpegImageReader = null
        previewSurface = null
        currentCrop = null
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
            previewView.visibility = android.view.View.VISIBLE
        }
        // Snapshot textureView for the closure (the property is `var`).
        val tv = textureView
        tv?.post {
            tv.visibility = android.view.View.GONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(previewTexture: SurfaceTexture?) {
        val handler = backgroundHandler ?: return
        val mgr = manager ?: return
        val chars = mgr.getCameraCharacteristics(cameraId)
        // Probe FLASH support so we can avoid toggling FLASH_MODE on OEM
        // aux lenses that don't have a flash unit (ultrawide / tele on
        // some Xiaomi devices accept the option but deadlock the capture
        // session when it is set to OFF after a TORCH cycle).
        flashModeSupported = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        Log.i(TAG, "Camera $cameraId flashModeSupported=$flashModeSupported")
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: error("No stream config for camera $cameraId")

        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        val analysisSize = chooseSize(yuvSizes)
        imageReader = ImageReader.newInstance(
            analysisSize.width,
            analysisSize.height,
            ImageFormat.YUV_420_888,
            2
        ).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                handleImage(image)
            }, handler)
        }

        // For JPEG stream format the camera ISP can encode frames directly
        // as JPEG at the configured output size, giving us hardware-accelerated
        // compression on every Android device (no MediaCodec needed).
        if (streamConfig.streamFormat == StreamFormat.JPEG) {
            jpegImageReader = ImageReader.newInstance(
                streamConfig.outputWidth,
                streamConfig.outputHeight,
                ImageFormat.JPEG,
                1
            ).also { reader ->
                reader.setOnImageAvailableListener({ r ->
                    val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                    handleJpegImage(image)
                }, handler)
            }
        }

        val surfaces = mutableListOf<Surface>()
        imageReader?.surface?.let { surfaces += it }
        jpegImageReader?.surface?.let { surfaces += it }

        if (previewTexture != null) {
            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
            val previewSize = chooseSize(previewSizes)
            lastPreviewSize = previewSize
            previewTexture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewSurface = Surface(previewTexture).also { surfaces += it }
            // Snapshot for the post() closure (textureView is `var`).
            val tv = textureView
            tv?.post { applyPreviewTransform(tv, previewSize) }
        }

        mgr.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (!running.get()) {
                        camera.close()
                        return
                    }
                    cameraDevice = camera
                    createSession(camera, surfaces)
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
                        captureSessionConfigured = true
                        val builder = createRequestBuilder(camera)
                        if (builder == null) {
                            Log.e(TAG, "No capture template works for camera $cameraId")
                            return
                        }
                        runCatching {
                            surfaces.forEach { builder.addTarget(it) }
                            builder.set(
                                CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                            )
                            builder.set(
                                CaptureRequest.CONTROL_AE_MODE,
                                CaptureRequest.CONTROL_AE_MODE_ON
                            )
                            // Here a request is made to the camera sensor to get
                            // the number of frames per second chosen by the user.
                            // The sensor automatically selects the closest supported value
                            // to the one requested (it may therefore be slightly different).
                            builder.set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                android.util.Range(streamConfig.fps.fps, streamConfig.fps.fps)
                            )
                            session.setRepeatingRequest(builder.build(), null, backgroundHandler)
                            setLinearZoom(streamConfig.linearZoom)
                            // Re-apply flash, ISO, exposure and focus settings
                            // that were configured before the session was created.
                            // Without these, switching to fullscreen (which creates
                            // a new Camera2StreamSession) would lose all manual
                            // settings even though the UI still shows the correct values.
                            setFlashTorch(streamConfig.flashTorchEnabled)
                            applyManualMode(
                                streamConfig.manualFocusEnabled,
                                streamConfig.manualAdjustmentsEnabled
                            )
                            Log.i(
                                TAG,
                                "Camera2 session started id=$cameraId surfaces=${surfaces.size} " +
                                    "fps=${streamConfig.fps.fps}"
                            )
                        }.onFailure {
                            Log.e(TAG, "Camera2 repeating request failed: ${it.message}", it)
                        }
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

    /** Some OEM aux cameras reject TEMPLATE_PREVIEW; try safer fallbacks. */
    private fun createRequestBuilder(camera: CameraDevice): CaptureRequest.Builder? {
        // RECORD first: Xiaomi aux lenses often reject TEMPLATE_PREVIEW (-38).
        val templates = intArrayOf(
            CameraDevice.TEMPLATE_RECORD,
            CameraDevice.TEMPLATE_PREVIEW,
            CameraDevice.TEMPLATE_STILL_CAPTURE,
            CameraDevice.TEMPLATE_MANUAL
        )
        for (template in templates) {
            val builder = runCatching { camera.createCaptureRequest(template) }
                .onFailure {
                    Log.w(TAG, "template=$template failed for $cameraId: ${it.message}")
                }
                .getOrNull()
            if (builder != null) {
                Log.i(TAG, "Using capture template=$template for camera $cameraId")
                // JPEG format streams use the camera ISP hardware encoder directly
                // via a dedicated JPEG ImageReader. Inject quality control so the
                // encoder produces frames at the user-configured quality level.
                if (streamConfig.streamFormat == StreamFormat.JPEG) {
                    builder.set(
                        CaptureRequest.JPEG_QUALITY,
                        streamConfig.jpegQuality.quality.toByte()
                    )
                }
                return builder
            }
        }
        return null
    }

    private fun handleImage(image: Image) {
        try {
            if (!running.get()) return
            lastPublishNs.set(System.nanoTime())

            // Publish-side FPS cap. See [nextPublishAllowedNs] for the
            // contract: when "Limit FPS" is on, drop any frame arriving
            // before the configured interval has elapsed since the last
            // published frame. The sensor keeps delivering at its
            // negotiated rate (so the preview stays smooth); only the
            // broker / outgoing stream is capped.
            if (streamConfig.limitFps) {
                val nowNs = System.nanoTime()
                val intervalNs = 1_000_000_000L / streamConfig.fps.fps.coerceAtLeast(1)
                val allowedNs = nextPublishAllowedNs.get()
                if (allowedNs != 0L && nowNs < allowedNs) {
                    return
                }
                nextPublishAllowedNs.set(nowNs + intervalNs)
            }

            val rotation = streamConfig.orientation.bufferRotationDegrees(
                sensorOrientation,
                frontFacing = lensFacing == CameraCharacteristics.LENS_FACING_FRONT,
            )
            // Independent 180° flip on top of the orientation-driven
            // rotation. Mirrors the same logic in CameraStreamController.
            val finalRotation =
                if (streamConfig.rotate180) (rotation + 180) % 360 else rotation
            yuvExtractor.processToOutput(
                image,
                streamConfig.outputWidth,
                streamConfig.outputHeight,
                finalRotation
            )?.let { (w, h, i420) ->
                if (streamConfig.streamFormat == StreamFormat.JPEG) {
                    // Convert I420 to JPEG with configurable quality.
                    // The YUV pipeline may pick a camera-native resolution that
                    // does not match (outputWidth, outputHeight) — when that
                    // happens the returned buffer is sized for the source and
                    // NOT (w, h); using (w, h) blindly would IndexOutOfBound.
                    // Detect the actual source dimensions from the buffer
                    // length and prefer them; when we can't recover them, skip
                    // this frame instead of crashing on the conversion.
                    val (jpegW, jpegH) = inferI420Size(i420, w, h)
                    if (jpegW <= 0 || jpegH <= 0) return@let
                    val jpegQuality = streamConfig.jpegQuality.quality
                    val jpegBytes = i420ToJpeg(i420, jpegW, jpegH, jpegQuality)
                    jpegBytes?.let {
                        frameBroker.publish(
                            format = StreamFormat.JPEG,
                            width = jpegW,
                            height = jpegH,
                            rotationDegrees = 0,
                            payload = it
                        )
                    }
                } else {
                    // Raw YUV/I420 streaming
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
     * Handles a JPEG frame produced directly by the camera ISP.
     * The payload is already HW-compressed JPEG bytes — nothing
     * to convert. We publish them as-is to the frame broker.
     *
     * [jpegQuality] is applied via [CaptureRequest.JPEG_QUALITY]
     * in the capture request, not here.
     */
    private fun handleJpegImage(image: Image) {
        try {
            if (!running.get()) return
            lastPublishNs.set(System.nanoTime())
            // Publish-side FPS cap (JPEG path). Mirrors [handleImage] so
            // the cap applies regardless of which stream format the user
            // picked. See [nextPublishAllowedNs] for details.
            if (streamConfig.limitFps) {
                val nowNs = System.nanoTime()
                val intervalNs = 1_000_000_000L / streamConfig.fps.fps.coerceAtLeast(1)
                val allowedNs = nextPublishAllowedNs.get()
                if (allowedNs != 0L && nowNs < allowedNs) {
                    return
                }
                nextPublishAllowedNs.set(nowNs + intervalNs)
            }
            val planes = image.planes
            if (planes.isNotEmpty()) {
                val buf = planes[0].buffer
                val bytes = ByteArray(buf.remaining())
                buf.get(bytes)
                frameBroker.publish(
                    format = StreamFormat.JPEG,
                    width = image.width,
                    height = image.height,
                    rotationDegrees = 0,
                    payload = bytes
                )
            }
        } finally {
            image.close()
        }
    }

    /**
     * Convert I420 (YUV420 planar) byte array to JPEG.
     *
     * On Android 11+ (API 30) this prefers the hardware JPEG encoder
     * via MediaCodec; on older devices or when HW fails it silently
     * falls back to the software YuvImage compressor.
     */
    private fun i420ToJpeg(
        i420: ByteArray,
        width: Int,
        height: Int,
        quality: Int
    ): ByteArray? = JpegEncoder.encode(i420, width, height, quality)

    /**
     * Pick dimensions that match the I420 buffer layout.
     *
     * The pipeline may return (w, h) for the target resolution while the
     * actual buffer is laid out at the camera's native resolution; using the
     * mismatched (w, h) to read the buffer throws IndexOutOfBounds. We
     * prefer (w, h) when the buffer is consistent with them, otherwise we
     * fall back to the actual (width, height) encoded in the buffer length.
     * Returns (-1, -1) when neither side matches; callers should skip the
     * frame instead of crashing the encoder.
     */
    private fun inferI420Size(
        i420: ByteArray,
        targetW: Int,
        targetH: Int
    ): Pair<Int, Int> {
        val targetBytes = targetW * targetH * 3 / 2
        if (i420.size == targetBytes && targetW > 0 && targetH > 0) {
            return targetW to targetH
        }
        // Derive (w, h) from the buffer: I420 layout = w*h + 2*(w/2)*(h/2).
        // Heuristic — assume the height matches and solve for the width.
        if (targetH > 0) {
            val perRow = i420.size.toDouble() / (targetH * 1.5)
            val recoveredW = (perRow).toInt()
            if (recoveredW > 0 && recoveredW * targetH * 3 / 2 == i420.size) {
                return recoveredW to targetH
            }
        }
        // Or assume the width matches and recover the height.
        if (targetW > 0) {
            val perRow = i420.size.toDouble() / (targetW * 1.5)
            val recoveredH = (perRow).toInt()
            if (recoveredH > 0 && targetW * recoveredH * 3 / 2 == i420.size) {
                return targetW to recoveredH
            }
        }
        // Last resort — log and surface the issue instead of crashing.
        Log.w(
            TAG,
            "I420 buffer size ${i420.size} ≠ target ${targetW}x${targetH} (${targetBytes} bytes); " +
                "could not recover dimensions, skipping JPEG encoding"
        )
        return -1 to -1
    }

    /**
     * Convert I420 (YUV420 planar: YYYYYYYY UVUV) to NV21 (YUV420 semi-planar: YYYYYYYY VUVU).
     */
    private fun i420ToNv21(i420: ByteArray, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = ySize / 4
        // NV21 has the same total size as I420 (Y plane + interleaved V/U plane).
        val nv21 = ByteArray(ySize + 2 * uvSize)

        // Copy Y plane (same in both formats)
        System.arraycopy(i420, 0, nv21, 0, ySize)

        // Interleave V and U planes: I420 has U then V, NV21 needs V then U
        val uOffset = ySize
        val vOffset = ySize + uvSize
        val nv21UvOffset = ySize

        for (i in 0 until uvSize) {
            nv21[nv21UvOffset + i * 2] = i420[vOffset + i]     // V
            nv21[nv21UvOffset + i * 2 + 1] = i420[uOffset + i] // U
        }

        return nv21
    }

    private fun chooseSize(sizes: Array<Size>): Size {
        if (sizes.isEmpty()) return Size(1280, 720)
        val targetW = maxOf(streamConfig.resolution.landscapeWidth, streamConfig.resolution.landscapeHeight)
        val targetH = minOf(streamConfig.resolution.landscapeWidth, streamConfig.resolution.landscapeHeight)
        return sizes.minByOrNull {
            abs(it.width * it.height - targetW * targetH)
        } ?: sizes[0]
    }

    /**
     * Called externally (via [CameraStreamController]) when the rotate180
     * toggle changes mid-stream so the TextureView preview re-applies
     * its transform matrix with the new 180° rotation flag.
     */
    fun forceReapplyPreviewTransform() {
        val tv = textureView
        tv?.post { applyPreviewTransform(tv) }
    }

    private fun applyPreviewTransform(view: TextureView, previewSize: Size? = null) {
        // The TextureView only handles the aspect-ratio scaling and
        // centring of the camera buffer inside the view. The 180°
        // rotation toggle is applied at the wrapper-container level
        // (see MainActivity.findWrapperFor / CameraStreamController's
        // findRotate180Wrapper) so the TextureView itself must stay
        // unrotated: rotating both would double-rotate the preview.
        //
        // We still bake the rotation directly into the transform matrix
        // instead of setting view.rotation, because setTransform()
        // overwrites view.rotation and would undo it. The matrix here
        // is purely the sensor-aspect-fit + centre-inside-view matrix.
        //
        // When called without a previewSize (from forceReapplyPreviewTransform
        // or updateConfig), we fall back to lastPreviewSize so the
        // toggle takes effect mid-stream without a full capture-session
        // rebuild.
        val size = previewSize ?: lastPreviewSize ?: return
        if (view.width == 0 || view.height == 0) return
        val viewW = view.width.toFloat()
        val viewH = view.height.toFloat()
        val previewW = size.height.toFloat() // sensor is landscape; view is typically portrait
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
    }

    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        runCatching { backgroundThread?.join(500) }
        backgroundThread = null
        backgroundHandler = null
    }

    companion object {
        private const val TAG = "Camera2StreamSession"
    }
}
