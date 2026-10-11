package com.example.panoramaapp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import org.opencv.stitching.Stitcher
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.tan

class MainActivity : AppCompatActivity(), SensorEventListener {

    companion object {
        private const val REQ_CAMERA = 100
        private const val MIN_INTERVAL_MS = 400L

        private const val YAW_STEP_DEFAULT = 20
        private const val LIVE_MIN_FRAMES = 3
        private const val LIVE_EVERY_N = 3

        private const val GUIDE_INTERVAL_MS = 33L
        private const val HUD_INTERVAL_MS = 120L

        private val WIDTH_CHOICES = intArrayOf(640, 800, 960, 1120, 1280, 1440, 1600)
    }

    // ---- Views ----
    private lateinit var previewView: PreviewView
    private lateinit var ghostOverlay: GhostOverlayView
    private lateinit var flashOverlay: View
    private lateinit var panGuide: PanGuideView
    private lateinit var draftContainer: View
    private lateinit var ivDraft: ImageView
    private lateinit var tvDraftLabel: TextView
    private lateinit var ivResult: ZoomableImageView
    private lateinit var resultControls: View
    private lateinit var tvStatus: TextView
    private lateinit var tvLevel: TextView
    private lateinit var tvArrow: TextView
    private lateinit var btnStart: Button
    private lateinit var btnSnap: Button
    private lateinit var btnUndo: Button
    private lateinit var btnDone: Button
    private lateinit var btnCancel: Button
    private lateinit var btnMode: Button
    private lateinit var btnAxis: Button
    private lateinit var btnSettings: Button
    private lateinit var btnGallery: Button
    private lateinit var btnShare: Button
    private lateinit var btnAddMore: Button
    private lateinit var btnDelete: Button
    private lateinit var btnClose: Button
    private lateinit var progressBar: ProgressBar

    // ---- Persisted settings ----
    @Volatile private var yawStepDeg = YAW_STEP_DEFAULT
    @Volatile private var stitchWidthPx = 960
    @Volatile private var livePreviewEnabled = true
    @Volatile private var scansMode = false
    @Volatile private var autoMode = true
    @Volatile private var verticalMode = false

    // ---- Capture state ----
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val engine: StitchingEngine = OpenCvStitchingEngine()
    private val liveStitchBusy = AtomicBoolean(false)

    @Volatile private var capturing = false
    @Volatile private var lastCaptureAt = 0L
    @Volatile private var gyroCapturePending = false
    @Volatile private var manualCapturePending = false
    @Volatile private var exposureLocked = false
    @Volatile private var autoFinishArmed = true
    @Volatile private var totalPanDeg = 0.0

    // ---- Gyro / guide / ghost ----
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private lateinit var sensorThread: HandlerThread
    private lateinit var sensorHandler: Handler
    private val rotMat = FloatArray(9)
    private val orient = FloatArray(3)

    @Volatile private var currentPan = 0f
    @Volatile private var lastTriggerPan = 0f
    @Volatile private var lastPanAxis = 0f
    @Volatile private var panSign = -1f
    @Volatile private var guideTargetPan = 0f
    @Volatile private var panDir = "→"
    @Volatile private var lastGuideUpdate = 0L
    @Volatile private var lastHudUpdate = 0L

    /** tan(half the lens' horizontal FOV at 1x zoom). Real value from camera characteristics. */
    @Volatile private var baseHalfTan = 0.64f
    @Volatile private var zoomRatio = 1f          // locked during capture

    // ---- Camera interaction / thermal ----
    private var camera: Camera? = null
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var tapDetector: GestureDetector
    private var powerManager: PowerManager? = null
    @Volatile private var draftsThrottled = false

    private val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
        draftsThrottled = status >= PowerManager.THERMAL_STATUS_MODERATE
        if (draftsThrottled) {
            runOnUiThread {
                if (capturing && draftContainer.visibility == View.VISIBLE) {
                    tvDraftLabel.text = getString(R.string.draft_paused_thermal)
                }
            }
        }
    }

    // ---- Output ----
    private var lastSavedUri: Uri? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var tickerJob: Job? = null
    private var lastState: StitchState = StitchState.Idle

    private val maxFrames get() = DeviceProfile.maxFrames
    private val prefs by lazy { getSharedPreferences("pano_settings", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (!OpenCVLoader.initLocal()) {
            Toast.makeText(this, getString(R.string.err_opencv), Toast.LENGTH_LONG).show()
        }

        yawStepDeg = prefs.getInt("yaw_step", YAW_STEP_DEFAULT)
        stitchWidthPx = prefs.getInt("stitch_width", 960).coerceAtMost(DeviceProfile.maxWidth)
        livePreviewEnabled = prefs.getBoolean("live_preview", true) && DeviceProfile.draftsEnabled
        scansMode = prefs.getBoolean("scans_mode", false)
        autoMode = prefs.getBoolean("auto_mode", true)
        verticalMode = prefs.getBoolean("vertical_mode", false)

        previewView    = findViewById(R.id.previewView)
        ghostOverlay   = findViewById(R.id.ghostOverlay)
        flashOverlay   = findViewById(R.id.flashOverlay)
        panGuide       = findViewById(R.id.panGuide)
        draftContainer = findViewById(R.id.draftContainer)
        ivDraft        = findViewById(R.id.ivDraft)
        tvDraftLabel   = findViewById(R.id.tvDraftLabel)
        ivResult       = findViewById(R.id.ivResult)
        resultControls = findViewById(R.id.resultControls)
        tvStatus       = findViewById(R.id.tvStatus)
        tvLevel        = findViewById(R.id.tvLevel)
        tvArrow        = findViewById(R.id.tvArrow)
        btnStart       = findViewById(R.id.btnStart)
        btnSnap        = findViewById(R.id.btnSnap)
        btnUndo        = findViewById(R.id.btnUndo)
        btnDone        = findViewById(R.id.btnDone)
        btnCancel      = findViewById(R.id.btnCancel)
        btnMode        = findViewById(R.id.btnMode)
        btnAxis        = findViewById(R.id.btnAxis)
        btnSettings    = findViewById(R.id.btnSettings)
        btnGallery     = findViewById(R.id.btnGallery)
        btnShare       = findViewById(R.id.btnShare)
        btnAddMore     = findViewById(R.id.btnAddMore)
        btnDelete      = findViewById(R.id.btnDelete)
        btnClose       = findViewById(R.id.btnClose)
        progressBar    = findViewById(R.id.progressBar)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        sensorThread = HandlerThread("SensorThread").apply { start() }
        sensorHandler = Handler(sensorThread.looper)

        powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= 29) {
            powerManager?.addThermalStatusListener(
                ContextCompat.getMainExecutor(this), thermalListener)
        }

        setupPreviewGestures()

        if (rotationSensor == null) {
            autoMode = false
            btnMode.isEnabled = false
            btnMode.text = getString(R.string.auto_na)
            Toast.makeText(this, getString(R.string.err_no_gyro), Toast.LENGTH_LONG).show()
        }
        btnMode.text = if (autoMode) getString(R.string.auto_on) else getString(R.string.auto_off)
        btnAxis.text = if (verticalMode) getString(R.string.axis_v) else getString(R.string.axis_h)

        btnStart.setOnClickListener { startCapture() }
        btnSnap.setOnClickListener { manualCapturePending = true }
        btnUndo.setOnClickListener { undoLastFrame() }
        btnDone.setOnClickListener { finishPanorama() }
        btnCancel.setOnClickListener { returnToIdle(getString(R.string.msg_cancelled)) }
        btnMode.setOnClickListener { toggleMode() }
        btnAxis.setOnClickListener { toggleAxis() }
        btnSettings.setOnClickListener { showSettingsDialog() }
        btnGallery.setOnClickListener { showGallery() }
        btnShare.setOnClickListener { shareResult() }
        btnAddMore.setOnClickListener { resumeCapture() }
        btnDelete.setOnClickListener { deleteResult() }
        btnClose.setOnClickListener { closeResult() }

        handleBackButton()

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        btnStart.isEnabled = granted
        if (granted) {
            startCamera(lockExposure = false)
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), REQ_CAMERA)
        }

        applyWindowInsets()
        observeStitchState()
        restoreInterruptedSession()
        showFirstRunTips()
    }

    // ============================ PREVIEW GESTURES ============================

    /** Pinch to zoom, tap to focus. Both disabled while capturing: a mixed-FOV
     *  frame set cannot stitch, and the ghost/guide math assumes fixed FOV. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewGestures() {
        scaleDetector = ScaleGestureDetector(this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(d: ScaleGestureDetector): Boolean {
                    if (capturing) return true
                    val cam = camera ?: return true
                    val zs = cam.cameraInfo.zoomState.value ?: return true
                    val target = (zs.zoomRatio * d.scaleFactor)
                        .coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                    cam.cameraControl.setZoomRatio(target)
                    zoomRatio = target
                    return true
                }
            })

        tapDetector = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (capturing) return true
                    val cam = camera ?: return true
                    val point = previewView.meteringPointFactory.createPoint(e.x, e.y)
                    cam.cameraControl.startFocusAndMetering(
                        FocusMeteringAction.Builder(
                            point,
                            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
                    )
                    return true
                }
            })

        previewView.setOnTouchListener { _, e ->
            scaleDetector.onTouchEvent(e)
            tapDetector.onTouchEvent(e)
            true
        }
    }

    // ============================ BACK / INSETS ============================

    private fun handleBackButton() {
        onBackPressedDispatcher.addCallback(this) {
            when {
                capturing -> {
                    val n = FrameStore.activeCount
                    if (n > 0) {
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle(getString(R.string.msg_discard_title))
                            .setMessage(getString(R.string.msg_discard_body, n))
                            .setPositiveButton(getString(R.string.btn_discard)) { _, _ ->
                                returnToIdle(getString(R.string.msg_discarded))
                            }
                            .setNegativeButton(getString(R.string.btn_keep_going), null)
                            .show()
                    } else {
                        returnToIdle(getString(R.string.msg_idle_hint))
                    }
                }
                StitchManager.state.value is StitchState.Running -> {
                    Toast.makeText(this@MainActivity,
                        getString(R.string.msg_background_stitch), Toast.LENGTH_SHORT).show()
                    finish()
                }
                ivResult.visibility == View.VISIBLE -> closeResult()
                else -> finish()
            }
        }
    }

    private fun closeResult() {
        lastSavedUri = null
        StitchManager.resetToIdle()
        returnToIdle(getString(R.string.msg_idle_hint))
    }

    private fun applyWindowInsets() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val top = bars.top.toFloat()
            val bottom = bars.bottom.toFloat()

            panGuide.bottomInsetPx = bottom
            listOf(tvStatus, tvLevel, btnMode, btnSettings, btnAxis, draftContainer)
                .forEach { it.translationY = top }
            listOf(btnStart, btnGallery, btnCancel, btnSnap, btnDone, btnUndo, resultControls)
                .forEach { it.translationY = -bottom }
            insets
        }
    }

    // ============================ STITCH STATE ============================

    private fun observeStitchState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                StitchManager.state.collect { render(it) }
            }
        }
    }

    private fun render(s: StitchState) {
        val fresh = lastState is StitchState.Running
        when (s) {
            is StitchState.Running -> {
                btnStart.visibility = View.GONE
                btnGallery.visibility = View.GONE
                ivResult.visibility = View.GONE
                resultControls.visibility = View.GONE
                draftContainer.visibility = View.GONE
                hideCaptureControls()
                progressBar.visibility = View.VISIBLE
                tvStatus.text = getString(R.string.msg_stitch_start, s.frameCount)
                tvStatus.announceForAccessibility(getString(R.string.a11y_stitch_started))
                startTicker()
            }
            is StitchState.Success -> {
                tickerJob?.cancel()
                progressBar.visibility = View.GONE
                showResultBitmap(s.bitmap, fromSession = true)
                if (fresh) {
                    lastSavedUri = s.uri
                    ivResult.announceForAccessibility(getString(R.string.a11y_saved))
                    if (s.uri == null) {
                        Toast.makeText(this, getString(R.string.msg_save_failed), Toast.LENGTH_LONG).show()
                    } else {
                        tvStatus.text = getString(R.string.msg_saved)
                    }
                }
            }
            is StitchState.Failure -> {
                tickerJob?.cancel()
                progressBar.visibility = View.GONE
                capturing = true
                autoFinishArmed = true
                lastTriggerPan = currentPan
                retargetGuide()
                panGuide.verticalStrip = verticalMode
                showCaptureControls()
                tvStatus.text = messageFor(s.code)
            }
            StitchState.Idle -> Unit
        }
        lastState = s
    }

    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = lifecycleScope.launch {
            var elapsed = 0
            while (isActive) {
                delay(1000)
                elapsed++
                tvStatus.text = getString(R.string.msg_stitch_tick, elapsed)
            }
        }
    }

    private fun messageFor(code: Int) = when (code) {
        Stitcher.ERR_NEED_MORE_IMGS -> getString(R.string.fail_overlap)
        Stitcher.ERR_HOMOGRAPHY_EST_FAIL -> getString(R.string.fail_homography)
        else -> getString(R.string.fail_generic, code)
    }

    private fun restoreInterruptedSession() {
        if (FrameStore.activeCount > 0 && StitchManager.state.value !is StitchState.Running) {
            capturing = true
            panSign = if (verticalMode) 1f else -1f
            retargetGuide(fromCurrent = true)
            panGuide.verticalStrip = verticalMode
            showCaptureControls()
            tvStatus.text = getString(R.string.msg_restored, FrameStore.activeCount)
        }
    }

    private fun showFirstRunTips() {
        if (prefs.getBoolean("tips_shown", false)) return
        prefs.edit().putBoolean("tips_shown", true).apply()
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.tips_title))
            .setMessage(getString(R.string.tips_body))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CAMERA && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            btnStart.isEnabled = true
            startCamera(lockExposure = false)
        } else {
            Toast.makeText(this, getString(R.string.err_camera_permission), Toast.LENGTH_LONG).show()
        }
    }

    // ============================ SENSOR ============================

    override fun onResume() {
        super.onResume()
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, sensorHandler)
        }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != rotationSensor?.type) return

        SensorManager.getRotationMatrixFromVector(rotMat, event.values)
        SensorManager.getOrientation(rotMat, orient)
        val panValue = if (verticalMode) orient[1] else orient[0]

        if (capturing) {
            val diff = PanMath.angleDiff(panValue, lastPanAxis)
            if (abs(diff) > 0.002f) {
                totalPanDeg += Math.toDegrees(abs(diff).toDouble())
                panSign = if (diff > 0) 1f else -1f
                panDir = if (verticalMode) {
                    if (diff > 0) "↑" else "↓"
                } else {
                    if (diff < 0) "→" else "←"
                }
            }
        }
        lastPanAxis = panValue
        currentPan = panValue

        if (capturing && autoMode && !gyroCapturePending) {
            if (PanMath.absDiffDeg(currentPan, lastTriggerPan) >= yawStepDeg) {
                gyroCapturePending = true
                lastTriggerPan = currentPan
            }
        }

        val now = SystemClock.elapsedRealtime()
        if (capturing && now - lastGuideUpdate >= GUIDE_INTERVAL_MS) {
            lastGuideUpdate = now
            val showSlow = now - lastHudUpdate >= HUD_INTERVAL_MS
            if (showSlow) lastHudUpdate = now

            val pitchDeg = Math.toDegrees(orient[1].toDouble())
            val rollDeg = Math.toDegrees(orient[2].toDouble())
            val eRad = PanMath.angleDiff(guideTargetPan, currentPan)
            val eDeg = Math.toDegrees(eRad.toDouble()).toFloat()
            val frac = if (verticalMode) eRad / stepRad() else -eRad / stepRad()

            runOnUiThread {
                if (showSlow) updateHud(pitchDeg, rollDeg)
                if (autoMode) panGuide.update(frac, eDeg)
                updateGhost()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun updateHud(pitchDeg: Double, rollDeg: Double) {
        val level = if (verticalMode) abs(rollDeg) < 5
                    else abs(pitchDeg) < 7 && abs(rollDeg) < 5
        tvLevel.text = if (verticalMode)
            "Roll %+.0f°%s".format(rollDeg, if (level) " ✓" else "")
        else
            "Pitch %+.0f°  Roll %+.0f°%s".format(pitchDeg, rollDeg, if (level) "  ✓" else "")
        tvLevel.setTextColor(if (level) 0xFF66BB6A.toInt() else 0xFFFFFFFF.toInt())
        tvArrow.text = panDir
    }

    /**
     * Ghost overlay position. e = angle between the ghost frame's heading and
     * the current heading. Higher-yaw content sits LEFT → dx = -k·tan(e);
     * higher-pitch content sits ABOVE → dy = -k·tan(e).
     * k derives from the lens FOV divided by the zoom ratio (zoom locked
     * during capture, so k is constant for the whole session).
     */
    private fun updateGhost() {
        val g = FrameStore.ghostState
        if (g == null || previewView.width == 0) { ghostOverlay.clear(); return }

        val halfTan = baseHalfTan / zoomRatio
        val e = PanMath.angleDiff(g.pan, currentPan).coerceIn(-1.1f, 1.1f)
        val k = previewView.width / (2f * halfTan)
        val shift = k * tan(e)
        val dx = if (verticalMode) 0f else -shift
        val dy = if (verticalMode) -shift else 0f
        ghostOverlay.render(g.bmp, dx, dy)
    }

    private fun stepRad() = Math.toDegrees(yawStepDeg.toDouble()).toFloat()

    private fun retargetGuide(fromCurrent: Boolean = false) {
        val from = if (fromCurrent) currentPan else FrameStore.lastPan ?: currentPan
        guideTargetPan = from + panSign * stepRad()
    }

    // ============================ CAMERA ============================

    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera(lockExposure: Boolean) {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val previewBuilder = Preview.Builder()
            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1280, 720),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                            )
                        ).build()
                )

            if (lockExposure) {
                for (ext in listOf(
                    Camera2Interop.Extender<Preview>(previewBuilder),
                    Camera2Interop.Extender<ImageAnalysis>(analysisBuilder)
                )) {
                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
                    ext.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, true)
                }
            }

            val preview = previewBuilder.build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }

            val analysis = analysisBuilder
                .setTargetRotation(previewView.display?.rotation ?: 0)
                .build()
                .also {
                    it.setAnalyzer(analysisExecutor, ImageAnalysis.Analyzer { proxy -> analyze(proxy) })
                }

            try {
                provider.unbindAll()
                val cam = provider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
                camera = cam
                exposureLocked = lockExposure
                zoomRatio = 1f   // rebind resets hardware zoom; keep ghost math in sync
                queryFov(cam)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.err_camera_start, e.message), Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /** Lens horizontal FOV → tan basis for the ghost overlay (divided by zoom at use time). */
    @androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
    private fun queryFov(cam: Camera) {
        try {
            val c2 = Camera2CameraInfo.from(cam.cameraInfo)
            val focalMm = c2.getCameraCharacteristic(
                CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return
            val phys = c2.getCameraCharacteristic(
                CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return
            baseHalfTan = (phys.width / (2f * focalMm)).coerceIn(0.3f, 1.2f)
        } catch (_: Exception) {}
    }

    private fun unbindCamera() {
        try { cameraProvider?.unbindAll() } catch (_: Exception) {}
        camera = null
        exposureLocked = false
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (!capturing) return
            val manual = manualCapturePending
            if (!manual && !(autoMode && gyroCapturePending)) return

            if (!manual && gyroCapturePending) {
                val eRad = PanMath.angleDiff(guideTargetPan, currentPan)
                if (eRad * panSign > stepRad() * Tuning.captureBand) return
            }

            val now = System.currentTimeMillis()
            if (now - lastCaptureAt < MIN_INTERVAL_MS) return

            manualCapturePending = false
            gyroCapturePending = false
            lastCaptureAt = now

            val mat = toMat(image)
            if (mat == null) {
                retargetGuide(fromCurrent = true)
                return
            }

            if (engine.isBlurry(mat, Tuning.blurThreshold)) {
                mat.release()
                lastTriggerPan = currentPan
                retargetGuide(fromCurrent = true)
                runOnUiThread { tvStatus.text = getString(R.string.msg_blurry) }
                return
            }

            val count = FrameStore.add(mat, currentPan)
            retargetGuide()
            autoFinishArmed = true
            FrameStore.setGhost(makeGhost(mat), currentPan)

            runOnUiThread {
                captureFeedback()
                previewView.announceForAccessibility(
                    getString(R.string.a11y_frame_captured, count, maxFrames))
                tvStatus.text = getString(R.string.msg_captured,
                    count, maxFrames, totalPanDeg.roundToInt())
                if (count >= maxFrames && autoFinishArmed) finishPanorama()
            }

            if (livePreviewEnabled && !draftsThrottled &&
                count >= LIVE_MIN_FRAMES && count % LIVE_EVERY_N == 0) {
                lifecycleScope.launch { runLiveStitch() }
            }
        } finally {
            image.close()
        }
    }

    private fun makeGhost(mat: Mat): Bitmap? = runCatching {
        toBitmap(downscaleClone(mat, DeviceProfile.ghostWidth))
    }.getOrNull()

    /** ImageProxy (YUV) → upright RGB Mat, downscaled so width ≤ stitchWidthPx. */
    private fun toMat(image: ImageProxy): Mat? = try {
        var bmp = image.toBitmap()

        val degrees = image.imageInfo.rotationDegrees
        if (degrees != 0) {
            val m = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (rotated != bmp) bmp.recycle()
            bmp = rotated
        }

        val rgba = Mat()
        Utils.bitmapToMat(bmp, rgba)
        bmp.recycle()

        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()

        downscale(rgb, stitchWidthPx)
    } catch (e: Exception) {
        null
    }

    private fun captureFeedback() {
        flashOverlay.animate().cancel()
        flashOverlay.alpha = 0.6f
        flashOverlay.animate().alpha(0f).setDuration(200).start()
        vibrate()
    }

    private fun vibrate() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        when {
            v == null -> Unit
            Build.VERSION.SDK_INT >= 26 ->
                v.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            else -> @Suppress("DEPRECATION") v.vibrate(35)
        }
    }

    // ============================ SESSION ============================

    private fun showCaptureControls() {
        btnSnap.visibility = View.VISIBLE
        btnDone.visibility = View.VISIBLE
        btnCancel.visibility = View.VISIBLE
        btnUndo.visibility = View.VISIBLE
        tvLevel.visibility = View.VISIBLE
        ghostOverlay.visibility = View.VISIBLE
        panGuide.visibility = if (autoMode) View.VISIBLE else View.GONE
    }

    private fun hideCaptureControls() {
        btnSnap.visibility = View.GONE
        btnDone.visibility = View.GONE
        btnCancel.visibility = View.GONE
        btnUndo.visibility = View.GONE
        tvLevel.visibility = View.GONE
        ghostOverlay.visibility = View.GONE
        panGuide.visibility = View.GONE
    }

    private fun autoMsg(): String =
        if (verticalMode) getString(R.string.msg_auto_v, yawStepDeg)
        else getString(R.string.msg_auto_h, yawStepDeg)

    private fun setMaxBrightness(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
                               else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
    }

    private fun startCapture() {
        FrameStore.startSession()
        totalPanDeg = 0.0
        capturing = true
        lastCaptureAt = 0L
        gyroCapturePending = false
        manualCapturePending = false
        autoFinishArmed = true
        panSign = if (verticalMode) 1f else -1f
        retargetGuide(fromCurrent = true)

        ivResult.setImageBitmap(null)
        ivResult.visibility = View.GONE
        resultControls.visibility = View.GONE
        draftContainer.visibility = View.GONE
        btnStart.visibility = View.GONE
        btnGallery.visibility = View.GONE
        showCaptureControls()
        panGuide.verticalStrip = verticalMode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setMaxBrightness(true)

        if (!exposureLocked) startCamera(lockExposure = true)
        LocalStats.sessionStarted(autoMode, verticalMode)

        tvStatus.text = if (autoMode) autoMsg()
                        else getString(R.string.msg_manual)
    }

    private fun undoLastFrame() {
        val count = FrameStore.undoLast()
        FrameStore.rebuildGhost { m -> makeGhost(m) }
        retargetGuide()
        vibrate()
        tvStatus.text = getString(R.string.msg_undo, count, maxFrames)
    }

    private fun resumeCapture() {
        if (!FrameStore.hasKept) return
        FrameStore.resumeKept()
        autoFinishArmed = false
        capturing = true
        lastCaptureAt = 0L
        gyroCapturePending = false
        manualCapturePending = false
        retargetGuide(fromCurrent = true)

        ivResult.setImageBitmap(null)
        ivResult.visibility = View.GONE
        resultControls.visibility = View.GONE
        draftContainer.visibility = View.GONE
        btnStart.visibility = View.GONE
        btnGallery.visibility = View.GONE
        showCaptureControls()
        panGuide.verticalStrip = verticalMode
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setMaxBrightness(true)
        startCamera(lockExposure = true)

        tvStatus.text = getString(R.string.msg_resumed, FrameStore.activeCount)
    }

    private fun toggleMode() {
        if (rotationSensor == null) return
        autoMode = !autoMode
        btnMode.text = if (autoMode) getString(R.string.auto_on) else getString(R.string.auto_off)
        prefs.edit().putBoolean("auto_mode", autoMode).apply()
        if (capturing) {
            lastTriggerPan = currentPan
            panGuide.visibility = if (autoMode) View.VISIBLE else View.GONE
            tvStatus.text = if (autoMode) autoMsg() else getString(R.string.msg_manual)
        }
    }

    private fun toggleAxis() {
        verticalMode = !verticalMode
        btnAxis.text = if (verticalMode) getString(R.string.axis_v) else getString(R.string.axis_h)
        prefs.edit().putBoolean("vertical_mode", verticalMode).apply()
        panGuide.verticalStrip = verticalMode
        panSign = if (verticalMode) 1f else -1f
        FrameStore.clearGhost()
        retargetGuide(fromCurrent = true)
        if (capturing) {
            tvStatus.text = if (autoMode) autoMsg() else getString(R.string.msg_manual)
        }
    }

    private fun returnToIdle(message: String) {
        capturing = false
        gyroCapturePending = false
        manualCapturePending = false
        FrameStore.endSession()

        ivResult.setImageBitmap(null)
        ivResult.visibility = View.GONE
        resultControls.visibility = View.GONE
        draftContainer.visibility = View.GONE
        hideCaptureControls()
        btnStart.visibility = View.VISIBLE
        btnGallery.visibility = View.VISIBLE
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setMaxBrightness(false)
        tvStatus.text = message

        if (cameraProvider != null) startCamera(lockExposure = false)
    }

    // ============================ STITCHING ============================

    private fun finishPanorama() {
        if (StitchManager.state.value is StitchState.Running) return

        val taken = FrameStore.takeForStitch()
        if (taken == null) {
            tvStatus.text = getString(R.string.msg_need_two)
            return
        }

        capturing = false
        gyroCapturePending = false
        manualCapturePending = false

        draftContainer.visibility = View.GONE
        hideCaptureControls()

        StitchManager.submitFinal(taken.first, taken.second, StitchSettings(scansMode, stitchWidthPx))
    }

    private suspend fun runLiveStitch() {
        if (!liveStitchBusy.compareAndSet(false, true)) return
        try {
            if (!capturing) return
            val snapshot = FrameStore.snapshotForDraft(Tuning.draftWidth)
            if (snapshot.size < LIVE_MIN_FRAMES) { snapshot.forEach { it.release() }; return }

            withContext(Dispatchers.Main) {
                if (!capturing) { snapshot.forEach { it.release() }; return@withContext }
                tvDraftLabel.text = getString(R.string.draft_stitching, snapshot.size)
                draftContainer.visibility = View.VISIBLE
            }

            val pano = StitchManager.stitchDraft(snapshot, StitchSettings(scansMode, Tuning.draftWidth))
            snapshot.forEach { it.release() }

            withContext(Dispatchers.Main) {
                if (capturing) {
                    if (pano != null) {
                        val bmp = toBitmap(pano)
                        val old = ivDraft.drawable as? BitmapDrawable
                        ivDraft.setImageBitmap(bmp)
                        old?.bitmap?.let { if (it !== bmp && !it.isRecycled) it.recycle() }
                        tvDraftLabel.text = getString(R.string.draft_live, FrameStore.activeCount)
                    } else {
                        tvDraftLabel.text = getString(R.string.draft_failed)
                    }
                }
            }
            pano?.release()
        } finally {
            liveStitchBusy.set(false)
        }
    }

    // ============================ OUTPUT ============================

    private fun toBitmap(mat: Mat): Bitmap =
        Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888).also {
            Utils.matToBitmap(mat, it)
        }

    private fun showResultBitmap(bmp: Bitmap, fromSession: Boolean) {
        unbindCamera()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setMaxBrightness(false)

        val old = ivResult.drawable as? BitmapDrawable
        ivResult.setImageBitmap(bmp)
        old?.bitmap?.let { if (it !== bmp && !it.isRecycled) it.recycle() }

        ivResult.visibility = View.VISIBLE
        resultControls.visibility = View.VISIBLE
        btnAddMore.visibility = if (fromSession && FrameStore.hasKept) View.VISIBLE else View.GONE
        btnStart.visibility = View.GONE
        btnGallery.visibility = View.GONE
        draftContainer.visibility = View.GONE
        hideCaptureControls()
    }

    private fun decodeScaled(uri: Uri): Bitmap? = try {
        val maxDim = DeviceProfile.viewerMaxDim
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (e: Exception) {
        null
    }

    private fun showGallery() {
        GallerySheet(
            this,
            onOpen = { uri ->
                if (capturing) return@GallerySheet
                val bmp = decodeScaled(uri)
                if (bmp == null) {
                    Toast.makeText(this, getString(R.string.err_open_image), Toast.LENGTH_SHORT).show()
                } else {
                    lastSavedUri = uri
                    showResultBitmap(bmp, fromSession = false)
                }
            },
            onDeleted = { uri -> if (uri == lastSavedUri) lastSavedUri = null }
        ).show()
    }

    private fun shareResult() {
        val uri = lastSavedUri ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
    }

    private fun deleteResult() {
        lastSavedUri?.let { uri ->
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
        }
        lastSavedUri = null
        StitchManager.resetToIdle()
        returnToIdle(getString(R.string.msg_deleted))
    }

    // ============================ SETTINGS ============================

    private fun showSettingsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val tvYaw = view.findViewById<TextView>(R.id.tvYawStepLabel)
        val sbYaw = view.findViewById<SeekBar>(R.id.sbYawStep)
        val tvWidth = view.findViewById<TextView>(R.id.tvWidthLabel)
        val sbWidth = view.findViewById<SeekBar>(R.id.sbWidth)
        val cbLive = view.findViewById<CheckBox>(R.id.cbLivePreview)
        val cbScans = view.findViewById<CheckBox>(R.id.cbScans)
        val tvStats = view.findViewById<TextView>(R.id.tvStats)
        val tvAbout = view.findViewById<TextView>(R.id.tvAbout)

        sbYaw.max = 15
        sbYaw.progress = (yawStepDeg - 15).coerceIn(0, 15)
        tvYaw.text = getString(R.string.settings_yaw_step, yawStepDeg)
        sbYaw.setOnSeekBarChangeListener(seekListener { p ->
            tvYaw.text = getString(R.string.settings_yaw_step, p + 15)
        })

        sbWidth.max = WIDTH_CHOICES.size - 1
        sbWidth.progress = WIDTH_CHOICES.indexOf(stitchWidthPx).coerceAtLeast(0)
        tvWidth.text = getString(R.string.settings_width_cap, stitchWidthPx)
        sbWidth.setOnSeekBarChangeListener(seekListener { p ->
            tvWidth.text = getString(R.string.settings_width_cap, WIDTH_CHOICES[p])
        })

        cbLive.isChecked = livePreviewEnabled
        if (!DeviceProfile.draftsEnabled) {
            cbLive.isEnabled = false
            cbLive.text = getString(R.string.settings_live_preview_na,
                getString(R.string.settings_live_preview))
        }
        cbScans.isChecked = scansMode

        tvStats.text = LocalStats.diagnostics()
        tvAbout.text = getString(R.string.about_line, BuildConfig.VERSION_NAME)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.settings_title))
            .setView(view)
            .setPositiveButton(getString(R.string.settings_save)) { _, _ ->
                yawStepDeg = sbYaw.progress + 15
                stitchWidthPx = WIDTH_CHOICES[sbWidth.progress].coerceAtMost(DeviceProfile.maxWidth)
                livePreviewEnabled = cbLive.isChecked && DeviceProfile.draftsEnabled
                scansMode = cbScans.isChecked
                prefs.edit()
                    .putInt("yaw_step", yawStepDeg)
                    .putInt("stitch_width", stitchWidthPx)
                    .putBoolean("live_preview", livePreviewEnabled)
                    .putBoolean("scans_mode", scansMode)
                    .apply()
                if (capturing && autoMode) {
                    retargetGuide()
                    tvStatus.text = getString(R.string.msg_auto_step, yawStepDeg)
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    private inline fun seekListener(crossinline onChanged: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) { onChanged(progress) }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        }

    override fun onDestroy() {
        super.onDestroy()
        if (Build.VERSION.SDK_INT >= 29) {
            powerManager?.removeThermalStatusListener(thermalListener)
        }
        analysisExecutor.shutdown()
        sensorThread.quitSafely()
        tickerJob?.cancel()
        // Frames intentionally survive activity death (FrameStore is process-scoped)
    }
}
