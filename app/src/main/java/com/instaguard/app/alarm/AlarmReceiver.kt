package com.instaguard.app.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager

/**
 * BroadcastReceiver triggered by AlarmManager or system BOOT_COMPLETED.
 * Immediately acquires WakeLock and launches the full-screen AlarmRingingActivity.
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

            // Acquire short-lived WakeLock to ensure screen turns on and activity starts
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = pm?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "InstaGuard:AlarmWakeLock"
            )
            wakeLock?.acquire(10_000L)

            val ringingIntent = Intent(context, AlarmRingingActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION
                putExtra(AlarmManagerHelper.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmManagerHelper.EXTRA_IS_TEST, isTest)
                putExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, isSnooze)
            }

            try {
                context.startActivity(ringingIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                try {
                    if (wakeLock?.isHeld == true) {
                        wakeLock.release()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
