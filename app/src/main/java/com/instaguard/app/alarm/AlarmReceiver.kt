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
            val alarmId = intent.getStringExtra(AlarmManagerHelper.EXTRA_ALARM_ID) ?: ""
            val isTest = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_TEST, false)
            val isSnooze = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, false)
            val deleteAfter = intent.getBooleanExtra("delete_after_ringing", false)

            // Acquire 60-second full wake lock to turn on screen immediately
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            try {
                @Suppress("DEPRECATION")
                val wakeLock = pm?.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "InstaGuard:AlarmReceiverWakeLock"
                )
                wakeLock?.acquire(60_000L)
            } catch (e: Exception) {
                val wakeLock = pm?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "InstaGuard:AlarmReceiverPartialWakeLock"
                )
                wakeLock?.acquire(60_000L)
            }

            // Immediately start dedicated foreground AlarmRingingService
            AlarmRingingService.startAlarm(context, alarmId, isTest, isSnooze, deleteAfter)
        }
    }
}
