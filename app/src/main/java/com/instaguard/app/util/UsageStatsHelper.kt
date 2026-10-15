package com.instaguard.app.util

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.model.TrackedApp
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
     * Queries total foreground time (in milliseconds) for a list of packages
     * between startTime and endTime using UsageEvents for maximum accuracy.
     */
    fun getForegroundUsageTime(
        context: Context,
        targetPackages: List<String>,
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
                if (targetPackages.contains(event.packageName)) {
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
     * Fallback query: Returns foreground time for a tracked app in the last 1 hour.
     */
    fun getAppUsagePastHour(context: Context, app: TrackedApp): Long {
        val now = System.currentTimeMillis()
        val oneHourAgo = now - GuardPreferences.USAGE_LIMIT_MS
        return getForegroundUsageTime(context, app.packageNames, oneHourAgo, now)
    }

    /**
     * Legacy single-package query for backward compatibility.
     */
    fun getInstagramUsagePastHour(context: Context): Long {
        return getAppUsagePastHour(context, TrackedApp.INSTAGRAM)
    }

    /**
     * Queries total foreground usage since midnight of today for a tracked app.
     */
    fun getAppUsageToday(context: Context, app: TrackedApp): Long {
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startOfDay = calendar.timeInMillis
        val now = System.currentTimeMillis()
        return getForegroundUsageTime(context, app.packageNames, startOfDay, now)
    }
}
