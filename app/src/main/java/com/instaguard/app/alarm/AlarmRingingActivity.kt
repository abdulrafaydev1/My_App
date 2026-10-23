package com.instaguard.app.alarm

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.instaguard.app.databinding.ActivityAlarmRingingBinding
import com.instaguard.app.service.AlarmRingingService
import com.instaguard.app.service.AppBlockerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-Screen Hardcore Alarm Ringing Activity (v15.4 Modular 5-Question Mission Edition).
 * Enforces solving 5 randomized Math & City/Country/General Knowledge questions before stopping.
 * Features strict skip/change rules and a dedicated Auto-Solve test button.
 */
class AlarmRingingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingingBinding
    private lateinit var alarm: SmartAlarm
    private var isTest = false
    private var isSnooze = false

    private val questionEngine = AlarmQuestionEngine()
    private val totalQuestions = 5
    private var currentStep = 1
    private lateinit var currentQuestion: AlarmQuestion

    private val handler = Handler(Looper.getMainLooper())
    private var pulseAnimator: ObjectAnimator? = null

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
                Toast.makeText(
                    this@AlarmRingingActivity,
                    "🚫 Solve all 5 questions or use Test Auto-Solve to stop the alarm!",
                    Toast.LENGTH_SHORT
                ).show()
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

        // Draw initial Question 1 of 5
        currentStep = 1
        currentQuestion = questionEngine.nextQuestion()
        bindCurrentQuestionToUi()
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

            // Rule 4 & 5: Change to another unused GK question (or random unused)
            currentQuestion = questionEngine.nextUnusedGkOrRandomQuestion()
            bindCurrentQuestionToUi()
            val remaining = totalQuestions - currentStep + 1
            Toast.makeText(
                this,
                "🔄 Question changed! ($remaining questions left)",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun checkAndSubmitAnswer(userAnswer: String) {
        if (userAnswer.isBlank()) {
            Toast.makeText(this, "⚠️ Please enter or select an answer!", Toast.LENGTH_SHORT).show()
            return
        }

        val isCorrect = questionEngine.checkAnswer(currentQuestion, userAnswer)
        if (isCorrect) {
            hideKeyboard()
            if (currentStep < totalQuestions) {
                Toast.makeText(
                    this,
                    "✅ Correct! Question $currentStep completed.",
                    Toast.LENGTH_SHORT
                ).show()
                currentStep++
                currentQuestion = questionEngine.nextQuestion()
                bindCurrentQuestionToUi()
            } else {
                // All 5 Questions Solved!
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
        currentStep = totalQuestions
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
