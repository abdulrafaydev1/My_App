package com.instaguard.app.alarm

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
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
 * Full-Screen Alarm Ringing Activity.
 * Displays over lockscreen, turns on display, and requires solving 5 Hard Random Math Questions
 * before the alarm sound can be stopped. Cannot be exited via Back, Recents, or Power Menu.
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
                    "🚫 Solve all 5 Math Questions to stop the alarm!",
                    Toast.LENGTH_SHORT
                ).show()
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
            }
        }
    }

    private fun updateTapButton() {
        binding.btnRapidTap.text = "⚡ TAP ${tapsRemaining} TIMES TO WAKE UP (${tapsRemaining} left)"
    }

    /**
     * Generates fresh random arithmetic questions across 4 varied templates using nanoTime seed.
     * Template 0: (a * b) + c (a in 12..29, b in 4..9, c in 15..85)
     * Template 1: (a * b) - c (a in 14..32, b in 5..9, c in 11..49)
     * Template 2: a + b + c (a in 120..350, b in 110..290, c in 45..180)
     * Template 3: (a * b) + (c * d) (a in 7..15, b in 6..12, c in 5..11, d in 4..9)
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
                Toast.makeText(this, "🎯 All 5 Math Missions Solved! Awake and energized!", Toast.LENGTH_LONG).show()
                dismissAlarmSuccessfully()
            }
        } else {
            Toast.makeText(this, "❌ Incorrect answer! Try again.", Toast.LENGTH_SHORT).show()
            binding.etMathAnswer.setText("")
        }
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
        // Stop dedicated foreground service
        AlarmRingingService.stopAlarmAfterSolved(this, alarm.id)

        // Schedule snooze trigger
        AlarmManagerHelper.scheduleSnooze(this, alarm, alarm.snoozeMinutes)
        Toast.makeText(this, "💤 Alarm snoozed for ${alarm.snoozeMinutes} minutes.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun dismissAlarmSuccessfully() {
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
        // Sound is owned by AlarmRingingService and MUST NEVER stop on activity destroy unless solved!
        handler.removeCallbacks(clockRunnable)
        pulseAnimator?.cancel()
    }
}
