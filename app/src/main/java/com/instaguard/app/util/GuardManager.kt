package com.instaguard.app.util

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.model.TrackedApp
import com.instaguard.app.service.AppBlockerService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Business logic and helper methods for InstaGuard v3.0 Multi-App Focus.
 */
class GuardManager(private val context: Context) {

    val prefs = GuardPreferences(context)

    /**
     * Seeds initial usage stats for all 4 apps if not yet tracked.
     */
    @Synchronized
    fun syncWithUsageStatsIfEmpty() {
        for (app in TrackedApp.entries) {
            val appKey = app.key
            val current = prefs.getAccumulatedUsage(appKey)
            val unlock = prefs.getUnlockTimestamp(appKey)
            if (current == 0L && unlock == 0L && AppBlockerService.currentForegroundAppKey != appKey) {
                val pastUsage = UsageStatsHelper.getAppUsagePastHour(context, app)
                if (pastUsage > 0L) {
                    val limit = prefs.getUsageLimitMs()
                    val seeded = pastUsage.coerceAtMost(limit)
                    prefs.setAccumulatedUsage(appKey, seeded)
                    AppBlockerService.accumulatedUsageMs[appKey] = seeded
                }
            }
        }
    }

    /**
     * Formats milliseconds into high-precision HH:MM:SS.mmm format.
     */
    fun formatWithMillis(ms: Long): String {
        val safeMs = maxOf(0L, ms)
        val hours = safeMs / 3_600_000
        val minutes = (safeMs % 3_600_000) / 60_000
        val seconds = (safeMs % 60_000) / 1_000
        val millis = safeMs % 1_000
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis)
    }

    /**
     * Formats an epoch timestamp into standard time of day (e.g. "06:45 PM").
     */
    fun formatTimeOfDay(epochMs: Long): String {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return sdf.format(Date(epochMs))
    }

    /**
     * Resets all app timers and cooldown states.
     */
    @Synchronized
    fun resetSession() {
        AppBlockerService.resetAll(prefs)
    }

    // Legacy helpers for Instagram
    fun isCooldownActive(): Boolean {
        return AppBlockerService.isAppLocked(TrackedApp.INSTAGRAM.key, prefs)
    }

    fun getRemainingCooldownMs(): Long {
        return AppBlockerService.getRemainingCooldownMs(TrackedApp.INSTAGRAM.key, prefs)
    }

    fun getCurrentSessionUsageMs(): Long {
        return AppBlockerService.getLiveUsageMs(TrackedApp.INSTAGRAM.key, prefs)
    }

    fun getRemainingAllowanceMs(): Long {
        val used = getCurrentSessionUsageMs()
        val limit = prefs.getUsageLimitMs()
        return maxOf(0L, limit - used)
    }

    companion object {
        fun isAccessibilityServiceEnabled(
            context: Context,
            serviceClass: Class<out AccessibilityService>
        ): Boolean {
            val expectedComponentName = ComponentName(context, serviceClass)
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val colonSplitter = TextUtils.SimpleStringSplitter(':')
            colonSplitter.setString(enabledServicesSetting)

            while (colonSplitter.hasNext()) {
                val componentNameString = colonSplitter.next()
                val enabledComponent = ComponentName.unflattenFromString(componentNameString)
                if (enabledComponent != null && enabledComponent == expectedComponentName) {
                    return true
                }
            }
            return false
        }
    }
}
