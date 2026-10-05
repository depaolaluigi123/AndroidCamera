package com.androidcamera.webcam.service

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.TextureView
import androidx.camera.view.PreviewView
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import com.androidcamera.webcam.R
import com.androidcamera.webcam.camera.CameraInventory
import com.androidcamera.webcam.camera.CameraLabels
import com.androidcamera.webcam.camera.CameraStreamController
import com.androidcamera.webcam.connection.ConnectionInfoFactory
import com.androidcamera.webcam.connection.NetworkAddressResolver
import com.androidcamera.webcam.data.PreferencesRepository
import com.androidcamera.webcam.model.ConnectionMode
import com.androidcamera.webcam.model.ServiceUiState
import com.androidcamera.webcam.model.StreamConfig
import com.androidcamera.webcam.streaming.StreamHttpServer
import com.androidcamera.webcam.streaming.UsbDeviceStreamServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Foreground service that owns cameras + stream servers while streaming.
 * HTTP for USB Tethering / IP; binary ACUS for USB Device.
 */
class CameraStreamService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var preferences: PreferencesRepository
    private lateinit var notificationFactory: StreamNotificationFactory
    private lateinit var cameraController: CameraStreamController
    private lateinit var connectionInfoFactory: ConnectionInfoFactory

    private var streamServer: StreamHttpServer? = null
    private var usbDeviceServer: UsbDeviceStreamServer? = null
    private var startJob: Job? = null
    private val running = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        instanceRef = WeakReference(this)
        preferences = PreferencesRepository(this)
        notificationFactory = StreamNotificationFactory(this)
        cameraController = CameraStreamController(this)
        connectionInfoFactory = ConnectionInfoFactory(NetworkAddressResolver(this))
        notificationFactory.ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START, null -> startStreaming(intent)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instanceRef?.get() === this) {
            instanceRef = null
        }
        stopStreaming()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun startStreaming(intent: Intent?) {
        val mode = intent?.getStringExtra(EXTRA_CONNECTION_MODE)
            ?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() }
            ?: preferences.connectionMode
        val preferredId = intent?.getStringExtra(EXTRA_CAMERA_ID)
            ?: preferences.selectedCameraId
        if (preferredId != null) {
            preferences.selectedCameraId = preferredId
        }
        val port = intent?.getIntExtra(EXTRA_PORT, preferences.httpPort) ?: preferences.httpPort
        // Use the pending config (which includes transient fields like
        // rotate180) when the Activity set one before we finished starting.
        // Falls back to persisted preferences when there is no pending config.
        val pendingCfg = pendingStreamConfig
        pendingStreamConfig = null  // consume once
        val streamConfig = pendingCfg ?: preferences.streamConfig()

        preferences.connectionMode = mode
        preferences.httpPort = port

        val placeholderLabel = getString(R.string.camera_facing_unknown_short)
        val notification = notificationFactory.build(placeholderLabel, isActive = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                StreamNotificationFactory.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(StreamNotificationFactory.NOTIFICATION_ID, notification)
        }

        if (running.get() || startJob?.isActive == true) {
            // A previous start is still in flight; let it complete before
            // we kick off another one. Without this guard each "Start service"
            // click (or repeated intent delivery) spawned a new coroutine that
            // called cameraController.start(), which tears down the analysis
            // and prevents frames from ever being published.
            Log.i(TAG, "startStreaming ignored: a previous start is still running")
            return
        }

        startJob = scope.launch {
            try {
                val camera = CameraInventory.resolve(this@CameraStreamService, preferences)
                    ?: error("No cameras available on this device")
                val cameraLabel = CameraLabels.shortName(this@CameraStreamService, camera)
                // Bind to the Service first WITHOUT a preview view — when the
                // Activity is not visible yet, its PreviewView's surface is
                // not ready, and binding it here would block the capture
                // session waiting for the surface (5 s timeout → no frames).
                // When the Activity is visible it calls adoptCameraHost()
                // which calls cameraController.attachPreview() and binds the
                // ready surface.
                val activeId = cameraController.start(
                    lifecycleOwner = this@CameraStreamService,
                    cameraId = camera.id,
                    previewView = null,
                    textureView = textureViewRef?.get(),
                    streamConfig = streamConfig
                )
                when (mode) {
                    ConnectionMode.USB_DEVICE -> {
                        usbDeviceServer = UsbDeviceStreamServer(
                            port,
                            cameraController.frameBroker,
                            streamConfig
                        ).also { it.start() }
                    }
                    ConnectionMode.USB, ConnectionMode.IP -> {
                        streamServer = StreamHttpServer(
                            port,
                            cameraController.frameBroker,
                            { preferences.streamConfig() }
                        ).also { it.start() }
                    }
                }
                running.set(true)
                latestState = ServiceUiState(
                    isRunning = true,
                    camerasInUseLabel = CameraLabels.displayName(this@CameraStreamService, camera),
                    endpoints = connectionInfoFactory.buildEndpoints(mode, port)
                )
                notifyStateChanged()
                val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(
                    StreamNotificationFactory.NOTIFICATION_ID,
                    notificationFactory.build(cameraLabel, isActive = true)
                )
                Log.i(
                    TAG,
                    "Streaming camera id=$activeId label=$cameraLabel mode=$mode port=$port"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start streaming", e)
                latestState = ServiceUiState(
                    isRunning = false,
                    errorMessage = e.message ?: e.javaClass.simpleName
                )
                notifyStateChanged()
                stopStreaming()
                stopSelf()
            }
        }
    }

    private fun stopStreaming(keepForeground: Boolean = false) {
        startJob?.cancel()
        startJob = null
        running.set(false)
        runCatching { cameraController.stop() }
        runCatching { streamServer?.stop() }
        streamServer = null
        runCatching { usbDeviceServer?.stop() }
        usbDeviceServer = null
        latestState = ServiceUiState(isRunning = false)
        notifyStateChanged()
        if (!keepForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun attachPreviewInternal(previewView: PreviewView?, textureView: TextureView?) {
        cameraController.attachPreview(previewView, textureView)
    }

    private fun adoptCameraHostInternal(
        owner: androidx.lifecycle.LifecycleOwner?,
        previewView: PreviewView?,
        textureView: TextureView?
    ) {
        if (owner != null) {
            // If we already have a camera session running, just attach the new preview surfaces
            // without doing a full rebind. This avoids the pause when switching between
            // normal and fullscreen preview in the same Activity.
            if (cameraController.isStarted()) {
                cameraController.attachPreview(previewView, textureView)
            } else {
                cameraController.rebind(owner, previewView, textureView)
            }
        } else {
            // Activity gone: keep streaming under the Service, without Preview.
            cameraController.rebind(this, previewView = null, textureView = null)
        }
    }

    companion object {
        private const val TAG = "CameraStreamService"
        const val ACTION_START = "com.androidcamera.webcam.action.START"
        const val ACTION_STOP = "com.androidcamera.webcam.action.STOP"
        const val EXTRA_CONNECTION_MODE = "extra_connection_mode"
        const val EXTRA_CAMERA_ID = "extra_camera_id"
        const val EXTRA_PORT = "extra_port"

        @Volatile
        private var instanceRef: WeakReference<CameraStreamService>? = null

        @Volatile
        private var previewViewRef: WeakReference<PreviewView>? = null

        @Volatile
        private var textureViewRef: WeakReference<TextureView>? = null

        @Volatile
        var latestState: ServiceUiState = ServiceUiState()
            private set

        @Volatile
        var stateListener: ((ServiceUiState) -> Unit)? = null

        /**
         * Pending stream config set by the Activity before the service has
         * fully started. The service reads this in [startStreaming] to
         * pick up transient fields (like rotate180) that are not persisted
         * to SharedPreferences. Cleared after use.
         */
        @Volatile
        private var pendingStreamConfig: StreamConfig? = null

        fun notifyStateChanged() {
            stateListener?.invoke(latestState)
        }

        fun setPreviewView(previewView: PreviewView?, textureView: TextureView? = null) {
            previewViewRef = previewView?.let { WeakReference(it) }
            textureViewRef = textureView?.let { WeakReference(it) }
            instanceRef?.get()?.attachPreviewInternal(previewView, textureView)
        }

        /**
         * Hand camera ownership to the Activity (with preview) or back to the Service
         * (analysis-only) when the UI is backgrounded.
         */
        fun adoptCameraHost(
            owner: androidx.lifecycle.LifecycleOwner?,
            previewView: PreviewView?,
            textureView: TextureView? = null
        ) {
            if (previewView != null || textureView != null) {
                previewViewRef = previewView?.let { WeakReference(it) }
                textureViewRef = textureView?.let { WeakReference(it) }
            } else if (owner == null) {
                previewViewRef = null
                textureViewRef = null
            }
            instanceRef?.get()?.adoptCameraHostInternal(owner, previewView, textureView)
        }

        fun setLinearZoom(linearZoom: Float) {
            instanceRef?.get()?.cameraController?.setLinearZoom(linearZoom)
        }

        fun focusAt(view: android.view.View, x: Float, y: Float) {
            instanceRef?.get()?.cameraController?.focusAt(view, x, y)
        }

        fun updateStreamConfig(streamConfig: StreamConfig) {
            instanceRef?.get()?.let { service ->
                service.cameraController.updateStreamConfig(streamConfig)
                // Also update the stream server so it uses the new FPS target.
                if (service.streamServer != null) {
                    service.streamServer?.updateConfig(streamConfig)
                }
            }
        }

        /**
         * Re-apply the preview transform on every live preview surface
         * (both CameraX PreviewView and Camera2 TextureView) so the
         * rotate180 toggle takes immediate visual effect.
         * Called from [MainActivity.rotate180Listener].
         */
        fun reapplyPreviewTransform() {
            instanceRef?.get()?.cameraController?.refreshPreviewTransform()
        }

        fun setManualFocusEnabled(enabled: Boolean) {
            instanceRef?.get()?.cameraController?.setManualFocusEnabled(enabled)
        }

        /**
         * Lock / unlock ISO + exposure time independently of the manual-focus
         * checkbox. Forwards to [CameraStreamController.setManualAdjustmentsEnabled].
         */
        fun setManualAdjustmentsEnabled(enabled: Boolean) {
            instanceRef?.get()?.cameraController?.setManualAdjustmentsEnabled(enabled)
        }

        fun setFocusDistance(distance: Float) {
            instanceRef?.get()?.cameraController?.setFocusDistance(distance)
        }

        fun setIso(iso: Int) {
            instanceRef?.get()?.cameraController?.setIso(iso)
        }

        fun setExposureTimeUs(exposureTimeUs: Long) {
            instanceRef?.get()?.cameraController?.setExposureTimeUs(exposureTimeUs)
        }

        fun setFlashTorch(enabled: Boolean) {
            instanceRef?.get()?.cameraController?.setFlashTorch(enabled)
        }

        /**
         * Toggle the outgoing-stream FPS cap. See
         * [CameraStreamController.setLimitFps] for the contract.
         */
        fun setLimitFps(enabled: Boolean) {
            instanceRef?.get()?.cameraController?.setLimitFps(enabled)
        }

        /**
         * Switch the active camera (lens) while streaming is live. The
         * service rebinds the controller to the new cameraId without
         * tearing down the HTTP / ACUS stream server. Returns the resolved
         * camera id (which may differ from the request when CameraX forces
         * a logical camera).
         */
        fun switchCamera(cameraId: String): String? =
            instanceRef?.get()?.cameraController?.switchCamera(cameraId)

        /** Returns true when the currently active lens is a rear-facing camera. */
        fun isActiveCameraRear(): Boolean =
            instanceRef?.get()?.cameraController?.isRearCamera() ?: false

        /**
         * Returns the encoder backend name used for the most recent JPEG
         * frame published by the running camera session, or null when the
         * service is idle / has not produced a JPEG frame yet.
         *
         * Used by the Activity to populate the HW/SW indicator under the
         * YUV/JPEG selector.
         */
        fun latestJpegEncoderBackend(): String? =
            instanceRef?.get()?.cameraController?.frameBroker?.latest()?.encoderBackend?.name

        fun start(
            context: android.content.Context,
            mode: ConnectionMode,
            cameraId: String,
            port: Int
        ) {
            val intent = Intent(context, CameraStreamService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONNECTION_MODE, mode.name)
                putExtra(EXTRA_CAMERA_ID, cameraId)
                putExtra(EXTRA_PORT, port)
            }
            context.startForegroundService(intent)
        }

        /**
         * Store a pending stream config for the next service start.
         * Transient fields like [StreamConfig.rotate180] are not
         * persisted to SharedPreferences; the Activity calls this
         * before [start] so the service can pick them up.
         */
        fun setPendingStreamConfig(config: StreamConfig) {
            pendingStreamConfig = config
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, CameraStreamService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
