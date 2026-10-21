package com.instaguard.app.alarm

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.instaguard.app.databinding.ActivityAlarmRingingBinding
import com.instaguard.app.service.AlarmRingingService
import com.instaguard.app.service.AppBlockerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-Screen Alarm Ringing Activity (v14.0 Master Edition).
 * 2-Stage Wake-Up Mission:
 * Stage 1: 5 Hard Math Questions (or Rapid Tap / Standard Tap) + "🧪 Skip 5 Questions" test bypass.
 * Stage 2: CameraX + Google ML Kit 400+ Object Verification Scanner matching 3-State reference UI
 *          (Idle White -> Error Red -> Verified Green) + Flashlight & Target Cycling.
 * Cannot be bypassed via Back, Recents, or Power Off menu until verification is complete!
 */
class AlarmRingingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingingBinding
    private lateinit var alarm: SmartAlarm
    private var isTest = false
    private var isSnooze = false

    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null

    // Math Mission State: 5 Hard Questions (Stage 1 to 5)
    private var mathStep = 1
    private var expectedMathAnswer = 0

    // Rapid Tap Mission State
    private var tapsRemaining = 15

    // Stage 2 Camera Scanner State
    private var isStage2Active = false
    private lateinit var targetItem: AlarmScanItem
    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraControl: CameraControl? = null
    private var cameraInfo: CameraInfo? = null
    private var isTorchOn = false
    private var lastSeenLabelsAdvice = ""

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCameraX()
        } else {
            Toast.makeText(
                this,
                "⚠️ Camera permission required to scan ${targetItem.displayName}!",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateLiveClock()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAndUnlockScreen()

        binding = ActivityAlarmRingingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Block Android Back button: Alarm cannot be dismissed by pressing Back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val message = if (isStage2Active) {
                    "🚫 Scan ${targetItem.displayName} on camera to stop the alarm!"
                } else {
                    "🚫 Solve all 5 Math Questions to stop the alarm!"
                }
                Toast.makeText(this@AlarmRingingActivity, message, Toast.LENGTH_SHORT).show()
            }
        })

        val alarmId = intent.getStringExtra(AlarmManagerHelper.EXTRA_ALARM_ID) ?: ""
        isTest = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_TEST, false)
        isSnooze = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, false)

        alarm = AlarmManagerHelper.getAlarmById(this, alarmId) ?: SmartAlarm(
            label = if (isTest) "Test Wake-Up Mission" else "Wake Up Mission",
            soundId = 2,
            snoozeMinutes = 0,
            dismissMissionType = 1
        )

        // Initialize target scan item for Stage 2
        val targetId = alarm.scanTargetItemId
        targetItem = if (targetId >= 0) {
            AlarmScanItem.fromId(targetId)
        } else {
            AlarmScanItem.randomItem()
        }

        // Ensure foreground AlarmRingingService is active and playing
        AlarmRingingService.startAlarm(
            context = this,
            alarmId = alarm.id,
            isTest = isTest,
            isSnooze = isSnooze,
            deleteAfter = alarm.deleteAfterRinging
        )

        bindAlarmDataToUi()
        setupMissionControls()
        setupCameraScannerControls()
        setupSnoozeAndEmergencyControls()
        startClockAndPulseAnimations()
    }

    override fun onResume() {
        super.onResume()
        AppBlockerService.setAlarmRingingState(this, isRinging = true, alarmId = alarm.id)
    }

    private fun wakeAndUnlockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        }
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
    }

    private fun bindAlarmDataToUi() {
        binding.tvAlarmLabel.text = alarm.label.ifBlank { "🏷️ Wake Up Mission" }
        binding.tvSoundTitle.text = HardAlarmSound.fromId(alarm.soundId).title

        if (alarm.deleteAfterRinging && !isTest) {
            binding.tvAutoDeleteBadge.visibility = View.VISIBLE
        } else {
            binding.tvAutoDeleteBadge.visibility = View.GONE
        }

        updateLiveClock()
    }

    private fun updateLiveClock() {
        val now = Date()
        val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.US)
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.US)
        binding.tvClockLive.text = timeFormat.format(now).uppercase(Locale.US)
        binding.tvDateLive.text = dateFormat.format(now)
    }

    private fun setupMissionControls() {
        when (alarm.dismissMissionType) {
            2 -> {
                // Rapid 15-Tap Challenge
                binding.btnStandardDismiss.visibility = View.GONE
                binding.layoutMathMission.visibility = View.GONE
                binding.layoutTapMission.visibility = View.VISIBLE
                tapsRemaining = 15
                updateTapButton()

                binding.btnRapidTap.setOnClickListener {
                    tapsRemaining--
                    if (tapsRemaining <= 0) {
                        dismissAlarmSuccessfully()
                    } else {
                        updateTapButton()
                    }
                }
            }
            0 -> {
                // Standard Tap
                binding.layoutMathMission.visibility = View.GONE
                binding.layoutTapMission.visibility = View.GONE
                binding.btnStandardDismiss.visibility = View.VISIBLE

                binding.btnStandardDismiss.setOnClickListener {
                    dismissAlarmSuccessfully()
                }
            }
            else -> {
                // Default: 5 Hard Random Math Questions (Stage 1 to 5)
                binding.btnStandardDismiss.visibility = View.GONE
                binding.layoutTapMission.visibility = View.GONE
                binding.layoutMathMission.visibility = View.VISIBLE
                mathStep = 1
                generateNextMathProblem()

                binding.btnSubmitMath.setOnClickListener {
                    checkMathAnswer()
                }

                // 🧪 Skip 5 Questions (Test Camera Scanner) button
                binding.btnSkipToCameraTest.setOnClickListener {
                    Toast.makeText(this, "🧪 Skipping Math: Launching Camera Scanner...", Toast.LENGTH_SHORT).show()
                    transitionToStage2CameraScanner()
                }
            }
        }
    }

    private fun updateTapButton() {
        binding.btnRapidTap.text = "⚡ TAP ${tapsRemaining} TIMES TO WAKE UP (${tapsRemaining} left)"
    }

    /**
     * Generates fresh random arithmetic questions across 4 varied templates using nanoTime seed.
     */
    private fun generateNextMathProblem() {
        val rng = kotlin.random.Random(System.nanoTime())
        val template = rng.nextInt(4)
        val problemStr: String
        val answer: Int

        when (template) {
            0 -> {
                val a = rng.nextInt(12, 30) // 12..29
                val b = rng.nextInt(4, 10)  // 4..9
                val c = rng.nextInt(15, 86) // 15..85
                problemStr = "($a × $b) + $c = ?"
                answer = (a * b) + c
            }
            1 -> {
                val a = rng.nextInt(14, 33) // 14..32
                val b = rng.nextInt(5, 10)  // 5..9
                val c = rng.nextInt(11, 50) // 11..49
                problemStr = "($a × $b) - $c = ?"
                answer = (a * b) - c
            }
            2 -> {
                val a = rng.nextInt(120, 351) // 120..350
                val b = rng.nextInt(110, 291) // 110..290
                val c = rng.nextInt(45, 181)  // 45..180
                problemStr = "$a + $b + $c = ?"
                answer = a + b + c
            }
            else -> {
                val a = rng.nextInt(7, 16)  // 7..15
                val b = rng.nextInt(6, 13)  // 6..12
                val c = rng.nextInt(5, 12)  // 5..11
                val d = rng.nextInt(4, 10)  // 4..9
                problemStr = "($a × $b) + ($c × $d) = ?"
                answer = (a * b) + (c * d)
            }
        }

        expectedMathAnswer = answer
        binding.tvMathStep.text = "🧠 WAKE-UP MATH MISSION (Stage $mathStep of 5)"
        binding.tvMathProblem.text = problemStr
        binding.etMathAnswer.setText("")
    }

    private fun checkMathAnswer() {
        val input = binding.etMathAnswer.text.toString().trim().toIntOrNull()
        if (input != null && input == expectedMathAnswer) {
            if (mathStep < 5) {
                mathStep++
                Toast.makeText(this, "✅ Correct! Stage $mathStep of 5 unlocked.", Toast.LENGTH_SHORT).show()
                generateNextMathProblem()
            } else {
                Toast.makeText(this, "🎯 All 5 Math Missions Solved! Now verify item on camera!", Toast.LENGTH_LONG).show()
                transitionToStage2CameraScanner()
            }
        } else {
            Toast.makeText(this, "❌ Incorrect answer! Try again.", Toast.LENGTH_SHORT).show()
            binding.etMathAnswer.setText("")
        }
    }

    // ==========================================
    // STAGE 2: CameraX & ML Kit Object Scanner
    // ==========================================

    private fun transitionToStage2CameraScanner() {
        isStage2Active = true
        binding.layoutStage1Container.visibility = View.GONE
        binding.layoutStage2CameraScanner.visibility = View.VISIBLE

        updateTargetItemUi()
        resetToScanState1()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCameraX()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun updateTargetItemUi() {
        binding.tvCameraScanTitle.text = "Scan this Item [${targetItem.displayName}] on camera"
        binding.tvCameraScanSubtitle.text = "Align item within the frame"
    }

    private fun setupCameraScannerControls() {
        // 1. Flashlight / Torch Toggle Button
        binding.btnToggleTorch.setOnClickListener {
            toggleTorch()
        }

        // 2. Switch Target Item Button
        binding.btnSwitchTargetItem.setOnClickListener {
            targetItem = AlarmScanItem.getNextItem(targetItem)
            updateTargetItemUi()
            resetToScanState1()
            Toast.makeText(this, "🔄 Switched to: ${targetItem.displayName}", Toast.LENGTH_SHORT).show()
        }

        // 3. Center Capture Shutter Button
        binding.btnCaptureScan.setOnClickListener {
            captureAndAnalyzeFrame()
        }

        // 4. Retry button in State 2
        binding.btnRetryScan.setOnClickListener {
            resetToScanState1()
        }

        // 5. Scanning Advice in State 2
        binding.tvScanningAdvice.setOnClickListener {
            showScanningAdviceDialog()
        }

        // 6. Verified Dismiss Button in State 3
        binding.btnVerifiedDismiss.setOnClickListener {
            dismissAlarmSuccessfully()
        }
    }

    private fun startCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreviewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider?.unbindAll()
                val camera = cameraProvider?.bindToLifecycle(this, cameraSelector, preview)
                cameraControl = camera?.cameraControl
                cameraInfo = camera?.cameraInfo
            } catch (e: Exception) {
                Log.e("AlarmRingingActivity", "CameraX initialization failed", e)
                Toast.makeText(this, "Camera initialization failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun toggleTorch() {
        val newTorchState = !isTorchOn
        cameraControl?.enableTorch(newTorchState)?.addListener({
            isTorchOn = newTorchState
            runOnUiThread {
                binding.btnToggleTorch.text = if (isTorchOn) "🔦 ON" else "🔦"
                binding.btnToggleTorch.backgroundTintList = ColorStateList.valueOf(
                    if (isTorchOn) Color.parseColor("#FFD600") else Color.parseColor("#161B22")
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureAndAnalyzeFrame() {
        val bitmap = binding.cameraPreviewView.bitmap
        if (bitmap == null) {
            Toast.makeText(this, "Camera preview initializing, please try in 1 second...", Toast.LENGTH_SHORT).show()
            return
        }

        // Freeze preview on screen
        binding.capturedFreezeImageView.setImageBitmap(bitmap)
        binding.capturedFreezeImageView.visibility = View.VISIBLE

        val isClear = isImageClearAndAdequate(bitmap)
        if (!isClear) {
            lastSeenLabelsAdvice = "Camera lens is covered or the frame is too dark. Align item under good lighting."
            onScanError()
            return
        }

        val options = ImageLabelerOptions.Builder()
            .setConfidenceThreshold(0.40f)
            .build()
        val labeler = ImageLabeling.getClient(options)
        val inputImage = InputImage.fromBitmap(bitmap, 0)

        labeler.process(inputImage)
            .addOnSuccessListener { labels ->
                var highestConfidence = 0f
                var matched = false
                val detectedNames = mutableListOf<String>()

                for (label in labels) {
                    val text = label.text.lowercase(Locale.ROOT)
                    val conf = label.confidence
                    detectedNames.add("${label.text} (${(conf * 100).toInt()}%)")

                    for (kw in targetItem.matchedKeywords) {
                        if (text.contains(kw) || kw.contains(text)) {
                            matched = true
                            if (conf > highestConfidence) {
                                highestConfidence = conf
                            }
                        }
                    }
                }

                lastSeenLabelsAdvice = if (detectedNames.isNotEmpty()) {
                    "ML Kit detected: " + detectedNames.take(5).joinToString(", ")
                } else {
                    "No clear objects detected. Move closer to ${targetItem.displayName} with good lighting."
                }

                if (matched) {
                    val scaledConfidence = 92.0f + (highestConfidence * 7.8f).coerceIn(0f, 7.8f)
                    onScanSuccess(scaledConfidence)
                } else {
                    onScanError()
                }
            }
            .addOnFailureListener { e ->
                lastSeenLabelsAdvice = "Vision analysis error: ${e.message}"
                onScanError()
            }
    }

    private fun onScanSuccess(confidence: Float) {
        binding.scannerFrameOverlay.scanState = ScanUiState.VERIFIED_GREEN
        binding.bannerErrorInside.visibility = View.GONE
        binding.cardVerifiedInside.visibility = View.VISIBLE
        binding.tvVerifiedConfidence.text = "Confidence ${String.format(Locale.US, "%.1f", confidence)}%"

        binding.layoutBottomState1.visibility = View.GONE
        binding.layoutBottomState2Error.visibility = View.GONE
        binding.layoutBottomState3Verified.visibility = View.VISIBLE

        // Immediately stop alarm sound & vibration and unlock Calculator!
        AlarmRingingService.stopAlarmAfterSolved(this, alarm.id)
        AppBlockerService.clearAlarmRingingState(this)

        Toast.makeText(this, "🎯 ${targetItem.displayName} Verified! Alarm stopped.", Toast.LENGTH_SHORT).show()

        // Auto-close activity after 1800ms
        handler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                finish()
            }
        }, 1800L)
    }

    private fun onScanError() {
        binding.scannerFrameOverlay.scanState = ScanUiState.ERROR_RED
        binding.bannerErrorInside.visibility = View.VISIBLE
        binding.cardVerifiedInside.visibility = View.GONE

        binding.layoutBottomState1.visibility = View.GONE
        binding.layoutBottomState2Error.visibility = View.VISIBLE
        binding.layoutBottomState3Verified.visibility = View.GONE

        // Alarm keeps ringing!
    }

    private fun resetToScanState1() {
        binding.scannerFrameOverlay.scanState = ScanUiState.IDLE_WHITE
        binding.bannerErrorInside.visibility = View.GONE
        binding.cardVerifiedInside.visibility = View.GONE
        binding.capturedFreezeImageView.visibility = View.GONE

        binding.layoutBottomState1.visibility = View.VISIBLE
        binding.layoutBottomState2Error.visibility = View.GONE
        binding.layoutBottomState3Verified.visibility = View.GONE
    }

    private fun isImageClearAndAdequate(bitmap: Bitmap): Boolean {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 10 || h < 10) return false

        var totalLum = 0.0
        var totalLumSq = 0.0
        val sampleStepX = (w / 16).coerceAtLeast(1)
        val sampleStepY = (h / 16).coerceAtLeast(1)
        var count = 0

        for (y in 0 until h step sampleStepY) {
            for (x in 0 until w step sampleStepX) {
                val pixel = bitmap.getPixel(x, y)
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                val lum = 0.299 * r + 0.587 * g + 0.114 * b
                totalLum += lum
                totalLumSq += lum * lum
                count++
            }
        }

        if (count == 0) return false
        val meanLum = totalLum / count
        val variance = (totalLumSq / count) - (meanLum * meanLum)

        // Ensure not completely black (meanLum >= 12) or flat (variance > 8)
        return meanLum in 12.0..248.0 && variance > 8.0
    }

    private fun showScanningAdviceDialog() {
        AlertDialog.Builder(this)
            .setTitle("💡 Scanning Advice: ${targetItem.displayName}")
            .setMessage(
                "How to verify '${targetItem.displayName}':\n\n" +
                "1. Center the ${targetItem.displayName} fully inside the rounded frame.\n" +
                "2. Turn on room lights or tap the 🔦 flashlight button.\n" +
                "3. Ensure the camera is held still and focused.\n\n" +
                "Diagnostic details:\n$lastSeenLabelsAdvice\n\n" +
                "Tip: You can tap 🔄 in State 1 to switch to a different item if needed!"
            )
            .setPositiveButton("Got It", null)
            .show()
    }

    private fun setupSnoozeAndEmergencyControls() {
        if (alarm.snoozeMinutes <= 0) {
            binding.btnSnooze.visibility = View.GONE
        } else {
            binding.btnSnooze.visibility = View.VISIBLE
            binding.btnSnooze.text = "💤 Snooze for ${alarm.snoozeMinutes} Min"
            binding.btnSnooze.setOnClickListener {
                snoozeAlarm()
            }
        }

        // Even in test mode, instant bypass button is hidden to ensure mission completion
        binding.btnEmergencyStop.visibility = View.GONE
    }

    private fun snoozeAlarm() {
        AppBlockerService.clearAlarmRingingState(this)
        // Stop dedicated foreground service
        AlarmRingingService.stopAlarmAfterSolved(this, alarm.id)

        // Schedule snooze trigger
        AlarmManagerHelper.scheduleSnooze(this, alarm, alarm.snoozeMinutes)
        Toast.makeText(this, "💤 Alarm snoozed for ${alarm.snoozeMinutes} minutes.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun dismissAlarmSuccessfully() {
        AppBlockerService.clearAlarmRingingState(this)
        // Stop dedicated foreground service and handle post-dismissal repeat/auto-delete logic
        AlarmRingingService.stopAlarmAfterSolved(this, alarm.id)

        Toast.makeText(this, "✅ Alarm dismissed successfully!", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun startClockAndPulseAnimations() {
        handler.post(clockRunnable)

        pulseAnimator = ObjectAnimator.ofFloat(binding.viewRingingDot, View.ALPHA, 1f, 0.2f, 1f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockRunnable)
        pulseAnimator?.cancel()
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
