package com.androidcamera.webcam.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.androidcamera.webcam.AndroidCameraApp
import com.androidcamera.webcam.R
import com.androidcamera.webcam.camera.CameraInventory
import com.androidcamera.webcam.camera.CameraLabels
import com.androidcamera.webcam.camera.CameraStreamController
import com.androidcamera.webcam.connection.ConnectionInfoFactory
import com.androidcamera.webcam.connection.NetworkAddressResolver
import com.androidcamera.webcam.data.PreferencesRepository
import com.androidcamera.webcam.data.WebcamSettingsStore
import com.androidcamera.webcam.databinding.ActivityMainBinding
import com.androidcamera.webcam.locale.LocaleManager
import com.androidcamera.webcam.model.AppLanguage
import com.androidcamera.webcam.model.AppThemeMode
import com.androidcamera.webcam.model.AvailableCamera
import com.androidcamera.webcam.model.ConnectionMode
import com.androidcamera.webcam.model.JpegQuality
import com.androidcamera.webcam.model.ServiceUiState
import com.androidcamera.webcam.model.StreamConfig
import com.androidcamera.webcam.model.StreamFps
import com.androidcamera.webcam.model.StreamFormat
import com.androidcamera.webcam.model.StreamOrientation
import com.androidcamera.webcam.model.StreamResolution
import com.androidcamera.webcam.service.CameraStreamService
import com.androidcamera.webcam.theme.ThemeManager
import kotlinx.coroutines.launch

/**
 * Main UI: connection mode, cameras, stream quality, theme/language, service control.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: PreferencesRepository
    private lateinit var themeManager: ThemeManager
    private lateinit var localeManager: LocaleManager
    private lateinit var connectionInfoFactory: ConnectionInfoFactory
    private lateinit var previewController: CameraStreamController

    private var availableCameras: List<AvailableCamera> = emptyList()
    private var serviceRunning = false
    private var updatingUi = false
    private var fullscreenPreview = false
    /** Job for the [webcamSettings] observer launched in [onStart];
     *  cancelled in [onStop] so the callback never fires after the
     *  Activity is no longer visible. */
    private var webcamObserverJob: kotlinx.coroutines.Job? = null
    /**
     * Read-only view on the shared webcam settings (single source of truth
     * for every UI surface). Both this Activity and the fullscreen
     * preview overlay read from / write to [webcamSettings]; the
     * previous ``var flashOn``, ``var manualFocusEnabled``, … fields are
     * now derived from this store so toggling a control on one UI
     * automatically updates the other.
     */
    private lateinit var webcamSettings: WebcamSettingsStore
    /** Convenience flag mirroring the latest ``webcamSettings.state``.
     *  Kept so the existing ``flashOn`` / ``manualFocusEnabled`` usages
     *  keep working without sprinkling ``webcamSettings.current().*``
     *  across the file. We refresh it whenever the store publishes. */
    private var flashOn: Boolean = false
        set(value) {
            field = value
            updateFlashUi(value)
        }
    private var manualFocusEnabled: Boolean = false
    /** Mirrors [preferences.manualAdjustmentsEnabled]. When the user toggles
     *  the "Manual adjustments" checkbox we save it, flip this flag and show
     *  the ISO/exposure spinner panel. */
    private var manualAdjustmentsEnabled: Boolean = false
    /** Mirrors [preferences.limitFps]. When true, the running session drops
     *  sensor frames that arrive faster than the user-selected FPS so the
     *  stream forwarded to the PC never exceeds that rate. */
    private var limitFpsEnabled: Boolean = false
    /** Mirrors [webcamSettings.current().rotate180]. When true, every
     *  outgoing frame is rotated an additional 180° on top of the rotation
     *  implied by the orientation spinner. Editable mid-stream. Not
     *  persisted across process restarts (transient live setting). */
    private var rotate180Enabled: Boolean = false
    /** Set to true while [maybeStartIdlePreview] is scheduling a start(); reset
     *  on Activity destroy. Used to coalesce the many [updateServiceUi] /
     *  [applyLaunchExtras] notifications into a single start, which would
     *  otherwise repeatedly tear down and rebind the camera and the analysis
     *  (resulting in no frames ever being published). */
    private var idlePreviewStarting = false
    /** True when [startServiceInternal] has fired (and we're waiting for the
     *  service to bind the camera). Used to suppress the activity's own idle
     *  preview, which would otherwise fight the service's controller for
     *  the process-wide ProcessCameraProvider. */
    private var serviceStartRequested = false
    /** True when the current permission launch is a startup check
     *  (don't auto-start the service after permissions are granted). */
    private var startupPermissionRequest = false
    /** True when the activity was launched with `start_service` in the intent.
     *  Captured before [applyLaunchExtras] removes the extra, so the guard
     *  fires even on the very first [updateServiceUi] call from onCreate. */
    private var launchedWithStartServiceIntent = false
    private val encoderProbeHandler = Handler(Looper.getMainLooper())
    private val encoderProbeRunnable = object : Runnable {
        override fun run() {
            refreshJpegEncoderIndicator()
            encoderProbeHandler.postDelayed(this, 1000L)
        }
    }

    private val fullscreenBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitFullscreenPreview()
        }
    }

    private val focusTouchListener = View.OnTouchListener { v, event ->
        // Treat a tap (down→up without drag) as a focus request on the preview.
        if (event.action == MotionEvent.ACTION_UP) {
            handleFocusTap(v, event.x, event.y)
        }
        true
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val cameraGranted = result[Manifest.permission.CAMERA] == true
        if (cameraGranted) {
            setupCameraSpinner()
            if (!startupPermissionRequest) {
                startServiceInternal()
            }
        } else {
            Toast.makeText(this, R.string.permission_camera_required, Toast.LENGTH_LONG).show()
        }
        startupPermissionRequest = false
    }

    override fun attachBaseContext(newBase: Context) {
        val prefs = PreferencesRepository(newBase)
        val locale = LocaleManager(prefs)
        super.attachBaseContext(locale.wrapWithSavedLocale(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val app = application as AndroidCameraApp
        preferences = app.preferences
        themeManager = app.themeManager
        localeManager = LocaleManager(preferences)
        webcamSettings = app.webcamSettings
        connectionInfoFactory = ConnectionInfoFactory(NetworkAddressResolver(this))
        previewController = CameraStreamController(this)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Mirror the persisted snapshot into the local convenience
        // fields so the rest of this Activity (and the fullscreen
        // overlay) start from a consistent state.
        syncLocalFromStore()

        setupSpinners()
        setupCameraSpinner()
        bindInitialState()
        setupListeners()
        onBackPressedDispatcher.addCallback(this, fullscreenBackCallback)
        checkPermissionsAtStartup()
        applyThemeColorsFromXml()
        refreshObsInfo()
        // Capture the start_service flag BEFORE applyLaunchExtras consumes
        // it (applyLaunchExtras removes the extra after handling it once).
        launchedWithStartServiceIntent = intent?.getBooleanExtra(EXTRA_START_SERVICE, false) == true
        updateServiceUi(CameraStreamService.latestState)
        applyLaunchExtras(intent)
        // If the launch intent requested service start, the service owns the
        // camera and we should NOT also start the activity's idle preview.
        // The activity's previewController would otherwise fight the service's
        // cameraController for the process-wide ProcessCameraProvider.
        if (!launchedWithStartServiceIntent) {
            maybeStartIdlePreview()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchExtras(intent)
    }

    /**
     * Optional ADB/automation hooks:
     *   --es camera_id 21
     *   --es connection_mode USB_DEVICE
     *   --ez start_service true
     *   --ez stop_service true
     */
    private fun applyLaunchExtras(intent: Intent?) {
        if (intent == null) return
        val modeName = intent.getStringExtra(EXTRA_CONNECTION_MODE)?.trim().orEmpty()
        if (modeName.isNotEmpty()) {
            runCatching { ConnectionMode.valueOf(modeName) }.getOrNull()?.let { mode ->
                preferences.connectionMode = mode
                updatingUi = true
                when (mode) {
                    ConnectionMode.USB -> binding.connectionToggle.check(R.id.btnModeUsb)
                    ConnectionMode.USB_DEVICE -> binding.connectionToggle.check(R.id.btnModeUsbDevice)
                    ConnectionMode.IP -> binding.connectionToggle.check(R.id.btnModeIp)
                }
                updatingUi = false
                updateConnectionDescription()
                refreshObsInfo()
            }
        }
        val cameraId = intent.getStringExtra(EXTRA_CAMERA_ID)?.trim().orEmpty()
        if (cameraId.isNotEmpty()) {
            preferences.selectedCameraId = cameraId
            setupCameraSpinner()
            if (!serviceRunning) maybeStartIdlePreview()
        }
        when {
            intent.getBooleanExtra(EXTRA_STOP_SERVICE, false) -> {
                if (serviceRunning) CameraStreamService.stop(this)
            }
            intent.getBooleanExtra(EXTRA_START_SERVICE, false) -> {
                if (!serviceRunning) ensurePermissionsAndStart()
            }
        }
        // Consume one-shot flags so recreate() does not re-trigger them.
        intent.removeExtra(EXTRA_START_SERVICE)
        intent.removeExtra(EXTRA_STOP_SERVICE)
        intent.removeExtra(EXTRA_CONNECTION_MODE)
    }

    override fun onStart() {
        super.onStart()
        CameraStreamService.stateListener = { state ->
            runOnUiThread { updateServiceUi(state) }
        }
        // Subscribe to the shared webcam-settings flow for the lifetime
        // of this Activity so any change coming from the service (a
        // future Composables / Fragment pipeline) or from the persisted
        // snapshot gets reflected in the UI without manual calls. The
        // collector runs on the main dispatcher (the default for
        // lifecycleScope.launch).
        webcamObserverJob?.cancel()
        webcamObserverJob = lifecycleScope.launch {
            webcamSettings.state.collect { cfg ->
                syncLocalFromStore()
                // Re-apply values on the controls that haven't been
                // touched in this emission; ``updatingUi`` guards the
                // listeners from re-firing publish().
                applyConfigToControls(cfg)
            }
        }
        updateServiceUi(CameraStreamService.latestState)
        if (CameraStreamService.latestState.isRunning) {
            // Activity owns the camera while visible so preview stays live.
            CameraStreamService.adoptCameraHost(
                this,
                activePreviewView(),
                activeTextureView()
            )
        }
        encoderProbeHandler.post(encoderProbeRunnable)
    }

    override fun onStop() {
        webcamObserverJob?.cancel()
        webcamObserverJob = null
        encoderProbeHandler.removeCallbacks(encoderProbeRunnable)
        CameraStreamService.stateListener = null
        if (serviceRunning) {
            // Keep YUV streaming under the Service; release the UI surface.
            CameraStreamService.adoptCameraHost(null, null, null)
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (!serviceRunning) {
            previewController.stop()
        }
        super.onDestroy()
    }

    private fun setupSpinners() {
        refreshResolutionSpinnerLabels()
        binding.fpsSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            StreamFps.entries.map { "${it.fps}" }
        )
        binding.orientationSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.orientation_portrait),
                getString(R.string.orientation_landscape),
                getString(R.string.orientation_portrait_inverted),
                getString(R.string.orientation_landscape_inverted)
            )
        )
        binding.streamFormatSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf(
                getString(R.string.stream_format_yuv),
                getString(R.string.stream_format_jpeg)
            )
        )
        binding.jpegQualitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            JpegQuality.entries.map { it.label }
        )
        // ISO spinner values (same list for main and fullscreen overlay).
        // The list lives in [isoLabels] so the fullscreen mirror stays in
        // sync with whatever we change here.
        val isoLabels = isoLabels()
        binding.isoSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            isoLabels
        )
        binding.isoSpinnerFullscreen.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            isoLabels
        )
        // Exposure time spinner values (same list for main and fullscreen).
        // The list lives in [exposureLabels] so the fullscreen mirror stays in
        // sync with whatever we change here.
        val exposureLabels = exposureLabels()
        binding.exposureSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            exposureLabels
        )
        binding.exposureSpinnerFullscreen.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            exposureLabels
        )
    }

    private fun setupCameraSpinner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            availableCameras = emptyList()
            binding.cameraSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.permission_camera_required))
            )
            binding.cameraSpinnerFullscreen.adapter = binding.cameraSpinner.adapter
            binding.cameraSpinner.isEnabled = false
            binding.cameraSpinnerFullscreen.isEnabled = false
            return
        }

        availableCameras = CameraInventory.listCameras(this)
        if (availableCameras.isEmpty()) {
            binding.cameraSpinner.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.camera_none_available))
            )
            binding.cameraSpinnerFullscreen.adapter = binding.cameraSpinner.adapter
            binding.cameraSpinner.isEnabled = false
            binding.cameraSpinnerFullscreen.isEnabled = false
            return
        }

        val labels = availableCameras.map { CameraLabels.displayName(this, it) }
        val previousUpdating = updatingUi
        updatingUi = true
        val mainAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            labels
        )
        binding.cameraSpinner.adapter = mainAdapter
        binding.cameraSpinnerFullscreen.adapter = mainAdapter
        val selected = CameraInventory.resolve(this, preferences)
        val index = availableCameras.indexOfFirst { it.id == selected?.id }.coerceAtLeast(0)
        binding.cameraSpinner.setSelection(index)
        binding.cameraSpinnerFullscreen.setSelection(index)
        binding.cameraSpinner.isEnabled = !serviceRunning
        // Fullscreen spinner stays live even while streaming so the user
        // can switch lenses without dropping out of fullscreen first.
        binding.cameraSpinnerFullscreen.isEnabled = true
        updatingUi = previousUpdating
    }

    /**
     * Common handler for the main + fullscreen camera spinners. Reads the
     * currently-selected camera from [selectedCamera] (always the main
     * spinner — the source of truth) and forwards the new id to the
     * service so the live session rebinds on the requested lens. Syncs
     * the fullscreen spinner so the overlay's selection matches.
     */
    private fun onCameraSpinnerChanged() {
        val camera = selectedCamera() ?: return
        publish { cfg -> cfg.copy(cameraId = camera.id) }
        refreshObsInfo()
        // Keep the fullscreen mirror in sync without re-firing its
        // listener (the call is gated by ``updatingUi``).
        updatingUi = true
        if (binding.cameraSpinnerFullscreen.selectedItemPosition !=
            binding.cameraSpinner.selectedItemPosition
        ) {
            binding.cameraSpinnerFullscreen.setSelection(
                binding.cameraSpinner.selectedItemPosition,
                false,
            )
        }
        updatingUi = false
        // Allow camera change while streaming — forward the new id to
        // the live service so it rebinds to the requested lens without
        // requiring the user to stop the service first.
        if (serviceRunning) {
            CameraStreamService.switchCamera(camera.id)
            // Refresh UI flags (flash button enable / disable) on the
            // next frame so they reflect the newly-active lens.
            binding.root.post { updateServiceUi(CameraStreamService.latestState) }
        } else {
            maybeStartIdlePreview()
        }
    }

    private fun refreshResolutionSpinnerLabels() {
        val orientation = preferences.streamOrientation
        val selected = binding.resolutionSpinner.selectedItemPosition.coerceAtLeast(0)
        binding.resolutionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            StreamResolution.entries.map { it.labelFor(orientation) }
        )
        binding.resolutionSpinner.setSelection(
            selected.coerceIn(0, StreamResolution.entries.lastIndex)
        )
    }

    private fun bindInitialState() {
        updatingUi = true
        when (preferences.connectionMode) {
            ConnectionMode.USB -> binding.connectionToggle.check(R.id.btnModeUsb)
            ConnectionMode.USB_DEVICE -> binding.connectionToggle.check(R.id.btnModeUsbDevice)
            ConnectionMode.IP -> binding.connectionToggle.check(R.id.btnModeIp)
        }
        binding.orientationSpinner.setSelection(
            StreamOrientation.entries.indexOf(preferences.streamOrientation).coerceAtLeast(0)
        )
        refreshResolutionSpinnerLabels()
        binding.resolutionSpinner.setSelection(
            StreamResolution.entries.indexOf(preferences.streamResolution).coerceAtLeast(0)
        )
        binding.fpsSpinner.setSelection(
            StreamFps.entries.indexOf(preferences.streamFps).coerceAtLeast(0)
        )
        binding.streamFormatSpinner.setSelection(
            StreamFormat.entries.indexOf(preferences.streamFormat).coerceAtLeast(0)
        )
        binding.jpegQualitySpinner.setSelection(
            JpegQuality.entries.indexOf(preferences.jpegQuality).coerceAtLeast(0)
        )
        // "Limit FPS" toggle: when checked, frames the sensor delivers
        // above the user-selected FPS are dropped before reaching the
        // PC. Defaults to off (legacy behaviour: forward every frame).
        limitFpsEnabled = preferences.limitFps
        binding.limitFpsCheckbox.isChecked = limitFpsEnabled
        // "Rotate 180°" toggle. Live, not persisted across process restarts —
        // the default is "off", matching the legacy behaviour.
        rotate180Enabled = webcamSettings.current().rotate180
        binding.rotate180Checkbox.isChecked = rotate180Enabled
        binding.rotate180CheckboxFullscreen.isChecked = rotate180Enabled
        // ISO: stored as int (0 = Auto). Match the label for the spinner.
        val isoLabel = if (preferences.iso <= 0) "Auto" else preferences.iso.toString()
        val isoIdx = isoLabels().indexOf(isoLabel).coerceAtLeast(0)
        binding.isoSpinner.setSelection(isoIdx)
        binding.isoSpinnerFullscreen.setSelection(isoIdx)
        // Exposure: stored as microseconds (0 = Auto). Match the label for the
        // spinner (round-trip the value through parseExposureLabel).
        val exposureUs = preferences.exposureTimeUs
        val exposureLabel = exposureLabels().firstOrNull { label ->
            parseExposureLabel(label) == exposureUs
        } ?: "Auto"
        val exposureIdx = exposureLabels().indexOf(exposureLabel).coerceAtLeast(0)
        binding.exposureSpinner.setSelection(exposureIdx)
        binding.exposureSpinnerFullscreen.setSelection(exposureIdx)
        binding.zoomSeekBar.progress = (preferences.linearZoom * 100f).toInt().coerceIn(0, 100)
        binding.fullscreenZoomSeekBar.progress = binding.zoomSeekBar.progress
        // Manual focus defaults to OFF on a fresh install so the camera runs
        // in automatic mode. Once the user has explicitly toggled the checkbox
        // we remember the resulting state and restore it on subsequent launches
        // (the saved focusDistance slider value is honoured regardless).
        val manualFocusSaved = preferences.manualFocusTouched
        manualFocusEnabled = manualFocusSaved && preferences.manualFocusEnabled
        binding.manualFocusCheckbox.isChecked = manualFocusEnabled
        binding.manualFocusCheckboxFullscreen.isChecked = manualFocusEnabled
        binding.manualFocusControls.visibility =
            if (manualFocusEnabled) View.VISIBLE else View.GONE
        binding.manualFocusControlsFullscreen.visibility =
            if (manualFocusEnabled) View.VISIBLE else View.GONE
        binding.focusSeekBar.progress = (preferences.focusDistance * 1000f).toInt().coerceIn(0, 1000)
        binding.focusSeekBarFullscreen.progress = binding.focusSeekBar.progress

        // Same rule for "Manual adjustments (ISO, exposure)".
        val manualAdjSaved = preferences.manualAdjustmentsTouched
        manualAdjustmentsEnabled = manualAdjSaved && preferences.manualAdjustmentsEnabled
        binding.manualAdjustmentsCheckbox.isChecked = manualAdjustmentsEnabled
        binding.manualAdjustmentsCheckboxFullscreen.isChecked = manualAdjustmentsEnabled
        binding.manualAdjustmentsControls.visibility =
            if (manualAdjustmentsEnabled) View.VISIBLE else View.GONE
        binding.manualAdjustmentsControlsFullscreen.visibility =
            if (manualAdjustmentsEnabled) View.VISIBLE else View.GONE
        flashOn = preferences.flashTorchEnabled
        updateFlashUi(flashOn)
        when (preferences.themeMode) {
            AppThemeMode.LIGHT -> binding.themeToggle.check(R.id.btnThemeLight)
            AppThemeMode.DARK -> binding.themeToggle.check(R.id.btnThemeDark)
        }
        when (preferences.language) {
            AppLanguage.ENGLISH -> binding.languageToggle.check(R.id.btnLangEn)
            AppLanguage.ITALIAN -> binding.languageToggle.check(R.id.btnLangIt)
        }
        binding.deviceNameInput.setText(preferences.deviceName)
        binding.portInput.setText(preferences.httpPort.toString())
        updateConnectionDescription()
        applyPreviewOrientation()
        applyStreamFormatUiState()
        // Show a placeholder for the JPEG encoder indicator until a JPEG
        // frame is published and refreshJpegEncoderIndicator() fills it in.
        binding.jpegEncoderValue.text = getString(R.string.jpeg_encoder_pending)
        updatingUi = false
    }

    /**
     * Lock the JPEG-quality spinner when the user picked raw YUV: quality is a
     * JPEG-only concept, and the Python GUI shows "-" for it when the format
     * negotiated over the wire is YUV.
     */
    private fun applyStreamFormatUiState() {
        val isJpeg = preferences.streamFormat == StreamFormat.JPEG
        // Respect the running-state gate: while streaming all config spinners
        // are disabled elsewhere; do not fight that by enabling here.
        val running = serviceRunning
        binding.jpegQualitySpinner.isEnabled = isJpeg && !running
        binding.jpegQualitySpinner.alpha = if (isJpeg) 1f else 0.4f
        // The HW/SW JPEG encoder indicator is only meaningful in JPEG mode.
        binding.jpegEncoderLabel.visibility = if (isJpeg) View.VISIBLE else View.GONE
        binding.jpegEncoderValue.visibility = if (isJpeg) View.VISIBLE else View.GONE
        if (!isJpeg) {
            binding.jpegEncoderValue.text = getString(R.string.jpeg_encoder_pending)
        }
    }

    /**
     * Refresh the JPEG encoder indicator (HW / SW). The phone pipeline tags
     * every JPEG frame with the backend that produced it, so the latest frame
     * in the broker is the source of truth. When no JPEG frame is available
     * yet, we show "Detecting…".
     */
    private fun refreshJpegEncoderIndicator() {
        if (preferences.streamFormat != StreamFormat.JPEG) return
        val backend = CameraStreamService.latestJpegEncoderBackend()
            ?: previewController.frameBroker.latest()?.encoderBackend?.name
        val textRes = when (backend) {
            "HW_MEDIACODEC" -> R.string.jpeg_encoder_hw
            "SW_YUVIMAGE" -> R.string.jpeg_encoder_sw
            else -> R.string.jpeg_encoder_pending
        }
        binding.jpegEncoderValue.text = getString(textRes)
    }

    private fun setupListeners() {
        binding.connectionToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingUi) return@addOnButtonCheckedListener
            preferences.connectionMode = when (checkedId) {
                R.id.btnModeUsbDevice -> ConnectionMode.USB_DEVICE
                R.id.btnModeIp -> ConnectionMode.IP
                else -> ConnectionMode.USB
            }
            updateConnectionDescription()
            refreshObsInfo()
        }

        binding.cameraSpinner.onItemSelectedListener = spinnerListener {
            onCameraSpinnerChanged()
        }
        // Fullscreen overlay mirrors the main spinner so the user can
        // switch lenses without dropping out of fullscreen first.
        binding.cameraSpinnerFullscreen.onItemSelectedListener = spinnerListener {
            // The main spinner is the source of truth. If the selection
            // already matches, do nothing; otherwise forward the change
            // through the same path the main spinner uses, so the live
            // session rebinds on the new lens and the two spinners stay
            // in sync.
            val fsIdx = binding.cameraSpinnerFullscreen.selectedItemPosition
            if (binding.cameraSpinner.selectedItemPosition != fsIdx) {
                binding.cameraSpinner.setSelection(fsIdx, false)
                onCameraSpinnerChanged()
            }
        }

        binding.resolutionSpinner.onItemSelectedListener = spinnerListener {
            val newResolution = StreamResolution.entries[
                binding.resolutionSpinner.selectedItemPosition.coerceIn(0, StreamResolution.entries.lastIndex)
            ]
            publish { cfg -> cfg.copy(resolution = newResolution) }
            applyPreviewOrientation()
            refreshObsInfo()
            restartPreviewIfIdle()
        }
        binding.fpsSpinner.onItemSelectedListener = spinnerListener {
            val newFps = StreamFps.entries[
                binding.fpsSpinner.selectedItemPosition.coerceIn(0, StreamFps.entries.lastIndex)
            ]
            publish { cfg -> cfg.copy(fps = newFps) }
            val cfg = webcamSettings.current()
            if (serviceRunning) {
                CameraStreamService.updateStreamConfig(cfg)
            } else {
                previewController.updateStreamConfig(cfg)
            }
            restartPreviewIfIdle()
        }
        binding.orientationSpinner.onItemSelectedListener = spinnerListener {
            val newOrientation = StreamOrientation.entries[
                binding.orientationSpinner.selectedItemPosition.coerceIn(
                    0,
                    StreamOrientation.entries.lastIndex
                )
            ]
            publish { cfg -> cfg.copy(orientation = newOrientation) }
            refreshResolutionSpinnerLabels()
            applyPreviewOrientation()
            refreshObsInfo()
            restartPreviewIfIdle()
        }
        binding.streamFormatSpinner.onItemSelectedListener = spinnerListener {
            val newFormat = StreamFormat.entries[
                binding.streamFormatSpinner.selectedItemPosition.coerceIn(0, StreamFormat.entries.lastIndex)
            ]
            publish { cfg -> cfg.copy(streamFormat = newFormat) }
            // JPEG quality only makes sense for JPEG; lock the spinner (and the
            // GUI mirrors that with a "-") when the user picked raw YUV.
            applyStreamFormatUiState()
            val cfg = webcamSettings.current()
            if (serviceRunning) {
                CameraStreamService.updateStreamConfig(cfg)
            } else {
                previewController.updateStreamConfig(cfg)
            }
            restartPreviewIfIdle()
        }
        binding.jpegQualitySpinner.onItemSelectedListener = spinnerListener {
            val newQuality = JpegQuality.entries[
                binding.jpegQualitySpinner.selectedItemPosition.coerceIn(0, JpegQuality.entries.lastIndex)
            ]
            publish { cfg -> cfg.copy(jpegQuality = newQuality) }
            val cfg = webcamSettings.current()
            if (serviceRunning) {
                CameraStreamService.updateStreamConfig(cfg)
            } else {
                previewController.updateStreamConfig(cfg)
            }
            restartPreviewIfIdle()
        }

        // "Limit FPS" checkbox. When checked, the camera sensor keeps
        // running at whatever rate it negotiated with the system, but
        // only frames that arrive at or after the configured interval
        // are forwarded to the broker (and therefore to the PC).
        // Unchecked is the legacy behaviour: forward every frame.
        val limitFpsListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@OnCheckedChangeListener
            publish { cfg -> cfg.copy(limitFps = isChecked) }
            limitFpsEnabled = isChecked
            if (serviceRunning) {
                CameraStreamService.setLimitFps(isChecked)
            } else {
                previewController.setLimitFps(isChecked)
            }
        }
        binding.limitFpsCheckbox.setOnCheckedChangeListener(limitFpsListener)
        // "Rotate 180°" toggle: adds an extra 180° rotation on top of the
        // orientation spinner. Editable mid-stream — the change is propagated
        // to the running service via [CameraStreamService.updateStreamConfig]
        // which only updates the per-frame rotation step (no session rebind
        // required). The fullscreen mirror shares the same publish path so
        // toggling from either surface stays in sync.
        val rotate180Listener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@OnCheckedChangeListener
            publish { cfg -> cfg.copy(rotate180 = isChecked) }
            rotate180Enabled = isChecked
            // Push the new config straight into the running session so the
            // next emitted frame is rotated 180°. This works for both the
            // HTTP stream server and the ACUS bridge: both forward the
            // rotation-180-tagged I420 buffer unchanged.
            if (serviceRunning) {
                CameraStreamService.updateStreamConfig(webcamSettings.current())
            } else {
                previewController.updateStreamConfig(webcamSettings.current())
            }
            // Rotate the wrapper containers (rotate180Wrapper /
            // rotate180WrapperFullscreen) that hold the PreviewView and
            // Camera2 TextureView. Rotating the wrapper instead of the
            // PreviewView itself avoids CameraX silently overriding
            // view.rotation on every surface re-bind. The TextureView's
            // transform matrix no longer needs the 180° either (see
            // Camera2StreamSession.applyPreviewTransform).
            applyPreviewSurfaceTransform(binding.previewView)
            applyPreviewSurfaceTransform(binding.fullscreenPreviewView)
        }
        binding.rotate180Checkbox.setOnCheckedChangeListener(rotate180Listener)
        binding.rotate180CheckboxFullscreen.setOnCheckedChangeListener(rotate180Listener)
        binding.zoomSeekBar.setOnSeekBarChangeListener(zoomSeekBarListener(binding.zoomSeekBar))
        binding.fullscreenZoomSeekBar.setOnSeekBarChangeListener(
            zoomSeekBarListener(binding.fullscreenZoomSeekBar)
        )

        // Zoom +/- buttons
        binding.btnZoomMinus.setOnClickListener {
            adjustZoom(-5)
        }
        binding.btnZoomPlus.setOnClickListener {
            adjustZoom(5)
        }
        binding.btnFullscreenZoomMinus.setOnClickListener {
            adjustZoom(-5)
        }
        binding.btnFullscreenZoomPlus.setOnClickListener {
            adjustZoom(5)
        }

        // Manual focus toggle (main + fullscreen). Both checkboxes
        // route through the shared store so enabling on one UI updates
        // the other.
        val manualFocusListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@OnCheckedChangeListener
            publish { cfg -> cfg.copy(manualFocusEnabled = isChecked) }
            preferences.manualFocusTouched = true
            // Always reach the running session (service or idle preview) so
            // toggling manual focus takes effect mid-stream.
            if (serviceRunning) {
                CameraStreamService.setManualFocusEnabled(isChecked)
            } else {
                previewController.setManualFocusEnabled(isChecked)
            }
            binding.manualFocusControls.visibility =
                if (isChecked) View.VISIBLE else View.GONE
            binding.manualFocusControlsFullscreen.visibility =
                if (isChecked) View.VISIBLE else View.GONE
            // Sync the main ↔ fullscreen checkboxes without re-firing listeners.
            updatingUi = true
            if (binding.manualFocusCheckbox.isChecked != isChecked) {
                binding.manualFocusCheckbox.isChecked = isChecked
            }
            if (binding.manualFocusCheckboxFullscreen.isChecked != isChecked) {
                binding.manualFocusCheckboxFullscreen.isChecked = isChecked
            }
            updatingUi = false
            // When manual focus is enabled, disable tap-to-focus.
            // When disabled, re-enable it.
            if (isChecked) {
                binding.previewView.setOnTouchListener(null)
                binding.camera2PreviewView.setOnTouchListener(null)
                binding.fullscreenPreviewView.setOnTouchListener(null)
                binding.fullscreenCamera2PreviewView.setOnTouchListener(null)
            } else {
                // Re-attach tap-to-focus listeners
                binding.previewView.setOnTouchListener(focusTouchListener)
                binding.camera2PreviewView.setOnTouchListener(focusTouchListener)
                binding.fullscreenPreviewView.setOnTouchListener(focusTouchListener)
                binding.fullscreenCamera2PreviewView.setOnTouchListener(focusTouchListener)
            }
        }
        binding.manualFocusCheckbox.setOnCheckedChangeListener(manualFocusListener)
        binding.manualFocusCheckboxFullscreen.setOnCheckedChangeListener(manualFocusListener)

        // Manual adjustments toggle (ISO + exposure time).
        // Mirrors the main ↔ fullscreen checkboxes and shows / hides the
        // spinner panel. The sliders themselves stay available while
        // streaming so the user can tune ISO/exposure on the fly — the
        // controller applies them through Camera2CaptureRequest overrides
        // (no rebind required).
        //
        // The "manual adjustments" toggle is INDEPENDENT from "manual
        // focus": checking the ISO box alone is enough to make AE lock
        // and the user's ISO / exposure-time values take effect. The
        // user does NOT have to also tick "manual focus" first.
        val manualAdjustmentsListener = CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (updatingUi) return@OnCheckedChangeListener
            publish { cfg -> cfg.copy(manualAdjustmentsEnabled = isChecked) }
            preferences.manualAdjustmentsTouched = true
            // Show/hide the ISO + exposure controls panel.
            binding.manualAdjustmentsControls.visibility =
                if (isChecked) View.VISIBLE else View.GONE
            binding.manualAdjustmentsControlsFullscreen.visibility =
                if (isChecked) View.VISIBLE else View.GONE
            // Sync the main ↔ fullscreen checkboxes without re-firing listeners.
            updatingUi = true
            if (binding.manualAdjustmentsCheckbox.isChecked != isChecked) {
                binding.manualAdjustmentsCheckbox.isChecked = isChecked
            }
            if (binding.manualAdjustmentsCheckboxFullscreen.isChecked != isChecked) {
                binding.manualAdjustmentsCheckboxFullscreen.isChecked = isChecked
            }
            updatingUi = false
            // Forward the gate directly so the controller can latch AE off
            // (or back to auto) without waiting for the user to touch the
            // ISO / exposure sliders afterwards. Then re-apply the
            // currently-saved ISO / exposure values so the new gate state
            // takes immediate effect.
            val cfg = webcamSettings.current()
            if (serviceRunning) {
                CameraStreamService.setManualAdjustmentsEnabled(isChecked)
                CameraStreamService.setIso(cfg.iso)
                CameraStreamService.setExposureTimeUs(cfg.exposureTimeUs)
            } else {
                previewController.setManualAdjustmentsEnabled(isChecked)
                previewController.setIso(cfg.iso)
                previewController.setExposureTimeUs(cfg.exposureTimeUs)
            }
        }
        binding.manualAdjustmentsCheckbox.setOnCheckedChangeListener(manualAdjustmentsListener)
        binding.manualAdjustmentsCheckboxFullscreen.setOnCheckedChangeListener(manualAdjustmentsListener)

        // Focus slider with +/- buttons (main)
        binding.btnFocusMinus.setOnClickListener {
            adjustFocus(-50)
        }
        binding.btnFocusPlus.setOnClickListener {
            adjustFocus(50)
        }
        binding.focusSeekBar.setOnSeekBarChangeListener(
            focusSeekBarListener(binding.focusSeekBar)
        )
        // Focus slider with +/- buttons (fullscreen mirror)
        binding.btnFocusMinusFullscreen.setOnClickListener {
            adjustFocus(-50)
        }
        binding.btnFocusPlusFullscreen.setOnClickListener {
            adjustFocus(50)
        }
        binding.focusSeekBarFullscreen.setOnSeekBarChangeListener(
            focusSeekBarListener(binding.focusSeekBarFullscreen)
        )

        // ISO spinner (main)
        val isoListener = spinnerListener {
            if (updatingUi) return@spinnerListener
            val selected = binding.isoSpinner.selectedItem.toString()
            val iso = when (selected) {
                "Auto" -> 0
                else -> selected.toIntOrNull() ?: 0
            }
            publish { cfg -> cfg.copy(iso = iso) }
            // Keep the fullscreen spinner visually in sync with the main one.
            val fsPos = isoLabels().indexOf(selected).coerceAtLeast(0)
            if (binding.isoSpinnerFullscreen.selectedItemPosition != fsPos) {
                updatingUi = true
                binding.isoSpinnerFullscreen.setSelection(fsPos)
                updatingUi = false
            }
            if (serviceRunning) {
                CameraStreamService.setIso(iso)
            } else {
                previewController.setIso(iso)
            }
        }
        binding.isoSpinner.onItemSelectedListener = isoListener
        // ISO spinner (fullscreen mirror)
        binding.isoSpinnerFullscreen.onItemSelectedListener = spinnerListener {
            if (updatingUi) return@spinnerListener
            val selected = binding.isoSpinnerFullscreen.selectedItem.toString()
            val iso = when (selected) {
                "Auto" -> 0
                else -> selected.toIntOrNull() ?: 0
            }
            publish { cfg -> cfg.copy(iso = iso) }
            val fsPos = isoLabels().indexOf(selected).coerceAtLeast(0)
            if (binding.isoSpinner.selectedItemPosition != fsPos) {
                updatingUi = true
                binding.isoSpinner.setSelection(fsPos)
                updatingUi = false
            }
            if (serviceRunning) {
                CameraStreamService.setIso(iso)
            } else {
                previewController.setIso(iso)
            }
        }

        // Exposure time spinner (main)
        val exposureListener = spinnerListener {
            if (updatingUi) return@spinnerListener
            val selected = binding.exposureSpinner.selectedItem.toString()
            val exposureUs = parseExposureLabel(selected)
            publish { cfg -> cfg.copy(exposureTimeUs = exposureUs) }
            // Keep the fullscreen spinner visually in sync.
            val fsPos = exposureLabels().indexOf(selected).coerceAtLeast(0)
            if (binding.exposureSpinnerFullscreen.selectedItemPosition != fsPos) {
                updatingUi = true
                binding.exposureSpinnerFullscreen.setSelection(fsPos)
                updatingUi = false
            }
            if (serviceRunning) {
                CameraStreamService.setExposureTimeUs(exposureUs)
            } else {
                previewController.setExposureTimeUs(exposureUs)
            }
        }
        binding.exposureSpinner.onItemSelectedListener = exposureListener
        // Exposure time spinner (fullscreen mirror)
        binding.exposureSpinnerFullscreen.onItemSelectedListener = spinnerListener {
            if (updatingUi) return@spinnerListener
            val selected = binding.exposureSpinnerFullscreen.selectedItem.toString()
            val exposureUs = parseExposureLabel(selected)
            publish { cfg -> cfg.copy(exposureTimeUs = exposureUs) }
            val fsPos = exposureLabels().indexOf(selected).coerceAtLeast(0)
            if (binding.exposureSpinner.selectedItemPosition != fsPos) {
                updatingUi = true
                binding.exposureSpinner.setSelection(fsPos)
                updatingUi = false
            }
            if (serviceRunning) {
                CameraStreamService.setExposureTimeUs(exposureUs)
            } else {
                previewController.setExposureTimeUs(exposureUs)
            }
        }

        // Flash toggle (main + fullscreen). Both buttons route through
        // the shared store so toggling on one UI updates the other.
        val flashClick = View.OnClickListener {
            publish { cfg -> cfg.copy(flashTorchEnabled = !cfg.flashTorchEnabled) }
            val nowOn = webcamSettings.current().flashTorchEnabled
            flashOn = nowOn
            if (serviceRunning) {
                CameraStreamService.setFlashTorch(nowOn)
            } else {
                previewController.setFlashTorch(nowOn)
            }
        }
        binding.btnFlash.setOnClickListener(flashClick)
        binding.btnFlashFullscreen.setOnClickListener(flashClick)

        binding.btnFullscreenPreview.setOnClickListener { enterFullscreenPreview() }
        binding.btnExitFullscreen.setOnClickListener { exitFullscreenPreview() }

        // Tap-to-focus — attach to all preview surfaces so focus works
        // regardless of whether we're in normal or fullscreen mode.
        binding.previewView.setOnTouchListener(focusTouchListener)
        binding.camera2PreviewView.setOnTouchListener(focusTouchListener)
        binding.fullscreenPreviewView.setOnTouchListener(focusTouchListener)
        binding.fullscreenCamera2PreviewView.setOnTouchListener(focusTouchListener)

        binding.deviceNameInput.doAfterTextChanged { text ->
            if (updatingUi) return@doAfterTextChanged
            preferences.deviceName = text?.toString().orEmpty()
            refreshObsInfo()
        }

        binding.themeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingUi) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnThemeDark -> AppThemeMode.DARK
                else -> AppThemeMode.LIGHT
            }
            themeManager.setTheme(mode)
            recreate()
        }

        binding.languageToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingUi) return@addOnButtonCheckedListener
            val language = when (checkedId) {
                R.id.btnLangIt -> AppLanguage.ITALIAN
                else -> AppLanguage.ENGLISH
            }
            localeManager.setLanguage(this, language)
            recreate()
        }

        binding.btnToggleService.setOnClickListener {
            if (serviceRunning) {
                // Reset the "service is taking ownership of the camera" gate
                // BEFORE issuing the stop, so the subsequent
                // updateServiceUi(isRunning=false) call kicks off the
                // activity's idle preview. Otherwise the preview surface
                // stays black because the activity never restarts the
                // idle controller.
                serviceStartRequested = false
                CameraStreamService.stop(this)
            } else {
                ensurePermissionsAndStart()
            }
        }

        binding.btnCopyUrl.setOnClickListener {
            val endpoints = connectionInfoFactory.buildEndpoints(
                preferences.connectionMode,
                readPort()
            )
            val url = endpoints.streamUrl ?: return@setOnClickListener
            val clipboard = getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText("stream_url", url))
            Toast.makeText(this, R.string.url_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun spinnerListener(onSelected: () -> Unit) =
        object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                if (!updatingUi) onSelected()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

    /**
     * Push a new [StreamConfig] to the shared store after persisting the
     * fields that need to survive a process restart. Centralised so the
     * Activity has exactly one code path that mutates state — every UI
     * surface (main page, fullscreen overlay) goes through this method.
     */
    private fun publish(transform: (StreamConfig) -> StreamConfig) {
        val previous = webcamSettings.current()
        val next = transform(previous)
        if (next == previous) return
        // Persist to SharedPreferences. The set of fields that survive a
        // process restart is intentionally a subset: connection mode,
        // device name, http port and the live config knobs the user
        // expects to come back to (resolution, fps, …).
        preferences.streamResolution = next.resolution
        preferences.streamFps = next.fps
        preferences.streamOrientation = next.orientation
        preferences.linearZoom = next.linearZoom
        preferences.jpegQuality = next.jpegQuality
        preferences.streamFormat = next.streamFormat
        preferences.manualFocusEnabled = next.manualFocusEnabled
        preferences.manualAdjustmentsEnabled = next.manualAdjustmentsEnabled
        preferences.focusDistance = next.focusDistance
        preferences.iso = next.iso
        preferences.exposureTimeUs = next.exposureTimeUs
        preferences.flashTorchEnabled = next.flashTorchEnabled
        preferences.limitFps = next.limitFps
        next.cameraId?.let { preferences.selectedCameraId = it }
        // Publish the in-memory snapshot last: by the time observers
        // receive the new value, the underlying preferences already
        // agree with it, so a config-restart in the middle of the
        // observer chain cannot resurrect a stale value.
        webcamSettings.update(next)
    }

    /** Copy the persisted snapshot into the local convenience fields
     *  this Activity uses for backward compatibility with the rest of
     *  the code (``flashOn``, ``manualFocusEnabled``,
     *  ``manualAdjustmentsEnabled``). Called once in ``onCreate`` and
     *  whenever the store publishes a change initiated by another
     *  surface (the fullscreen overlay). */
    private fun syncLocalFromStore() {
        val snapshot = webcamSettings.current()
        flashOn = snapshot.flashTorchEnabled
        manualFocusEnabled = snapshot.manualFocusEnabled
        manualAdjustmentsEnabled = snapshot.manualAdjustmentsEnabled
        limitFpsEnabled = snapshot.limitFps
        rotate180Enabled = snapshot.rotate180
    }

    /** Re-apply every control value from [cfg] to the UI widgets
     *  (spinners, seek bars, checkboxes) without re-firing their
     *  listeners (which would call [publish] again and loop).
     *
     *  This is the bridge between the shared [WebcamSettingsStore]
     *  and the Activity's view layer: whenever a setting changes
     *  in one surface (main page or fullscreen overlay) the
     *  observer in [onStart] calls this method so the other
     *  surface stays in sync. */
    private fun applyConfigToControls(cfg: StreamConfig) {
        updatingUi = true
        // Spinners
        val resPos = StreamResolution.entries.indexOf(cfg.resolution).coerceAtLeast(0)
        if (binding.resolutionSpinner.selectedItemPosition != resPos) {
            binding.resolutionSpinner.setSelection(resPos, false)
        }
        val fpsPos = StreamFps.entries.indexOf(cfg.fps).coerceAtLeast(0)
        if (binding.fpsSpinner.selectedItemPosition != fpsPos) {
            binding.fpsSpinner.setSelection(fpsPos, false)
        }
        val orientPos = StreamOrientation.entries.indexOf(cfg.orientation).coerceAtLeast(0)
        if (binding.orientationSpinner.selectedItemPosition != orientPos) {
            binding.orientationSpinner.setSelection(orientPos, false)
        }
        val fmtPos = StreamFormat.entries.indexOf(cfg.streamFormat).coerceAtLeast(0)
        if (binding.streamFormatSpinner.selectedItemPosition != fmtPos) {
            binding.streamFormatSpinner.setSelection(fmtPos, false)
        }
        val qualPos = JpegQuality.entries.indexOf(cfg.jpegQuality).coerceAtLeast(0)
        if (binding.jpegQualitySpinner.selectedItemPosition != qualPos) {
            binding.jpegQualitySpinner.setSelection(qualPos, false)
        }
        // Camera spinner
        val camPos = availableCameras.indexOfFirst { it.id == cfg.cameraId }
        if (camPos >= 0 && binding.cameraSpinner.selectedItemPosition != camPos) {
            binding.cameraSpinner.setSelection(camPos, false)
        }
        // Checkboxes (flash is a Button, not a CheckBox — update its UI separately)
        if (binding.manualFocusCheckbox.isChecked != cfg.manualFocusEnabled) {
            binding.manualFocusCheckbox.isChecked = cfg.manualFocusEnabled
        }
        if (binding.manualAdjustmentsCheckbox.isChecked != cfg.manualAdjustmentsEnabled) {
            binding.manualAdjustmentsCheckbox.isChecked = cfg.manualAdjustmentsEnabled
        }
        // Limit FPS checkbox (mirror)
        if (binding.limitFpsCheckbox.isChecked != cfg.limitFps) {
            binding.limitFpsCheckbox.isChecked = cfg.limitFps
        }
        // Rotate 180° checkbox (main + fullscreen mirror)
        if (binding.rotate180Checkbox.isChecked != cfg.rotate180) {
            binding.rotate180Checkbox.isChecked = cfg.rotate180
        }
        if (binding.rotate180CheckboxFullscreen.isChecked != cfg.rotate180) {
            binding.rotate180CheckboxFullscreen.isChecked = cfg.rotate180
        }
        // Seek bars
        val zoomProgress = (cfg.linearZoom * 100f + 0.5f).toInt().coerceIn(0, 100)
        if (binding.zoomSeekBar.progress != zoomProgress) {
            binding.zoomSeekBar.progress = zoomProgress
            binding.fullscreenZoomSeekBar.progress = zoomProgress
        }
        val focusProgress = (cfg.focusDistance * 1000f + 0.5f).toInt().coerceIn(0, 1000)
        if (binding.focusSeekBar.progress != focusProgress) {
            binding.focusSeekBar.progress = focusProgress
            binding.focusSeekBarFullscreen.progress = focusProgress
        }
        // Visibility of manual-control panels
        binding.manualFocusControls.visibility =
            if (cfg.manualFocusEnabled) View.VISIBLE else View.GONE
        binding.manualFocusControlsFullscreen.visibility =
            if (cfg.manualFocusEnabled) View.VISIBLE else View.GONE
        binding.manualAdjustmentsControls.visibility =
            if (cfg.manualAdjustmentsEnabled) View.VISIBLE else View.GONE
        binding.manualAdjustmentsControlsFullscreen.visibility =
            if (cfg.manualAdjustmentsEnabled) View.VISIBLE else View.GONE
        updatingUi = false
    }

    private fun selectedCamera(): AvailableCamera? {
        if (availableCameras.isEmpty()) return null
        val index = binding.cameraSpinner.selectedItemPosition
            .coerceIn(0, availableCameras.lastIndex)
        return availableCameras[index]
    }

    private fun ensurePermissionsAndStart() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startServiceInternal()
        } else {
            startupPermissionRequest = false
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    /**
     * At app startup, check if camera and notification permissions are granted.
     * If not, request them. Do not auto-start the service.
     */
    private fun checkPermissionsAtStartup() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            startupPermissionRequest = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startServiceInternal() {
        val camera = selectedCamera() ?: CameraInventory.resolve(this, preferences)
        if (camera == null) {
            Toast.makeText(this, R.string.camera_none_available, Toast.LENGTH_LONG).show()
            return
        }
        val port = readPort()
        preferences.httpPort = port
        val deviceName = binding.deviceNameInput.text?.toString().orEmpty()
        preferences.deviceName = deviceName
        // Publish the resolved spinner positions / zoom into the shared
        // store so the service picks them up from the single source of
        // truth instead of having to re-read every preferences field.
        publish {
            cfg ->
            cfg.copy(
                cameraId = camera.id,
                resolution = StreamResolution.entries[
                    binding.resolutionSpinner.selectedItemPosition.coerceIn(0, StreamResolution.entries.lastIndex)
                ],
                fps = StreamFps.entries[
                    binding.fpsSpinner.selectedItemPosition.coerceIn(0, StreamFps.entries.lastIndex)
                ],
                orientation = StreamOrientation.entries[
                    binding.orientationSpinner.selectedItemPosition.coerceIn(
                        0,
                        StreamOrientation.entries.lastIndex
                    )
                ],
                linearZoom = (binding.zoomSeekBar.progress / 100f).coerceIn(0f, 1f),
                deviceName = deviceName,
                limitFps = limitFpsEnabled,
            )
        }
        previewController.stop()
        serviceStartRequested = true
        // Store the in-memory config (including transient fields like
        // rotate180) before starting the service so the service picks
        // them up even though they are not persisted to SharedPreferences.
        CameraStreamService.setPendingStreamConfig(webcamSettings.current())
        CameraStreamService.setPreviewView(activePreviewView(), activeTextureView())
        CameraStreamService.start(
            this,
            preferences.connectionMode,
            camera.id,
            port
        )
    }

    private fun zoomSeekBarListener(source: SeekBar) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUi) return
                val zoom = (progress / 100f).coerceIn(0f, 1f)
                publish { cfg -> cfg.copy(linearZoom = zoom) }
                val other = if (source === binding.zoomSeekBar) {
                    binding.fullscreenZoomSeekBar
                } else {
                    binding.zoomSeekBar
                }
                if (other.progress != progress) {
                    other.progress = progress
                }
                if (serviceRunning) {
                    CameraStreamService.setLinearZoom(zoom)
                } else {
                    previewController.setLinearZoom(zoom)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun focusSeekBarListener(source: SeekBar) =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser || updatingUi) return
                val distance = (progress / 1000f).coerceIn(0f, 1f)
                publish { cfg -> cfg.copy(focusDistance = distance) }
                // Mirror the value to the other seekbar (main ↔ fullscreen).
                val other = if (source === binding.focusSeekBar) {
                    binding.focusSeekBarFullscreen
                } else {
                    binding.focusSeekBar
                }
                if (other.progress != progress) {
                    other.progress = progress
                }
                if (serviceRunning) {
                    CameraStreamService.setFocusDistance(distance)
                } else {
                    previewController.setFocusDistance(distance)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }

    private fun adjustZoom(delta: Int) {
        val current = binding.zoomSeekBar.progress
        val newProgress = (current + delta).coerceIn(0, 100)
        binding.zoomSeekBar.progress = newProgress
        binding.fullscreenZoomSeekBar.progress = newProgress
        val zoom = (newProgress / 100f).coerceIn(0f, 1f)
        publish { cfg -> cfg.copy(linearZoom = zoom) }
        if (serviceRunning) {
            CameraStreamService.setLinearZoom(zoom)
        } else {
            previewController.setLinearZoom(zoom)
        }
    }

    private fun adjustFocus(delta: Int) {
        val current = binding.focusSeekBar.progress
        val newProgress = (current + delta).coerceIn(0, 1000)
        binding.focusSeekBar.progress = newProgress
        binding.focusSeekBarFullscreen.progress = newProgress
        val distance = (newProgress / 1000f).coerceIn(0f, 1f)
        publish { cfg -> cfg.copy(focusDistance = distance) }
        if (serviceRunning) {
            CameraStreamService.setFocusDistance(distance)
        } else {
            previewController.setFocusDistance(distance)
        }
    }

    /** ISO labels exposed by the main/fullscreen spinner. Combines the
     *  original coarse set (Auto, 600, 800, 1600, 3200, 6400, 12800) with
     *  the fine-grained steps the user asked for (100/160/200/…/10000),
     *  extended through the full ISO 12232 progression up to 1000000. */
    private fun isoLabels(): List<String> = listOf(
        "Auto",
        "100", "160", "200", "250", "320", "400", "500",
        "600", "640", "800", "1000", "1250", "1600", "2000", "2500",
        "3200", "4000", "5000", "6400", "8000", "10000", "12800",
        "16000", "20000", "25600", "32000", "40000", "51200", "64000",
        "80000", "102400", "128000", "160000", "200000", "260000",
        "300000", "400000", "500000", "650000", "750000", "1000000"
    )

    /** Exposure labels exposed by the main/fullscreen spinner. */
    private fun exposureLabels(): List<String> = listOf(
        "Auto", "1/20000", "1/15000", "1/10000", "1/7500", "1/6500",
        "1/5000", "1/3000", "1/2000", "1/1500", "1/1250", 
        "1/1000", "1/800", "1/500", "1/250", "1/200", "1/160",
        "1/120", "1/100", "1/80", "1/60", "1/50", "1/48", 
        "1/30", "1/24", "1/15", "1/8", "1/4", "1/2", "1s", "2s", 
        "3s", "5s", "10s", "15s", "20s","30s", "45s", "60s"
    )

    /** Convert "1/250" / "1s" / "Auto" → microseconds. */
    private fun parseExposureLabel(label: String): Long = when (label) {
        "Auto" -> 0L
        else -> {
            val parts = label.split("/")
            if (parts.size == 2) {
                val denominator = parts[1].replace("s", "").toIntOrNull() ?: 0
                if (denominator > 0) 1_000_000L / denominator else 0L
            } else {
                val secs = label.replace("s", "").toFloatOrNull() ?: 0f
                (secs * 1_000_000L).toLong()
            }
        }
    }

    /**
     * Dispatch helper: always target the running service when one is active,
     * otherwise the preview controller. Used by every manual-control listener
     * so a setting changed while streaming reaches the camera immediately
     * (and is reflected in the StreamConfig persisted to disk so a service
     * restart reuses the same value).
     */
    private inline fun applyToCamera(block: () -> Unit) {
        if (serviceRunning) {
            // Forward via the service static API. The service owns the
            // CameraStreamController and updates the live session.
            block()
        } else {
            previewController.let { block() }
        }
    }

    private fun updateFlashUi(on: Boolean) {
        val iconRes = if (on) R.drawable.ic_flash_on else R.drawable.ic_flash_off
        val textRes = if (on) R.string.flash_torch_on else R.string.flash_torch_off
        binding.btnFlash.setIconResource(iconRes)
        binding.btnFlash.text = getString(textRes)
        binding.btnFlashFullscreen.setIconResource(iconRes)
        binding.btnFlashFullscreen.text = getString(textRes)
    }

    private fun activePreviewView(): PreviewView =
        if (fullscreenPreview) binding.fullscreenPreviewView else binding.previewView

    private fun activeTextureView(): TextureView =
        if (fullscreenPreview) binding.fullscreenCamera2PreviewView else binding.camera2PreviewView

    private fun enterFullscreenPreview() {
        if (fullscreenPreview) return
        fullscreenPreview = true
        binding.previewCard.visibility = View.GONE
        binding.fullscreenPreviewOverlay.visibility = View.VISIBLE
        binding.fullscreenZoomSeekBar.progress = binding.zoomSeekBar.progress
        // Sync fullscreen manual-focus / manual-adjustments / ISO / exposure
        // state with the main UI.
        updatingUi = true
        binding.manualFocusCheckboxFullscreen.isChecked = manualFocusEnabled
        binding.manualFocusControlsFullscreen.visibility =
            if (manualFocusEnabled) View.VISIBLE else View.GONE
        binding.manualAdjustmentsCheckboxFullscreen.isChecked = manualAdjustmentsEnabled
        binding.manualAdjustmentsControlsFullscreen.visibility =
            if (manualAdjustmentsEnabled) View.VISIBLE else View.GONE
        binding.focusSeekBarFullscreen.progress = binding.focusSeekBar.progress
        binding.isoSpinnerFullscreen.setSelection(binding.isoSpinner.selectedItemPosition)
        binding.exposureSpinnerFullscreen.setSelection(binding.exposureSpinner.selectedItemPosition)
        // Sync the fullscreen camera spinner so the user can see (and
        // change) the currently-active lens from inside the overlay.
        if (binding.cameraSpinnerFullscreen.selectedItemPosition !=
            binding.cameraSpinner.selectedItemPosition
        ) {
            binding.cameraSpinnerFullscreen.setSelection(
                binding.cameraSpinner.selectedItemPosition,
                false,
            )
        }
        updatingUi = false
        fullscreenBackCallback.isEnabled = true
        applyPreviewSurfaceTransform(binding.fullscreenPreviewView)
        rebindActivePreviewSurface()
        updateFlashUi(flashOn)
        // Re-apply flash on the camera session in case the rebind cleared it.
        reapplyFlashOnCamera()
    }

    private fun exitFullscreenPreview() {
        if (!fullscreenPreview) return
        fullscreenPreview = false
        binding.fullscreenPreviewOverlay.visibility = View.GONE
        binding.previewCard.visibility = View.VISIBLE
        binding.focusReticle.visibility = View.GONE
        fullscreenBackCallback.isEnabled = false
        applyPreviewSurfaceTransform(binding.previewView)
        rebindActivePreviewSurface()
        updateFlashUi(flashOn)
        // Re-apply flash on the camera session in case the rebind cleared it.
        reapplyFlashOnCamera()
    }

    /**
     * Push the current [flashOn] value into the running camera session.
     * Entering/exiting fullscreen triggers a preview-surface rebind which
     * can drop the previous FLASH_MODE capture-request override. Re-applying
     * keeps the LED lit across the transition.
     */
    private fun reapplyFlashOnCamera() {
        if (!flashOn) return
        if (serviceRunning) {
            CameraStreamService.setFlashTorch(true)
        } else {
            previewController.setFlashTorch(true)
        }
    }

    private fun handleFocusTap(view: View, x: Float, y: Float) {
        showFocusReticle(view, x, y)
        if (serviceRunning) {
            CameraStreamService.focusAt(view, x, y)
        } else {
            previewController.focusAt(view, x, y)
        }
    }

    /**
     * Flashes a white ring centered on the tapped point, then fades it out.
     * [view] is the tapped preview surface; the reticle lives in the same overlay,
     * so tap coords are translated into the overlay's coordinate space.
     */
    private fun showFocusReticle(view: View, x: Float, y: Float) {
        // Use the correct reticle based on which preview is active
        val reticle = if (fullscreenPreview) binding.focusReticle else binding.focusReticleMain
        val reticleSize = reticle.width.takeIf { it > 0 }
            ?: (90 * resources.displayMetrics.density).toInt()

        // Translate tap coords from the preview view into the overlay FrameLayout.
        // For fullscreen, the overlay is the fullscreen overlay; for main, it's the previewCard.
        val overlayX: Float
        val overlayY: Float
        if (fullscreenPreview) {
            overlayX = x + view.left
            overlayY = y + view.top
        } else {
            // For main preview, the reticle is inside previewCard FrameLayout
            // The view is the previewView/camera2PreviewView inside the card
            val card = binding.previewCard
            overlayX = x + view.left - card.paddingLeft
            overlayY = y + view.top - card.paddingTop
        }

        reticle.clearAnimation()
        reticle.alpha = 1f
        val lp = reticle.layoutParams
        lp.width = reticleSize
        lp.height = reticleSize
        reticle.layoutParams = lp
        reticle.translationX = overlayX - reticleSize / 2f
        reticle.translationY = overlayY - reticleSize / 2f
        reticle.visibility = View.VISIBLE
        reticle.animate()
            .alpha(0f)
            .setDuration(700)
            .withEndAction { reticle.visibility = View.GONE }
            .start()
    }

    private fun rebindActivePreviewSurface() {
        if (serviceRunning) {
            CameraStreamService.adoptCameraHost(
                this,
                activePreviewView(),
                activeTextureView()
            )
        } else {
            maybeStartIdlePreview()
        }
    }

    private fun applyPreviewSurfaceTransform(view: PreviewView) {
        view.scaleType = PreviewView.ScaleType.FIT_CENTER
        // We do NOT rotate the PreviewView itself: CameraX internally
        // overrides its rotation whenever it binds the surface, which
        // would silently undo the toggle. Instead we rotate the parent
        // wrapper container (the FrameLayout that holds the PreviewView
        // and the Camera2 TextureView). The wrapper is identified by
        // matching the view's id: rotate180Wrapper for the main preview
        // and rotate180WrapperFullscreen for the fullscreen one.
        val wrapper = findWrapperFor(view)
        val target = wrapper ?: view
        val rotation = if (rotate180Enabled) 180f else 0f
        target.rotation = rotation
        // Setting view.rotation rotates around the view's pivot (its
        // centre by default). To keep the wrapper centred inside its
        // parent after a 180° rotation we don't need to move it — a
        // rotation around the centre maps the rect onto itself. This
        // is exactly what we want for the 180° toggle.
    }

    /**
     * Locate the wrapper FrameLayout that hosts the given preview view.
     * The wrappers are `rotate180Wrapper` (main preview card) and
     * `rotate180WrapperFullscreen` (fullscreen overlay). We find them
     * by walking up from the view's parent until we see a FrameLayout
     * with one of those ids, because the actual nesting may change as
     * we add or remove intermediate containers in the XML.
     */
    private fun findWrapperFor(view: View): View? {
        var node: ViewParent? = view.parent
        while (node is ViewGroup) {
            val resId = node.id
            if (resId == R.id.rotate180Wrapper ||
                resId == R.id.rotate180WrapperFullscreen) {
                return node
            }
            node = node.parent
        }
        return null
    }

    private fun applyPreviewOrientation() {
        // Hold phone to match the mode (portrait upright / landscape on its side).
        // CameraX + display rotation show the live FOV; FIT keeps the full frame
        // (no top/bottom crop). Inverted modes keep the same upright preview —
        // only the frames sent to the computer are digitally flipped 180°.
        val cfg = preferences.streamConfig()
        applyPreviewSurfaceTransform(binding.previewView)
        applyPreviewSurfaceTransform(binding.fullscreenPreviewView)

        val card = binding.previewCard
        val applySize = {
            val width = card.width.takeIf { it > 0 }
                ?: (resources.displayMetrics.widthPixels -
                    (32 * resources.displayMetrics.density).toInt())
            // Always size the card for portrait so landscape keeps the same box
            // (letterboxed via FIT_CENTER instead of shrinking the view).
            val rw = cfg.resolution.widthFor(StreamOrientation.PORTRAIT).coerceAtLeast(1)
            val rh = cfg.resolution.heightFor(StreamOrientation.PORTRAIT).coerceAtLeast(1)
            val maxH = (resources.displayMetrics.heightPixels * 0.45f).toInt()
            val targetH = (width.toLong() * rh / rw).toInt().coerceIn(160, maxH)
            val lp = card.layoutParams
            if (lp.height != targetH) {
                lp.width = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = targetH
                card.layoutParams = lp
            }
        }
        if (card.width > 0) {
            applySize()
        } else {
            card.post { applySize() }
        }

        if (!serviceRunning) {
            previewController.updateStreamConfig(cfg)
        }
    }

    private fun restartPreviewIfIdle() {
        if (serviceRunning) return
        maybeStartIdlePreview()
    }

    private fun readPort(): Int {
        val parsed = binding.portInput.text?.toString()?.toIntOrNull()
        return parsed?.coerceIn(1024, 65534) ?: PreferencesRepository.DEFAULT_PORT
    }

    private fun updateConnectionDescription() {
        binding.connectionModeDesc.setText(
            when (preferences.connectionMode) {
                ConnectionMode.USB -> R.string.mode_usb_desc
                ConnectionMode.USB_DEVICE -> R.string.mode_usb_device_desc
                ConnectionMode.IP -> R.string.mode_ip_desc
            }
        )
    }

    private fun refreshObsInfo() {
        val endpoints = connectionInfoFactory.buildEndpoints(
            preferences.connectionMode,
            readPort()
        )
        val deviceName = preferences.deviceName
        binding.deviceAddressText.text = getString(R.string.device_address, endpoints.host)

        val url = endpoints.streamUrl
        if (url != null) {
            binding.obsUrlStream.visibility = View.VISIBLE
            binding.obsUrlStream.text = getString(R.string.obs_url_stream, url)
        } else {
            binding.obsUrlStream.visibility = View.GONE
        }

        binding.obsHint.text = when (preferences.connectionMode) {
            ConnectionMode.USB -> getString(R.string.obs_hint_usb, deviceName)
            ConnectionMode.USB_DEVICE -> getString(R.string.obs_hint_usb_device, deviceName)
            ConnectionMode.IP -> getString(R.string.obs_hint_ip, deviceName)
        }
    }

    private fun updateServiceUi(state: ServiceUiState) {
        val wasRunning = serviceRunning
        serviceRunning = state.isRunning
        val palette = themeManager.loadPaletteColors(this)

        binding.statusTitle.setText(
            if (state.isRunning) R.string.service_running else R.string.service_stopped
        )
        binding.statusDetail.setText(
            if (state.isRunning) R.string.status_active_detail else R.string.status_idle_detail
        )
        binding.btnToggleService.setText(
            if (state.isRunning) R.string.stop_service else R.string.start_service
        )

        val dot = binding.statusDot.background as? GradientDrawable
            ?: GradientDrawable().also {
                it.shape = GradientDrawable.OVAL
                binding.statusDot.background = it
            }
        dot.setColor(if (state.isRunning) palette.statusActive else palette.statusIdle)

        val controlsEnabled = !state.isRunning
        binding.portInput.isEnabled = controlsEnabled
        binding.deviceNameInput.isEnabled = controlsEnabled
        binding.connectionToggle.isEnabled = controlsEnabled
        // Camera picker stays live while streaming so the user can switch
        // lenses mid-stream; the live selection drives a runtime rebind in
        // the service. config-only spinners (resolution, fps, orientation,
        // stream format) still lock while streaming to avoid the analysis
        // restarting mid-frame.
        binding.cameraSpinner.isEnabled = availableCameras.isNotEmpty()
        binding.cameraSpinnerFullscreen.isEnabled = availableCameras.isNotEmpty()
        binding.resolutionSpinner.isEnabled = controlsEnabled
        binding.fpsSpinner.isEnabled = controlsEnabled
        binding.orientationSpinner.isEnabled = controlsEnabled
        binding.streamFormatSpinner.isEnabled = controlsEnabled
        // "Limit FPS" must stay locked while streaming: toggling the gate
        // mid-stream would silently flip between dropping extra sensor frames
        // and forwarding every frame, and that decision is something the
        // user should make before pressing "Start webcam service". Lock +
        // fade so it is visually obvious the control is intentionally
        // disabled, mirroring the JPEG-quality treatment in
        // applyStreamFormatUiState.
        binding.limitFpsCheckbox.isEnabled = controlsEnabled
        binding.limitFpsCheckbox.alpha = if (controlsEnabled) 1f else 0.4f
        // JPEG quality is meaningful only in JPEG mode (locked + faded in YUV).
        applyStreamFormatUiState()
        // Zoom stays available while streaming so the user can reframe live.
        binding.zoomSeekBar.isEnabled = true
        binding.fullscreenZoomSeekBar.isEnabled = true
        // Manual focus / ISO / exposure stay available while streaming so the
        // user can tune the image on the fly. CameraX applies the new
        // capture-request options via Camera2CameraControl (no rebind, the
        // analysis keeps streaming). The fullscreen overlay mirrors each
        // control so the user can keep tuning after entering fullscreen.
        binding.manualFocusCheckbox.isEnabled = true
        binding.manualFocusCheckboxFullscreen.isEnabled = true
        binding.focusSeekBar.isEnabled = true
        binding.focusSeekBarFullscreen.isEnabled = true
        binding.btnFocusMinus.isEnabled = true
        binding.btnFocusMinusFullscreen.isEnabled = true
        binding.btnFocusPlus.isEnabled = true
        binding.btnFocusPlusFullscreen.isEnabled = true
        binding.isoSpinner.isEnabled = true
        binding.isoSpinnerFullscreen.isEnabled = true
        binding.exposureSpinner.isEnabled = true
        binding.exposureSpinnerFullscreen.isEnabled = true
        // Flash button enabled only for rear cameras. While streaming we ask
        // the service which lens is active; otherwise we use the idle preview
        // controller. This avoids the spinner showing "Flash Off" greyed out
        // when the user picks a rear lens but the previewController still
        // points at a front-facing device (e.g. immediately after a camera
        // switch).
        val isRearCamera = if (state.isRunning) {
            CameraStreamService.isActiveCameraRear()
        } else {
            previewController.isRearCamera()
        }
        binding.btnFlash.isEnabled = isRearCamera
        binding.btnFlashFullscreen.isEnabled = isRearCamera

        if (state.isRunning) {
            val label = state.camerasInUseLabel.ifBlank {
                selectedCamera()?.let { CameraLabels.displayName(this, it) }.orEmpty()
            }
            binding.camerasInUseText.visibility = View.VISIBLE
            binding.camerasInUseText.text = getString(R.string.cameras_in_use, label)
            if (!wasRunning) {
                // The service is taking ownership of the camera. Stop the
                // activity's idle previewController so we don't end up with
                // two controllers fighting over the process-wide
                // ProcessCameraProvider (one unbinds the other's bindings,
                // which causes the analysis to never deliver frames).
                runCatching { previewController.stop() }
                CameraStreamService.adoptCameraHost(
                    this,
                    activePreviewView(),
                    activeTextureView()
                )
            }
        } else {
            binding.camerasInUseText.visibility = View.GONE
            // Service went away. NOTE: we deliberately do NOT reset
            // serviceStartRequested here — transient state.isRunning=false
            // (e.g. the service briefly restarting itself) would otherwise
            // cause the activity's previewController to start and fight the
            // service's controller for the camera. The gate is reset only
            // when the user explicitly stops the service.
            maybeStartIdlePreview()
        }

        state.errorMessage?.let {
            Toast.makeText(this, getString(R.string.error_start_failed, it), Toast.LENGTH_LONG).show()
        }

        refreshObsInfo()
    }

    private fun applyThemeColorsFromXml() {
        // Use Activity context so light theme does not pick system night colors.
        val palette = themeManager.loadPaletteColors(this)
        binding.rootLayout.setBackgroundColor(palette.background)
        binding.toolbar.setBackgroundColor(palette.background)
        binding.toolbar.setTitleTextColor(palette.textPrimary)
        binding.subtitleText.setTextColor(palette.textSecondary)
        binding.statusCard.setCardBackgroundColor(palette.surface)
        binding.statusCard.strokeColor = palette.outline
        binding.statusTitle.setTextColor(palette.textPrimary)
        binding.statusDetail.setTextColor(palette.textSecondary)
        binding.camerasInUseText.setTextColor(palette.primary)
        binding.obsCard.setCardBackgroundColor(palette.surface)
        binding.obsCard.strokeColor = palette.outline
        binding.obsTitle.setTextColor(palette.textPrimary)
        binding.deviceAddressText.setTextColor(palette.textSecondary)
        binding.obsUrlStream.setTextColor(palette.primary)
        binding.obsHint.setTextColor(palette.textSecondary)
        binding.btnToggleService.setBackgroundColor(palette.primary)
    }

    private fun maybeStartIdlePreview() {
        if (serviceRunning) return
        if (idlePreviewStarting) return
        // If we already requested the service to start, don't also start the
        // activity's idle preview — the service's controller owns the camera
        // and our previewController would just fight it for the camera and
        // starve the analysis.
        if (serviceStartRequested) return
        // Don't start the idle preview if we're heading toward launching the
        // service from this very Activity (applyLaunchExtras is about to
        // dispatch start_service). This catches the onCreate path where
        // updateServiceUi() ran with the initial state before
        // applyLaunchExtras had a chance to set serviceStartRequested.
        if (launchedWithStartServiceIntent) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val camera = selectedCamera() ?: CameraInventory.resolve(this, preferences) ?: return
        idlePreviewStarting = true
        lifecycleScope.launch {
            try {
                runCatching {
                    previewController.start(
                        lifecycleOwner = this@MainActivity,
                        cameraId = camera.id,
                        previewView = activePreviewView(),
                        textureView = activeTextureView(),
                        // Use the in-memory snapshot (webcamSettings) instead of
                        // the persisted preferences so transient fields like
                        // rotate180 are not silently reset to their defaults.
                        streamConfig = webcamSettings.current()
                    )
                }.onFailure {
                    Toast.makeText(
                        this@MainActivity,
                        getString(R.string.error_start_failed, it.message ?: it.javaClass.simpleName),
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                idlePreviewStarting = false
            }
        }
    }

    companion object {
        const val EXTRA_CAMERA_ID = "camera_id"
        const val EXTRA_CONNECTION_MODE = "connection_mode"
        const val EXTRA_START_SERVICE = "start_service"
        const val EXTRA_STOP_SERVICE = "stop_service"
    }
}
