package com.instaguard.app.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.instaguard.app.data.GuardPreferences
import java.util.Calendar

/**
 * Utility to interact with Android's UsageStatsManager and check permissions.
 */
object UsageStatsHelper {

    /**
     * Verifies if the app has been granted PACKAGE_USAGE_STATS permission by the user.
     */
    fun hasUsageStatsPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /**
     * Queries total foreground time (in milliseconds) for a specific package
     * between startTime and endTime using UsageEvents for maximum accuracy.
     */
    fun getForegroundUsageTime(
        context: Context,
        targetPackage: String,
        startTime: Long,
        endTime: Long
    ): Long {
        if (!hasUsageStatsPermission(context) || startTime >= endTime) {
            return 0L
        }

        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return 0L

        var totalForegroundTime = 0L

        try {
            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()

            var lastResumeTime = 0L

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.packageName == targetPackage) {
                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            lastResumeTime = event.timeStamp
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.ACTIVITY_STOPPED -> {
                            if (lastResumeTime > 0L) {
                                totalForegroundTime += (event.timeStamp - lastResumeTime)
                                lastResumeTime = 0L
                            }
                        }
                    }
                }
            }

            // If still in foreground at query time
            if (lastResumeTime > 0L && endTime > lastResumeTime) {
                totalForegroundTime += (endTime - lastResumeTime)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return totalForegroundTime
    }

    /**
     * Fallback query: Returns foreground time for Instagram in the last 1 hour.
     * Used when AccessibilityService was not yet active so the timer is never stuck at zero.
     */
    fun getInstagramUsagePastHour(context: Context, targetPackage: String = GuardPreferences.TARGET_PACKAGE): Long {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - GuardPreferences.USAGE_LIMIT_MS
        return getForegroundUsageTime(context, targetPackage, oneHourAgo, now)
    }

    /**
     * Queries total Instagram foreground usage since midnight of today.
     */
    fun getInstagramUsageToday(context: Context, targetPackage: String = GuardPreferences.TARGET_PACKAGE): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val now = System.currentTimeMillis()
        return getForegroundUsageTime(context, targetPackage, startOfDay, now)
    }
}
