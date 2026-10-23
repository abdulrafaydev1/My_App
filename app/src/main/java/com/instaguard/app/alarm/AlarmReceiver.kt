package com.instaguard.app.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.instaguard.app.service.AlarmRingingService
import com.instaguard.app.service.AppBlockerService

/**
 * BroadcastReceiver triggered by AlarmManager or system BOOT_COMPLETED.
 * Immediately acquires atomic static WakeLock and launches dedicated foreground AlarmRingingService.
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_TRIGGER_ALARM = "com.instaguard.app.ACTION_TRIGGER_ALARM"
        private var handoffWakeLock: PowerManager.WakeLock? = null
        private val lock = Any()

        fun acquireHandoffWakeLock(context: Context) {
            synchronized(lock) {
                if (handoffWakeLock == null) {
                    val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                    handoffWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "InstaGuard:AlarmBroadcastHandoffWakeLock"
                    ).apply {
                        setReferenceCounted(false)
                    }
                }
                handoffWakeLock?.acquire(60_000L)
            }
        }

        fun releaseHandoffWakeLock() {
            synchronized(lock) {
                try {
                    if (handoffWakeLock?.isHeld == true) {
                        handoffWakeLock?.release()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                AlarmManagerHelper.rescheduleAllEnabledAlarms(context)
                AppBlockerService.triggerAlarmWakeLockUpdate(context)
            }

            ACTION_TRIGGER_ALARM -> {
                // 1. Hold CPU awake BEFORE onReceive() returns so the OS cannot suspend
                //    the CPU between broadcast completion and Service.onStartCommand()!
                acquireHandoffWakeLock(context)

                // 2. Nudge the screen controller awake for lock-screen presentation
                try {
                    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    @Suppress("DEPRECATION")
                    val screenWake = pm?.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP or
                            PowerManager.ON_AFTER_RELEASE,
                        "InstaGuard:AlarmScreenWake"
                    )
                    screenWake?.acquire(10_000L)
                } catch (_: Exception) {}

                // 3. Fallback lookup from AlarmManagerHelper if any Intent extra is missing
                val alarmId = intent.getStringExtra(AlarmManagerHelper.EXTRA_ALARM_ID)
                    ?: intent.getStringExtra("alarm_id") ?: ""
                val savedAlarm = if (alarmId.isNotBlank()) AlarmManagerHelper.getAlarmById(context, alarmId) else null

                // 4. Start the Foreground Service that immediately plays the alarm sound
                val serviceIntent = Intent(context, AlarmRingingService::class.java).apply {
                    this.action = AlarmRingingService.ACTION_START_ALARM
                    putExtras(intent)
                    if (savedAlarm != null) {
                        if (!hasExtra(AlarmManagerHelper.EXTRA_ALARM_ID)) putExtra(AlarmManagerHelper.EXTRA_ALARM_ID, savedAlarm.id)
                        if (!hasExtra("alarm_id")) putExtra("alarm_id", savedAlarm.id)
                        if (!hasExtra("alarm_label")) putExtra("alarm_label", savedAlarm.label)
                        if (!hasExtra("sound_id")) putExtra("sound_id", savedAlarm.soundId)
                        if (!hasExtra("snooze_minutes")) putExtra("snooze_minutes", savedAlarm.snoozeMinutes)
                        if (!hasExtra("delete_after_ringing")) putExtra("delete_after_ringing", savedAlarm.deleteAfterRinging)
                        if (!hasExtra("force_max_volume")) putExtra("force_max_volume", savedAlarm.forceMaxVolume)
                        if (!hasExtra("heavy_vibration")) putExtra("heavy_vibration", savedAlarm.heavyVibration)
                        if (!hasExtra("ai_voice")) putExtra("ai_voice", savedAlarm.aiVoiceAnnouncement)
                    }
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}
