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
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.instaguard.app.databinding.ActivityAlarmRingingBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-Screen Alarm Ringing Activity.
 * Displays over lockscreen, turns on display, streams synthesized hardcore alarm sound,
 * drives continuous earthquake vibration, speaks AI voice alerts,
 * and handles Snooze, Math/Tap Wake-Up Missions, and Delete-After-Ringing logic.
 */
class AlarmRingingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingingBinding
    private lateinit var alarm: SmartAlarm
    private var isTest = false
    private var isSnooze = false

    private val handler = Handler(Looper.getMainLooper())
    private var vibrator: Vibrator? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pulseAnimator: ObjectAnimator? = null

    // Math Mission State
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

        val alarmId = intent.getStringExtra(AlarmManagerHelper.EXTRA_ALARM_ID) ?: ""
        isTest = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_TEST, false)
        isSnooze = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, false)

        alarm = AlarmManagerHelper.getAlarmById(this, alarmId) ?: SmartAlarm(
            label = if (isTest) "Test Wake-Up Mission" else "Wake Up Mission",
            soundId = 0,
            snoozeMinutes = 5,
            dismissMissionType = 0
        )

        initVibrator()
        initTts()
        bindAlarmDataToUi()
        startAlarmAudioAndVibration()
        setupMissionControls()
        setupSnoozeAndEmergencyControls()
        startClockAndPulseAnimations()
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

    private fun startAlarmAudioAndVibration() {
        // 1. Audio
        HardAlarmSoundEngine.startPlaying(
            context = this,
            soundId = alarm.soundId,
            forceMaxVolume = alarm.forceMaxVolume,
            loop = true
        )

        // 2. Vibration
        if (alarm.heavyVibration) {
            val pattern = longArrayOf(0, 1000, 300, 1000, 300)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, 0)
                vibrator?.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
        }
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun initTts() {
        if (!alarm.aiVoiceAnnouncement) return
        try {
            tts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.US
                    tts?.setSpeechRate(0.95f)
                    isTtsReady = true
                    speakAlarmAnnouncement()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun speakAlarmAnnouncement() {
        if (!isTtsReady || !alarm.aiVoiceAnnouncement) return
        val speech = "Attention! Wake up now! It is time for ${alarm.label}!"
        tts?.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "alarm_announcement")
    }

    private fun setupMissionControls() {
        when (alarm.dismissMissionType) {
            1 -> {
                // Math Mission
                binding.btnStandardDismiss.visibility = View.GONE
                binding.layoutTapMission.visibility = View.GONE
                binding.layoutMathMission.visibility = View.VISIBLE
                mathStep = 1
                generateNextMathProblem()

                binding.btnSubmitMath.setOnClickListener {
                    checkMathAnswer()
                }
            }
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
            else -> {
                // Standard Tap
                binding.layoutMathMission.visibility = View.GONE
                binding.layoutTapMission.visibility = View.GONE
                binding.btnStandardDismiss.visibility = View.VISIBLE

                binding.btnStandardDismiss.setOnClickListener {
                    dismissAlarmSuccessfully()
                }
            }
        }
    }

    private fun updateTapButton() {
        binding.btnRapidTap.text = "⚡ TAP ${tapsRemaining} TIMES TO WAKE UP (${tapsRemaining} left)"
    }

    private fun generateNextMathProblem() {
        val num1 = (12..48).random()
        val num2 = (4..9).random()
        val num3 = (15..75).random()
        val isAddition = (0..1).random() == 1

        val problemStr = if (isAddition) "($num1 × $num2) + $num3 = ?" else "($num1 × $num2) - $num3 = ?"
        expectedMathAnswer = if (isAddition) (num1 * num2) + num3 else (num1 * num2) - num3

        binding.tvMathStep.text = "🧠 WAKE-UP MATH MISSION (Stage $mathStep of 3)"
        binding.tvMathProblem.text = problemStr
        binding.etMathAnswer.setText("")
    }

    private fun checkMathAnswer() {
        val input = binding.etMathAnswer.text.toString().trim().toIntOrNull()
        if (input != null && input == expectedMathAnswer) {
            if (mathStep < 3) {
                mathStep++
                Toast.makeText(this, "✅ Correct! Stage $mathStep unlocked.", Toast.LENGTH_SHORT).show()
                generateNextMathProblem()
            } else {
                Toast.makeText(this, "🎯 All 3 Math Missions Solved! Awake and energized!", Toast.LENGTH_LONG).show()
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

        binding.btnEmergencyStop.visibility = if (isTest) View.VISIBLE else View.GONE
        binding.btnEmergencyStop.setOnClickListener {
            stopAudioAndVibration()
            Toast.makeText(this, "🛑 Test alarm stopped.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun snoozeAlarm() {
        stopAudioAndVibration()
        AlarmManagerHelper.scheduleSnooze(this, alarm, alarm.snoozeMinutes)
        Toast.makeText(this, "💤 Alarm snoozed for ${alarm.snoozeMinutes} minutes.", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun dismissAlarmSuccessfully() {
        stopAudioAndVibration()

        if (!isTest) {
            if (alarm.deleteAfterRinging) {
                AlarmManagerHelper.deleteAlarm(this, alarm.id)
                Toast.makeText(this, "🗑️ Alarm deleted after ringing as configured!", Toast.LENGTH_LONG).show()
            } else if (alarm.repeatDays.isNotEmpty()) {
                // Schedule next occurrence on next repeat day
                AlarmManagerHelper.scheduleAlarm(this, alarm)
                Toast.makeText(this, "✅ Alarm dismissed. Next repeat scheduled.", Toast.LENGTH_SHORT).show()
            } else {
                // One-time alarm: mark disabled
                alarm.enabled = false
                AlarmManagerHelper.saveAlarm(this, alarm)
                Toast.makeText(this, "✅ Alarm dismissed.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "✅ Test completed successfully!", Toast.LENGTH_SHORT).show()
        }

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

    private fun stopAudioAndVibration() {
        HardAlarmSoundEngine.stopPlaying(this)
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(clockRunnable)
        pulseAnimator?.cancel()
        stopAudioAndVibration()
    }
}
