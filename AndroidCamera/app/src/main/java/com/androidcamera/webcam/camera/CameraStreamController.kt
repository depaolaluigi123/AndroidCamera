package com.androidcamera.webcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.os.Build
import androidx.annotation.RequiresApi
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import android.hardware.camera2.CaptureRequest
import android.view.TextureView
import android.view.View
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.androidcamera.webcam.model.JpegQuality
import com.androidcamera.webcam.model.StreamConfig
import com.androidcamera.webcam.model.CameraFacing
import com.androidcamera.webcam.model.StreamFormat
import com.androidcamera.webcam.streaming.FrameBroker
import com.androidcamera.webcam.streaming.StreamFrame
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.math.min

/**
 * Owns CameraX binding and publishes oriented/scaled frames into [frameBroker].
 *
 * Supports both public logical cameras and OEM/physical aux cameras. When the
 * selected id is a physical camera of a logical multi-camera, CameraX opens the
 * logical camera and [Camera2Interop.Extender.setPhysicalCameraId] forces that lens.
 * Aux IDs unknown to CameraX fall back to a Camera2 session with [TextureView] preview.
 */
class CameraStreamController(
    private val context: Context
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysisExecutor: ExecutorService? = null
    private val started = AtomicBoolean(false)
    @Volatile
    private var config: StreamConfig = StreamConfig()
    private var lifecycleOwner: LifecycleOwner? = null
    private var activeBinding: CameraInventory.CameraBinding? = null
    private var previewView: PreviewView? = null
    private var textureView: TextureView? = null
    private var primaryPreview: Preview? = null
    private var analysis: ImageAnalysis? = null
    private var camera: Camera? = null
    private var rebindGeneration = 0
    private var camera2Session: Camera2StreamSession? = null

    val frameBroker = FrameBroker()
    private val lastPublishNs = AtomicLong(0L)
    /**
     * High-water mark of [System.nanoTime] for the next publish to honour
     * when [StreamConfig.limitFps] is enabled: a publish that arrives
     * before this instant is dropped (the broker keeps holding the
     * previous frame). Reset to 0 by [updateStreamConfig] and by the
     * limit-toggle setter so a tighter / looser limit takes effect from
     * the very next frame.
     */
    private val nextPublishAllowedNs = AtomicLong(0L)

    fun isStarted(): Boolean = started.get()

    suspend fun start(
        lifecycleOwner: LifecycleOwner,
        cameraId: String,
        previewView: PreviewView?,
        textureView: TextureView? = null,
        streamConfig: StreamConfig = StreamConfig()
    ): String {
        if (!started.compareAndSet(false, true)) {
            stop(clearBrokers = false)
            started.set(true)
        }
        config = streamConfig
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView
        this.textureView = textureView

        val cameraBinding = CameraInventory.listBindings(context)
            .firstOrNull { it.camera.id == cameraId }
            ?: error("Camera id $cameraId is not available on this device")
        activeBinding = cameraBinding

        val provider = awaitCameraProvider()
        cameraProvider = provider
        provider.unbindAll()
        camera2Session?.close()
        camera2Session = null
        frameBroker.clear()

        analysisExecutor = Executors.newFixedThreadPool(1)

        // Initialize the analysis that will be reused across preview rebinds
        analysis = buildAnalysis(cameraBinding.physicalId)

        bindCamera(provider, lifecycleOwner, cameraBinding, previewView, textureView)
        applyZoom()
        applyManualOptions()
        Log.i(
            TAG,
            "Camera started id=${cameraBinding.camera.id} openId=${cameraBinding.openId} " +
                "physicalId=${cameraBinding.physicalId} orientation=${config.orientation} " +
                "output=${config.outputWidth}x${config.outputHeight}"
        )
        return cameraBinding.camera.id
    }

    fun rebind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView?,
        textureView: TextureView? = this.textureView
    ) {
        if (!started.get()) return
        val provider = cameraProvider ?: return
        val binding = activeBinding ?: return
        // Suppress reentrant rebinds when the very same parameters are
        // already in place — repeated state notifications from the service
        // (and from the same coroutine scheduling) can otherwise call
        // unbindAll() in a tight loop, starving the analysis.
        if (
            this.lifecycleOwner === lifecycleOwner &&
            this.previewView === previewView &&
            this.textureView === textureView
        ) {
            return
        }

        // Rebind to new lifecycle owner (Activity vs Service) with new preview surfaces.
        // This is a full rebind needed when switching camera ownership.
        val generation = ++rebindGeneration
        this.lifecycleOwner = lifecycleOwner
        this.previewView = previewView
        this.textureView = textureView
        ContextCompat.getMainExecutor(context).execute {
            if (!started.get() || generation != rebindGeneration) return@execute
            runCatching {
                provider.unbindAll()
                primaryPreview = null
                camera = null
                analysis = null
                camera2Session?.close()
                camera2Session = null
                bindCamera(provider, lifecycleOwner, binding, previewView, textureView)
                applyZoom()
                applyManualOptions()
                Log.i(
                    TAG,
                    "Rebound camera id=${binding.camera.id} " +
                        "owner=${lifecycleOwner.javaClass.simpleName} preview=${previewView != null}"
                )
            }.onFailure {
                Log.e(TAG, "Camera rebind failed: ${it.message}")
            }
        }
    }

    fun attachPreview(previewView: PreviewView?, textureView: TextureView? = this.textureView) {
        if (!started.get()) return
        val owner = lifecycleOwner ?: return
        // Camera2 (OEM aux) path: swap the preview texture on the live session
        // without touching the analysis / capture session. The previous
        // implementation only handled the CameraX case and left the
        // fullscreen TextureView frozen on the very frame the user tapped
        // the "Fullscreen preview" button — the symptom was that switching
        // the lens (anything past the first camera in the spinner) would
        // never update the fullscreen preview. Doing a CameraX-style
        // unbind/rebind here is also wrong: the Camera2 session owns the
        // lens, ProcessCameraProvider.bindToLifecycle() would fail.
        val session = camera2Session
        if (session != null) {
            Log.d(
                TAG,
                "attachPreview: camera2 session, swap texture " +
                    "(tv same=${this.textureView === textureView})"
            )
            this.previewView = previewView
            this.textureView = textureView
            this.lifecycleOwner = owner
            // Hide the CameraX PreviewView while Camera2 owns the lens,
            // and re-show the new TextureView (fullscreen or embedded).
            previewView?.visibility = android.view.View.INVISIBLE
            session.swapTextureView(textureView)
            return
        }
        // Already attached to the same surfaces — do nothing. Repeated state
        // notifications from the service can otherwise spam unbind/rebind
        // and starve the analysis use case.
        if (
            this.previewView === previewView &&
            this.textureView === textureView &&
            primaryPreview != null &&
            previewView != null &&
            camera2Session == null
        ) {
            Log.d(TAG, "attachPreview: same surfaces, skip")
            ContextCompat.getMainExecutor(context).execute {
                applyPreviewScale(previewView)
                primaryPreview?.setSurfaceProvider(previewView.surfaceProvider)
            }
            return
        }
        Log.d(TAG, "attachPreview: re-binding (pv same=${this.previewView === previewView}, tv same=${this.textureView === textureView})")
        // Switch to new preview surfaces without full rebind if camera is already running.
        // This avoids the brief pause when switching between normal/fullscreen preview.
        val provider = cameraProvider ?: return
        val binding = activeBinding ?: return
        this.previewView = previewView
        this.textureView = textureView
        this.lifecycleOwner = owner
        ContextCompat.getMainExecutor(context).execute {
            if (!started.get()) return@execute
            runCatching {
                // Only unbind and rebind the preview, keep analysis running for continuous streaming
                if (primaryPreview != null) {
                    provider.unbind(primaryPreview!!)
                    primaryPreview = null
                    // Do NOT set camera = null - keep the analysis bound and running
                }
                // Bind only the new preview surface to the existing camera session
                if (previewView != null) {
                    val preview = buildPreview(previewView, binding.physicalId)
                    primaryPreview = preview
                    val selector = CameraSelector.Builder()
                        .addCameraFilter { cameraInfos ->
                            cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == binding.openId }
                        }
                        .build()
                    // Reuse existing analysis
                    val analysis = this.analysis ?: buildAnalysis(binding.physicalId).also { this.analysis = it }
                    camera = provider.bindToLifecycle(lifecycleOwner!!, selector, preview, analysis)
                } else {
                    primaryPreview = null
                    // No preview needed, camera stays bound to analysis only
                }
                applyZoom()
                // Re-apply manual focus / AE overrides on the new camera
                // binding: a fresh bind discards the capture-request options
                // that were pushed before, so without this re-application
                // the new (fullscreen) preview surface would render with
                // stale AF / AE / ISO / exposure values. The user reported
                // this exact symptom when switching to a camera that is not
                // the first in the spinner list.
                applyManualOptions()
                Log.i(
                    TAG,
                    "Attached preview view preview=${previewView != null} texture=${textureView != null}"
                )
            }.onFailure {
                Log.e(TAG, "Camera attach preview failed: ${it.message}")
            }
        }
    }

    fun updateStreamConfig(streamConfig: StreamConfig) {
        config = streamConfig
        applyPreviewScale(previewView)
        // Also re-bind the CameraX preview surface provider so the new
        // view.rotation value is picked up by CameraX's internal renderer.
        val pv = previewView
        val preview = primaryPreview
        if (pv != null && preview != null && camera2Session == null) {
            ContextCompat.getMainExecutor(context).execute {
                preview.setSurfaceProvider(pv.surfaceProvider)
            }
        }
        applyZoom()
        // Reset the last publish timestamp when FPS changes so the next frame
        // is processed without stale comparison.
        lastPublishNs.set(0L)
        // Also reset the limit-FPS gate: changing the target FPS would
        // otherwise carry the old inter-frame interval into the new config
        // and either drop too many or too few frames at the boundary.
        nextPublishAllowedNs.set(0L)
        camera2Session?.updateConfig(streamConfig)
    }

    fun setLinearZoom(linearZoom: Float) {
        config = config.copy(linearZoom = linearZoom.coerceIn(0f, 1f))
        applyZoom()
        camera2Session?.setLinearZoom(config.linearZoom)
    }

    /** Switches between auto-focus and manual-focus mode. */
    fun setManualFocusEnabled(enabled: Boolean) {
        config = config.copy(manualFocusEnabled = enabled)
        camera2Session?.setManualFocusEnabled(enabled)
        applyCameraXOptions(enabled)
    }

    /**
     * Switches between auto-exposure and manual-exposure mode. Independent
     * from [setManualFocusEnabled] so the user can lock only ISO / exposure
     * time without also disabling autofocus.
     */
    fun setManualAdjustmentsEnabled(enabled: Boolean) {
        config = config.copy(manualAdjustmentsEnabled = enabled)
        camera2Session?.setManualAdjustmentsEnabled(enabled)
        applyCameraXOptions(config.manualFocusEnabled)
    }

    /** Sets normalized focus distance (0f = closest, 1f = farthest). */
    fun setFocusDistance(distance: Float) {
        config = config.copy(focusDistance = distance.coerceIn(0f, 1f))
        camera2Session?.setFocusDistance(config.focusDistance)
        applyCameraXOptions(config.manualFocusEnabled)
    }

    /** Sets ISO sensitivity (0 = auto). */
    fun setIso(iso: Int) {
        config = config.copy(iso = iso)
        camera2Session?.setIso(iso)
        applyCameraXOptions(config.manualFocusEnabled)
    }

    /** Sets exposure time in microseconds (0 = auto). */
    fun setExposureTimeUs(exposureTimeUs: Long) {
        config = config.copy(exposureTimeUs = exposureTimeUs)
        camera2Session?.setExposureTimeUs(exposureTimeUs)
        applyCameraXOptions(config.manualFocusEnabled)
    }

    /** Toggles torch/flash mode (rear cameras only). */
    fun setFlashTorch(enabled: Boolean) {
        config = config.copy(flashTorchEnabled = enabled)
        camera2Session?.setFlashTorch(enabled)
        applyCameraXOptions(enabled)
    }

    /**
     * Enables / disables the outgoing-stream FPS cap.
     *
     * When [enabled] is true the controller drops (does not publish) any
     * frame that arrives before the configured [StreamConfig.fps] interval
     * has elapsed since the last published frame. The camera sensor keeps
     * running at whatever rate it negotiated with the system, so the live
     * preview still updates at the full sensor rate; only the broker (and
     * therefore the stream forwarded to the PC) is capped.
     *
     * When [enabled] is false every frame is forwarded, matching the
     * previous behaviour.
     */
    fun setLimitFps(enabled: Boolean) {
        config = config.copy(limitFps = enabled)
        // Reset the gate so a freshly-enabled cap doesn't immediately drop
        // the first frame that arrived during the "uncapped" window, and
        // a freshly-disabled cap stops dropping on the very next frame.
        nextPublishAllowedNs.set(0L)
        camera2Session?.setLimitFps(enabled)
    }

    /** Returns true when the active camera faces rear. */
    fun isRearCamera(): Boolean {
        val binding = activeBinding ?: return false
        return binding.camera.facing == CameraFacing.BACK
    }

    /**
     * Switch the active camera (lens) while streaming is live. Rebinds the
     * controller to [cameraId] without going through a full stop/start
     * (which would kill the live stream broker). Returns the resolved
     * camera id, or null when no live session is running.
     *
     * The user can pick any camera in the system (rear, front, aux). The
     * binding is looked up via [CameraInventory.listBindings] which already
     * understands logical / physical / OEM aux cameras.
     */
    fun switchCamera(cameraId: String): String? {
        if (!started.get()) return null
        val newBinding = CameraInventory.listBindings(context)
            .firstOrNull { it.camera.id == cameraId }
            ?: run {
                Log.w(TAG, "switchCamera: cameraId=$cameraId not available")
                return null
            }
        if (newBinding.camera.id == activeBinding?.camera?.id) {
            Log.i(TAG, "switchCamera: already on id=${newBinding.camera.id}, skip")
            return newBinding.camera.id
        }
        val owner = lifecycleOwner ?: return null
        val provider = cameraProvider ?: return null
        val previewView = this.previewView
        val textureView = this.textureView

        activeBinding = newBinding
        // Stop any pending Camera2 fallback session — CameraX rebinds do not
        // coexist with a Camera2 session on the same ProcessCameraProvider.
        runCatching { camera2Session?.close() }
        camera2Session = null
        camera = null
        primaryPreview = null
        // Rebuild the analysis with the new physical id (or null when the
        // user switched to a logical camera).
        analysis = buildAnalysis(newBinding.physicalId)

        val generation = ++rebindGeneration
        ContextCompat.getMainExecutor(context).execute {
            if (!started.get() || generation != rebindGeneration) return@execute
            runCatching {
                provider.unbindAll()
                bindCamera(provider, owner, newBinding, previewView, textureView)
                applyZoom()
                applyManualOptions()
                Log.i(
                    TAG,
                    "switchCamera: rebound to id=${newBinding.camera.id} " +
                        "physicalId=${newBinding.physicalId}"
                )
            }.onFailure {
                Log.e(TAG, "switchCamera failed: ${it.message}")
            }
        }
        return newBinding.camera.id
    }

    /**
     * Apply the currently configured manual focus / ISO / exposure time / flash
     * options to the live CameraX session via Camera2CaptureRequest overrides
     * — without tearing down the analysis use case. CameraX's
     * Camera2CameraControl.setCaptureRequestOptions() overrides the options on
     * every repeating capture request, so changes take effect immediately and
     * the streaming pipeline keeps running.
     *
     * The two "manual" toggles are now INDEPENDENT:
     *  - ``manualFocusEnabled`` controls AF (CONTROL_AF_MODE).
     *  - ``manualAdjustmentsEnabled`` controls AE (CONTROL_AE_MODE) and is
     *    what gates the ISO / exposure-time overrides. They MUST be
     *    decoupled: a user who only checks "Manual adjustments" expects the
     *    ISO / exposure spinners to take effect even when "Manual focus"
     *    is left off.
     *
     * When the session is a Camera2 session (OEM aux cameras) the Camera2
     * session already has its own per-method apply path (setManualFocusEnabled,
     * setIso, setExposureTimeUs, setFlashTorch) — we still touch the controller
     * config here so config().copy() stays consistent for the next start() /
     * rebind.
     */
    private fun applyCameraXOptions(@Suppress("UNUSED_PARAMETER") manual: Boolean) {
        val cam = camera ?: return
        ContextCompat.getMainExecutor(context).execute {
            runCatching {
                val control = Camera2CameraControl.from(cam.cameraControl)
                val builder = CaptureRequestOptions.Builder()
                // AF mode is governed by "manual focus" only. When the user
                // has only ticked "manual adjustments" we keep the camera's
                // continuous-picture AF running — disabling AF would yank
                // autofocus from a scene the user did not mean to lock.
                if (config.manualFocusEnabled) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                } else {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                    )
                }
                // AE mode is governed by "manual adjustments" only. This is
                // what actually enables the user's ISO / exposure-time
                // overrides — locking AE here is the trigger that makes
                // SENSOR_SENSITIVITY / SENSOR_EXPOSURE_TIME take effect on
                // every repeating capture request.
                if (config.manualAdjustmentsEnabled) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_OFF
                    )
                } else {
                    builder.setCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON
                    )
                }
                if (config.focusDistance > 0f) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.LENS_FOCUS_DISTANCE,
                        config.focusDistance
                    )
                }
                if (config.iso > 0) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.SENSOR_SENSITIVITY,
                        config.iso
                    )
                }
                if (config.exposureTimeUs > 0L) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.SENSOR_EXPOSURE_TIME,
                        config.exposureTimeUs * 1000L
                    )
                }
                // Flash / torch (rear cameras only): the FLASH_MODE capture
                // request option drives the LED continuously while on.
                if (config.flashTorchEnabled) {
                    builder.setCaptureRequestOption(
                        CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_TORCH
                    )
                } else {
                    builder.setCaptureRequestOption(
                        CaptureRequest.FLASH_MODE,
                        CaptureRequest.FLASH_MODE_OFF
                    )
                }
                control.setCaptureRequestOptions(builder.build())
                Log.i(
                    TAG,
                    "CameraX options applied " +
                        "(focusManual=${config.manualFocusEnabled}, " +
                        "adjustManual=${config.manualAdjustmentsEnabled})"
                )
            }.onFailure {
                Log.w(TAG, "applyCameraXOptions failed: ${it.message}")
            }
        }
    }

    /**
     * Public entry point: re-apply the current capture-request overrides to the
     * running CameraX session. Used after bind to ensure the options are in
     * place from the first frame.
     */
    fun applyManualOptions() {
        applyCameraXOptions(config.manualFocusEnabled)
    }

    /**
     * Returns the currently active camera session transport — useful for the
     * UI to decide whether the camera-side controls reach the user (always
     * true once the controller is started).
     */
    fun isUsingCamera2(): Boolean = camera2Session != null

    /**
     * Tap-to-focus. [view] is the preview surface that received the tap (one of the
     * fullscreen PreviewView / TextureView); [x]/[y] are tap coordinates in that
     * view's coordinate space. Delegates to the Camera2 session when it owns the
     * lens (OEM aux cameras), otherwise uses CameraX focus-and-metering.
     */
    fun focusAt(view: View, x: Float, y: Float) {
        val session = camera2Session
        if (session != null) {
            session.focusAt(view, x, y)
            return
        }
        val cam = camera ?: return
        if (view.width <= 0 || view.height <= 0) return
        runCatching {
            val display = view.display ?: ContextCompat.getDisplayOrDefault(context)
            val factory = DisplayOrientedMeteringPointFactory(
                display,
                cam.cameraInfo,
                view.width.toFloat(),
                view.height.toFloat()
            )
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            cam.cameraControl.startFocusAndMetering(action)
        }.onFailure {
            Log.w(TAG, "Focus failed: ${it.message}")
        }
    }

    fun stop(clearBrokers: Boolean = true) {
        started.set(false)
        rebindGeneration++
        // Force-unbind everything this controller had attached. ProcessCameraProvider
        // is process-wide, so unbindAll() will also tear down the service's
        // bindings — but the service rebinds immediately in its own start(),
        // which runs in a coroutine and tolerates being kicked.
        runCatching { cameraProvider?.unbindAll() }
        camera2Session?.close()
        camera2Session = null
        analysisExecutor?.shutdownNow()
        analysisExecutor = null
        lifecycleOwner = null
        activeBinding = null
        previewView = null
        textureView = null
        primaryPreview = null
        analysis = null
        camera = null
        if (clearBrokers) {
            frameBroker.clear()
        }
    }

    private fun applyZoom() {
        val cam = camera ?: return
        runCatching {
            cam.cameraControl.setLinearZoom(config.linearZoom.coerceIn(0f, 1f))
        }.onFailure {
            Log.w(TAG, "Zoom failed: ${it.message}")
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCamera(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        binding: CameraInventory.CameraBinding,
        previewView: PreviewView?,
        textureView: TextureView?
    ) {
        val availableIds = provider.availableCameraInfos.map {
            Camera2CameraInfo.from(it).cameraId
        }
        Log.i(
            TAG,
            "CameraX available ids=$availableIds requested=${binding.camera.id} " +
                "openId=${binding.openId} physicalId=${binding.physicalId}"
        )

        when {
            binding.openId in availableIds -> {
                // Ensure Camera2 TextureView is hidden when CameraX owns preview.
                textureView?.visibility = android.view.View.GONE
                previewView?.visibility = android.view.View.VISIBLE
                bindWithCameraX(provider, lifecycleOwner, binding, previewView)
            }
            binding.camera.id in availableIds -> {
                textureView?.visibility = android.view.View.GONE
                previewView?.visibility = android.view.View.VISIBLE
                bindWithCameraX(
                    provider,
                    lifecycleOwner,
                    binding.copy(openId = binding.camera.id, physicalId = null),
                    previewView
                )
            }
            else -> {
                // OEM aux camera not registered with CameraX — open via Camera2.
                Log.i(TAG, "Falling back to Camera2 for id=${binding.camera.id}")
                primaryPreview = null
                camera = null
                val session = Camera2StreamSession(
                    context = context,
                    cameraId = binding.camera.id,
                    frameBroker = frameBroker,
                    initialConfig = config,
                    previewView = previewView,
                    textureView = textureView
                )
                camera2Session = session
                session.start()
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindWithCameraX(
        provider: ProcessCameraProvider,
        lifecycleOwner: LifecycleOwner,
        binding: CameraInventory.CameraBinding,
        previewView: PreviewView?
    ) {
        val selector = CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                cameraInfos.filter { Camera2CameraInfo.from(it).cameraId == binding.openId }
            }
            .build()
        // Reuse existing analysis if available, otherwise create new one
        val analysis = this.analysis ?: buildAnalysis(binding.physicalId).also { this.analysis = it }
        if (previewView != null) {
            val preview = buildPreview(previewView, binding.physicalId)
            primaryPreview = preview
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } else {
            primaryPreview = null
            camera = provider.bindToLifecycle(lifecycleOwner, selector, analysis)
        }
    }

    private fun resolutionSelector(): ResolutionSelector {
        val lw = config.resolution.landscapeWidth
        val lh = config.resolution.landscapeHeight
        val target = Size(max(lw, lh), min(lw, lh))
        return ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    target,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                )
            )
            .build()
    }

    /**
     * Re-apply the preview transform on every live preview surface so the
     * 180° rotation toggle takes effect immediately on the local display.
     * Called from [MainActivity] when the user toggles the rotate180 checkbox.
     *
     * For the CameraX path (main cameras), instead of fighting
     * with CameraX's internal transform system, we rotate the
     * graphical component at the View level — we remove the PreviewView
     * from its parent, apply the rotation, and re-attach it.
     * This preserves the rotation because the View retains the property
     * even after re-attach, and CameraX when it rebinds to the
     * surface sees the view already rotated.
     */
    fun refreshPreviewTransform() {
        // Update the CameraX PreviewView rotation.
        applyPreviewScale(previewView)
        // Update the Camera2 TextureView preview transform (for aux / OEM
        // cameras that go through the Camera2 path).
        camera2Session?.forceReapplyPreviewTransform()
    }

    private fun applyPreviewScale(previewView: PreviewView?) {
        val view = previewView ?: return
        ContextCompat.getMainExecutor(context).execute {
            view.scaleType = PreviewView.ScaleType.FIT_CENTER
            // We do NOT rotate the PreviewView itself: CameraX
            // internally overrides its rotation whenever it binds the
            // surface, which would silently undo the toggle. Instead
            // we rotate the parent wrapper container that holds the
            // PreviewView (and the Camera2 TextureView). The wrapper is
            // identified by walking up the view tree looking for
            // rotate180Wrapper / rotate180WrapperFullscreen — those ids
            // are declared in activity_main.xml on the FrameLayouts that
            // wrap both preview surfaces.
            val wrapper = findRotate180Wrapper(view)
            val target = wrapper ?: view
            target.rotation = if (config.rotate180) 180f else 0f
        }
    }

    /**
     * Locate the wrapper FrameLayout that hosts the given preview view.
     * The wrappers are `rotate180Wrapper` (main preview card) and
     * `rotate180WrapperFullscreen` (fullscreen overlay). We find them
     * by walking up from the view's parent until we see a FrameLayout
     * with one of those ids. Returning the wrapper (not the view)
     * makes the rotation survive CameraX surface re-binding, which is
     * the whole point of the wrapper approach.
     */
    private fun findRotate180Wrapper(view: View): View? {
        var node: android.view.ViewParent? = view.parent
        while (node is android.view.ViewGroup) {
            val resId = node.id
            if (resId == com.androidcamera.webcam.R.id.rotate180Wrapper ||
                resId == com.androidcamera.webcam.R.id.rotate180WrapperFullscreen) {
                return node
            }
            node = node.parent
        }
        return null
    }

    @SuppressLint("UnsafeOptInUsageError")
    @RequiresApi(Build.VERSION_CODES.P)
    private fun buildPreview(previewView: PreviewView, physicalId: String?, manualEnabled: Boolean = false): Preview {
        val displayRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val builder = Preview.Builder()
            .setResolutionSelector(resolutionSelector())
            .setTargetRotation(displayRotation)
        // We deliberately do NOT set AF/AE/ISO/exposure options on
        // the Extender: those are managed by Camera2CameraControl
        // (applyManualOptions) on every capture request. Setting them here
        // would conflict with the Camera2CameraControl options and throw
        // "Option values conflicts" at runtime, killing the camera session.
        if (physicalId != null) {
            Camera2Interop.Extender(builder).setPhysicalCameraId(physicalId)
        }
        return builder.build().also {
            applyPreviewScale(previewView)
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    @RequiresApi(Build.VERSION_CODES.P)
    private fun buildAnalysis(physicalId: String?): ImageAnalysis {
        val executor = analysisExecutor ?: Executors.newSingleThreadExecutor().also {
            analysisExecutor = it
        }
        val yuvExtractor = YuvRawExtractor()

        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(resolutionSelector())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetRotation(Surface.ROTATION_0)

        // Here a request is made to the camera sensor to get
        // the number of frames per second chosen by the user.
        // The sensor automatically selects the closest supported value
        // to the one requested (it may therefore be slightly different).
        Camera2Interop.Extender(builder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            android.util.Range(config.fps.fps, config.fps.fps)
        )

        if (physicalId != null) {
            Camera2Interop.Extender(builder).setPhysicalCameraId(physicalId)
        }

        return builder.build().also { analysis ->
            analysis.setAnalyzer(executor) { imageProxy ->
                try {
                    lastPublishNs.set(System.nanoTime())

                    val cfg = config
                    // FPS cap (publish-side drop). When the user has
                    // enabled "Limit FPS", drop frames that arrive
                    // before the configured inter-frame interval has
                    // elapsed since the last published frame. The
                    // camera sensor keeps delivering at its negotiated
                    // rate (so the local preview still looks smooth)
                    // but the broker — and therefore the stream
                    // forwarded to the PC — only sees at most
                    // cfg.fps frames per second.
                    if (cfg.limitFps) {
                        val nowNs = System.nanoTime()
                        val intervalNs = 1_000_000_000L / cfg.fps.fps.coerceAtLeast(1)
                        val allowedNs = nextPublishAllowedNs.get()
                        if (allowedNs != 0L && nowNs < allowedNs) {
                            return@setAnalyzer
                        }
                        nextPublishAllowedNs.set(nowNs + intervalNs)
                    }
                    val frontFacing = activeBinding?.camera?.facing == CameraFacing.FRONT
                    val rotation = cfg.orientation.bufferRotationDegrees(
                        imageProxy.imageInfo.rotationDegrees,
                        frontFacing = frontFacing,
                    )
                    // Independent 180° flip on top of the orientation-driven
                    // rotation. This lets the user flip the stream upside-down
                    // mid-stream without touching the orientation spinner
                    // (which is locked while the service is running). The
                    // rotation is applied at the I420 level so it works for
                    // both the raw YUV stream and the JPEG stream that comes
                    // out of the same per-frame buffer.
                    val finalRotation =
                        if (cfg.rotate180) (rotation + 180) % 360 else rotation
                    val processed = yuvExtractor.processToOutput(
                        imageProxy,
                        cfg.outputWidth,
                        cfg.outputHeight,
                        finalRotation
                    )
                    processed?.let { (w, h, i420) ->
                        if (cfg.streamFormat == StreamFormat.JPEG) {
                            // Convert I420 to JPEG with configurable quality.
                            // The YUV pipeline may pick a camera-native resolution
                            // that does not match (outputWidth, outputHeight) — when
                            // that happens the returned buffer is sized for the
                            // source and NOT (w, h); using (w, h) blindly would
                            // throw IndexOutOfBounds. Detect the actual source
                            // dimensions from the buffer length and prefer them.
                            val (jpegW, jpegH) = inferI420Size(i420, w, h)
                            if (jpegW <= 0 || jpegH <= 0) return@let
                            val jpegQuality = cfg.jpegQuality.quality
                            val jpegBytes = i420ToJpeg(i420, jpegW, jpegH, jpegQuality)
                            if (jpegBytes != null) {
                                frameBroker.publish(
                                    format = StreamFormat.JPEG,
                                    width = jpegW,
                                    height = jpegH,
                                    rotationDegrees = 0,
                                    payload = jpegBytes
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
                    imageProxy.close()
                }
            }
        }
    }

    private suspend fun awaitCameraProvider(): ProcessCameraProvider =
        suspendCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    try {
                        cont.resume(future.get())
                    } catch (e: Exception) {
                        cont.resumeWithException(e)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
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
                Log.w(
                    TAG,
                    "I420 buffer recovered ${recoveredW}x${targetH} (was target ${targetW}x${targetH})"
                )
                return recoveredW to targetH
            }
        }
        // Try height-first heuristic — assume width is correct and recover h.
        if (targetW > 0) {
            val perRow = i420.size.toDouble() / (targetW * 1.5)
            val recoveredH = (perRow).toInt()
            if (recoveredH > 0 && targetW * recoveredH * 3 / 2 == i420.size) {
                Log.w(
                    TAG,
                    "I420 buffer recovered ${targetW}x${recoveredH} (was target ${targetW}x${targetH})"
                )
                return targetW to recoveredH
            }
        }
        // Last resort — log and fall through; the conversion will likely fail
        // and skip the frame, surfacing the issue instead of crashing.
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

    companion object {
        private const val TAG = "CameraStreamController"
    }
}
