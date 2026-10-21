package com.instaguard.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import com.instaguard.app.service.AlarmRingingService
import com.instaguard.app.service.AppBlockerService

/**
 * BroadcastReceiver triggered by AlarmManager or system BOOT_COMPLETED.
 * Immediately acquires WakeLock and launches dedicated foreground AlarmRingingService.
 */
class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return

        if (action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmManagerHelper.rescheduleAllEnabledAlarms(context)
            return
        }

        if (action == AlarmManagerHelper.ACTION_TRIGGER_ALARM) {
            val alarmId = intent.getStringExtra(AlarmManagerHelper.EXTRA_ALARM_ID) ?: intent.getStringExtra("alarm_id") ?: ""
            val isTest = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_TEST, false)
            val isSnooze = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, false)
            val deleteAfter = intent.getBooleanExtra("delete_after_ringing", false)
            val soundId = intent.getIntExtra("sound_id", 2)
            val forceMaxVolume = intent.getBooleanExtra("force_max_volume", true)

            // Acquire 120-second full wake lock & CPU wake lock to turn on screen and CPU immediately
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            try {
                @Suppress("DEPRECATION")
                val screenWakeLock = pm?.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "InstaGuard:AlarmScreenWakeLock"
                )
                screenWakeLock?.acquire(120_000L)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            try {
                val cpuWakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "InstaGuard:AlarmCpuWakeLock"
                )
                cpuWakeLock?.acquire(120_000L)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Immediately start sound without waiting for Activity to open/resume
            HardAlarmSoundEngine.startPlaying(context.applicationContext, soundId, forceMaxVolume = forceMaxVolume)

            // Immediately start dedicated foreground AlarmRingingService
            AlarmRingingService.startAlarm(context, alarmId, isTest, isSnooze, deleteAfter)
        }
    }
}
