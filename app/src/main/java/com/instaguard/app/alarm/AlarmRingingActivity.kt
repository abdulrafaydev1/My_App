package com.instaguard.app.alarm

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.instaguard.app.databinding.ActivityAlarmRingingBinding
import com.instaguard.app.service.AlarmRingingService
import com.instaguard.app.service.AppBlockerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-Screen Hardcore Alarm Ringing Activity (v15.5 Persistent & Immersive Edition).
 * - Immersive sticky full-screen lock mode with system bars hidden.
 * - Persistent 5-question mission state synced with AlarmRingingService across rotations/recreations.
 * - Smooth 180ms cross-fade & horizontal slide animations between questions.
 * - Debounced back-swipe/back-button card shake guard.
 * - Safe app-switch re-entry guard via onUserLeaveHint.
 */
class AlarmRingingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingingBinding
    private lateinit var alarm: SmartAlarm
    private var isTest = false
    private var isSnooze = false

    private val totalQuestions = 5
    private var currentStep = 1
    private lateinit var currentQuestion: AlarmQuestion

    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null
    private var lastBackToastElapsedMs = 0L

    private val clockRunnable = object : Runnable {
        override fun run() {
            updateLiveClock()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wakeAndUnlockScreen()

        // Configure Immersive Sticky Mode & Prevent Edge Swipes Leaving Screen
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
        setFinishOnTouchOutside(false)

        binding = ActivityAlarmRingingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Block Android Back button: Alarm cannot be dismissed by pressing Back
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val step = AlarmRingingService.currentQuestionStep
                if (step <= totalQuestions) {
                    ObjectAnimator.ofFloat(binding.cardQuestionMission, "translationX", 0f, -14f, 14f, -10f, 10f, 0f).apply {
                        duration = 260
                        start()
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastBackToastElapsedMs >= 2000L) {
                        lastBackToastElapsedMs = now
                        val remaining = 6 - step
                        Toast.makeText(
                            this@AlarmRingingActivity,
                            "🔒 Complete all 5 questions ($remaining remaining) to stop the alarm!",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        })

        val alarmId = intent.getStringExtra(AlarmManagerHelper.EXTRA_ALARM_ID)
            ?: intent.getStringExtra("alarm_id") ?: ""
        isTest = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_TEST, false)
        isSnooze = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, false)

        alarm = AlarmManagerHelper.getAlarmById(this, alarmId) ?: SmartAlarm(
            id = alarmId,
            label = if (isTest) "Test Wake-Up Mission" else "Wake Up Mission",
            soundId = 2,
            snoozeMinutes = 0
        )

        // Ensure foreground AlarmRingingService is active and playing
        AlarmRingingService.startAlarm(
            context = this,
            alarmId = alarm.id,
            isTest = isTest,
            isSnooze = isSnooze,
            deleteAfter = alarm.deleteAfterRinging,
            soundId = alarm.soundId,
            forceMaxVolume = alarm.forceMaxVolume,
            heavyVibration = alarm.heavyVibration,
            aiVoice = alarm.aiVoiceAnnouncement
        )

        bindAlarmDataToUi()
        setupQuestionControls()
        setupSnoozeAndTestControls()
        startClockAndPulseAnimations()

        // Bind to persistent 5-question session in AlarmRingingService
        val (step, question) = AlarmRingingService.getOrInitQuestionSession()
        currentStep = step
        currentQuestion = question
        bindCurrentQuestionToUi()
    }

    override fun onResume() {
        super.onResume()
        AppBlockerService.setAlarmRingingState(this, isRinging = true, alarmId = alarm.id)

        // Refresh and re-sync persistent session state on resume/recreation
        val (step, question) = AlarmRingingService.getOrInitQuestionSession()
        currentStep = step
        currentQuestion = question
        bindCurrentQuestionToUi()

        // Re-hide system bars in case window focus changed
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // If alarm is still ringing and 5 questions are not finished, bring back to front
        if (AlarmRingingService.isCurrentlyRinging && AlarmRingingService.currentQuestionStep <= totalQuestions) {
            handler.postDelayed({
                if (AlarmRingingService.isCurrentlyRinging && !isFinishing && !isDestroyed) {
                    try {
                        val bringFrontIntent = Intent(applicationContext, AlarmRingingActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(bringFrontIntent)
                    } catch (_: Exception) {}
                }
            }, 250L)
        }
    }

    private fun wakeAndUnlockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun bindAlarmDataToUi() {
        binding.tvAlarmLabel.text = "🏷️ " + alarm.label
        val sound = HardAlarmSound.fromId(alarm.soundId)
        binding.tvSoundTitle.text = sound.title
        binding.tvAutoDeleteBadge.visibility = if (alarm.deleteAfterRinging) View.VISIBLE else View.GONE
        updateLiveClock()
    }

    private fun updateLiveClock() {
        val now = Date()
        val timeFormat = SimpleDateFormat("hh:mm:ss a", Locale.US)
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.US)
        binding.tvClockLive.text = timeFormat.format(now)
        binding.tvDateLive.text = dateFormat.format(now)
    }

    private fun bindCurrentQuestionToUi() {
        binding.tvQuestionProgress.text = "Question $currentStep of $totalQuestions"
        binding.tvCategoryBadge.text = currentQuestion.categoryBadgeText
        binding.tvQuestionText.text = currentQuestion.questionText
        binding.etAnswer.setText("")

        if (currentQuestion.category == QuestionCategory.MATH) {
            // Rule 3: Math CANNOT be changed
            binding.tvCategoryBadge.setTextColor(Color.parseColor("#2EE59D"))
            binding.etAnswer.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            binding.etAnswer.hint = "Enter calculated number..."
            binding.layoutChoices.visibility = View.GONE

            binding.btnChangeQuestion.isEnabled = false
            binding.btnChangeQuestion.alpha = 0.45f
            binding.btnChangeQuestion.text = "🔒 Math Question (Cannot Change)"
        } else {
            // Rule 4: City/Country/GK CAN be changed
            binding.tvCategoryBadge.setTextColor(Color.parseColor("#FF8A1E"))
            binding.etAnswer.inputType = InputType.TYPE_CLASS_TEXT
            binding.etAnswer.hint = "Tap choice above or type answer..."

            val choices = currentQuestion.choices
            if (!choices.isNullOrEmpty()) {
                val shuffled = choices.shuffled()
                val choiceButtons = listOf(
                    binding.btnChoice1,
                    binding.btnChoice2,
                    binding.btnChoice3,
                    binding.btnChoice4
                )
                for (i in choiceButtons.indices) {
                    if (i < shuffled.size) {
                        choiceButtons[i].visibility = View.VISIBLE
                        choiceButtons[i].text = shuffled[i]
                        choiceButtons[i].isEnabled = true
                        val optionText = shuffled[i]
                        choiceButtons[i].setOnClickListener {
                            checkAndSubmitAnswer(optionText)
                        }
                    } else {
                        choiceButtons[i].visibility = View.GONE
                    }
                }
                binding.layoutChoices.visibility = View.VISIBLE
            } else {
                binding.layoutChoices.visibility = View.GONE
            }

            binding.btnChangeQuestion.isEnabled = true
            binding.btnChangeQuestion.alpha = 1.0f
            binding.btnChangeQuestion.text = "🔄 Change / Skip Question"
        }
    }

    private fun setupQuestionControls() {
        binding.btnSubmitAnswer.setOnClickListener {
            val text = binding.etAnswer.text.toString().trim()
            checkAndSubmitAnswer(text)
        }

        binding.etAnswer.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO) {
                val text = binding.etAnswer.text.toString().trim()
                checkAndSubmitAnswer(text)
                true
            } else {
                false
            }
        }

        binding.btnChangeQuestion.setOnClickListener {
            if (currentQuestion.category == QuestionCategory.MATH) {
                Toast.makeText(
                    this,
                    "🔒 Math questions cannot be changed! Solve to proceed.",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            // Rule 4 & 5: Change to another unused GK question via AlarmRingingService
            currentQuestion = AlarmRingingService.skipCurrentQuestion()
            animateQuestionTransition {
                bindCurrentQuestionToUi()
            }
            val remaining = totalQuestions - currentStep + 1
            Toast.makeText(
                this,
                "🔄 Question changed! ($remaining questions left)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * Smooth 180ms cross-fade and 10dp horizontal slide transition between questions.
     */
    private fun animateQuestionTransition(onMidPoint: () -> Unit) {
        val interpolator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)
        } else {
            DecelerateInterpolator()
        }
        val card = binding.cardQuestionMission
        card.animate()
            .alpha(0f)
            .translationX(-10f)
            .setDuration(90)
            .setInterpolator(interpolator)
            .withEndAction {
                onMidPoint()
                card.translationX = 10f
                card.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(90)
                    .setInterpolator(interpolator)
                    .start()
            }
            .start()
    }

    private fun checkAndSubmitAnswer(userAnswer: String) {
        if (userAnswer.isBlank()) {
            Toast.makeText(this, "⚠️ Please enter or select an answer!", Toast.LENGTH_SHORT).show()
            return
        }

        val engine = AlarmRingingService.activeQuestionEngine ?: AlarmQuestionEngine()
        val isCorrect = engine.checkAnswer(currentQuestion, userAnswer)
        if (isCorrect) {
            hideKeyboard()
            if (currentStep < totalQuestions) {
                val (nextStep, nextQ) = AlarmRingingService.advanceToNextQuestion()
                currentStep = nextStep
                if (nextQ != null) {
                    currentQuestion = nextQ
                    animateQuestionTransition {
                        bindCurrentQuestionToUi()
                    }
                    Toast.makeText(
                        this,
                        "✅ Correct! Question ${currentStep - 1} completed.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } else {
                // All 5 Questions Solved! Disable buttons immediately to prevent double-tap race conditions
                disableAllButtons()
                dismissAlarmSuccessfully()
            }
        } else {
            Toast.makeText(
                this,
                "❌ Incorrect answer! Try again.",
                Toast.LENGTH_SHORT
            ).show()
            binding.etAnswer.selectAll()
        }
    }

    private fun disableAllButtons() {
        binding.btnSubmitAnswer.isEnabled = false
        binding.btnAutoSolveTest.isEnabled = false
        binding.btnChangeQuestion.isEnabled = false
        binding.btnChoice1.isEnabled = false
        binding.btnChoice2.isEnabled = false
        binding.btnChoice3.isEnabled = false
        binding.btnChoice4.isEnabled = false
    }

    private fun setupSnoozeAndTestControls() {
        // Dedicated Auto-Solve Test Button
        binding.btnAutoSolveTest.setOnClickListener {
            handleAutoSolveTest()
        }

        // Snooze Control
        if (alarm.snoozeMinutes <= 0) {
            binding.btnSnooze.visibility = View.GONE
        } else {
            binding.btnSnooze.visibility = View.VISIBLE
            binding.btnSnooze.text = "💤 Snooze for ${alarm.snoozeMinutes} Min"
            binding.btnSnooze.setOnClickListener {
                snoozeAlarm()
            }
        }
    }

    private fun handleAutoSolveTest() {
        disableAllButtons()
        AlarmRingingService.stopAlarmAfterSolved(this, alarm.id)
        AppBlockerService.clearAlarmRingingState(this)
        AlarmManagerHelper.handleAlarmDismissed(this, alarm.id)

        Toast.makeText(
            this,
            "🧪 Test Auto-Solve Complete! All 5 questions solved & alarm stopped.",
            Toast.LENGTH_LONG
        ).show()
        finishAndRemoveTask()
    }

    private fun snoozeAlarm() {
        disableAllButtons()
        AppBlockerService.clearAlarmRingingState(this)
        AlarmRingingService.stopAlarmAfterSolved(this, alarm.id)
        AlarmManagerHelper.scheduleSnooze(this, alarm, alarm.snoozeMinutes)
        Toast.makeText(this, "💤 Alarm snoozed for ${alarm.snoozeMinutes} minutes.", Toast.LENGTH_LONG).show()
        finishAndRemoveTask()
    }

    private fun dismissAlarmSuccessfully() {
        AppBlockerService.clearAlarmRingingState(this)
        AlarmRingingService.stopAlarmAfterSolved(this, alarm.id)
        AlarmManagerHelper.handleAlarmDismissed(this, alarm.id)

        Toast.makeText(this, "✅ All 5 Questions Solved! Alarm Dismissed.", Toast.LENGTH_LONG).show()
        finishAndRemoveTask()
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(binding.root.windowToken, 0)
        } catch (_: Exception) {}
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
    }
}
