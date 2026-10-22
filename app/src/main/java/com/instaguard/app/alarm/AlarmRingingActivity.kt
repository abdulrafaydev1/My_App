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
 * Full-Screen Alarm Ringing Activity (v14.6 Clean Direct-Dismiss Edition).
 * Displays live digital clock, alarm label, sound title,
 * Snooze button (when snoozeMinutes > 0), and a clean "🛑 Stop / Dismiss Alarm" button.
 */
class AlarmRingingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAlarmRingingBinding
    private lateinit var alarm: SmartAlarm
    private var isTest = false
    private var isSnooze = false

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
                Toast.makeText(this@AlarmRingingActivity, "🚫 Tap 'Stop / Dismiss Alarm' to stop ringing!", Toast.LENGTH_SHORT).show()
            }
        })

        val alarmId = intent.getStringExtra(AlarmManagerHelper.EXTRA_ALARM_ID) ?: ""
        isTest = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_TEST, false)
        isSnooze = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, false)

        alarm = AlarmManagerHelper.getAlarmById(this, alarmId) ?: SmartAlarm(
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
            deleteAfter = alarm.deleteAfterRinging
        )

        bindAlarmDataToUi()
        setupDismissAndSnoozeControls()
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

    private fun setupDismissAndSnoozeControls() {
        // Direct Stop / Dismiss Alarm Button
        binding.btnStopAlarm.setOnClickListener {
            dismissAlarmSuccessfully()
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

        binding.btnEmergencyStop.visibility = View.GONE
    }

    private fun snoozeAlarm() {
        AppBlockerService.clearAlarmRingingState(this)
        // Stop dedicated foreground service
        AlarmRingingService.stopAlarmAfterSolved(this, alarm.id)

        // Schedule snooze trigger
        AlarmManagerHelper.scheduleSnooze(this, alarm, alarm.snoozeMinutes)
        Toast.makeText(this, "💤 Alarm snoozed for ${alarm.snoozeMinutes} minutes.", Toast.LENGTH_LONG).show()
        finishAndRemoveTask()
    }

    private fun dismissAlarmSuccessfully() {
        AppBlockerService.clearAlarmRingingState(this)
        // Stop dedicated foreground service and handle post-dismissal repeat/auto-delete logic
        AlarmRingingService.stopAlarmAfterSolved(this, alarm.id)

        Toast.makeText(this, "✅ Alarm dismissed successfully!", Toast.LENGTH_SHORT).show()
        finishAndRemoveTask()
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
