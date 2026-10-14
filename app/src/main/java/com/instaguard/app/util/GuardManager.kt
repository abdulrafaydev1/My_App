package com.instaguard.app.util

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.text.TextUtils
import com.instaguard.app.data.GuardPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Core business logic and state machine for InstaGuard.
 * Manages active session usage, cooldown triggers, auto-reset, and formatting.
 */
class GuardManager(private val context: Context) {

    private val prefs = GuardPreferences(context)

    /**
     * Checks if Instagram is currently in an active 3-hour cooldown lock.
     * Automatically handles cooldown expiration and session reset.
     */
    @Synchronized
    fun isCooldownActive(): Boolean {
        val unlockTime = prefs.unlockTimestamp
        if (unlockTime == 0L) {
            return false
        }

        val now = System.currentTimeMillis()
        if (now >= unlockTime) {
            // 3-Hour Cooldown has expired: automatically reset session
            prefs.resetSession()
            return false
        }

        return true
    }

    /**
     * Returns remaining milliseconds in the 3-hour cooldown period.
     * Returns 0L if not locked.
     */
    fun getRemainingCooldownMs(): Long {
        if (!isCooldownActive()) return 0L
        val remaining = prefs.unlockTimestamp - System.currentTimeMillis()
        return if (remaining > 0L) remaining else 0L
    }

    /**
     * Calculates the total active foreground usage time of Instagram
     * in the current tracking session (in milliseconds).
     */
    @Synchronized
    fun getCurrentSessionUsageMs(): Long {
        if (isCooldownActive()) {
            return GuardPreferences.USAGE_LIMIT_MS
        }

        var total = prefs.accumulatedUsageMs
        if (prefs.isInstagramForeground && prefs.lastForegroundStart > 0L) {
            val currentChunk = System.currentTimeMillis() - prefs.lastForegroundStart
            if (currentChunk > 0L) {
                total += currentChunk
            }
        }
        return total.coerceAtMost(GuardPreferences.USAGE_LIMIT_MS)
    }

    /**
     * Returns remaining Instagram allowance in current session (in milliseconds).
     */
    fun getRemainingAllowanceMs(): Long {
        if (isCooldownActive()) return 0L
        val used = getCurrentSessionUsageMs()
        val remaining = GuardPreferences.USAGE_LIMIT_MS - used
        return if (remaining > 0L) remaining else 0L
    }

    /**
     * Called when Instagram enters the foreground.
     * Returns true if Instagram is blocked (due to active cooldown or limit reached).
     */
    @Synchronized
    fun onInstagramForeground(): Boolean {
        if (isCooldownActive()) {
            return true
        }

        val now = System.currentTimeMillis()

        if (prefs.sessionStartTime == 0L) {
            prefs.sessionStartTime = now
        }

        prefs.lastForegroundStart = now
        prefs.isInstagramForeground = true

        // Verify if limit was already reached
        if (getCurrentSessionUsageMs() >= GuardPreferences.USAGE_LIMIT_MS) {
            triggerCooldown()
            return true
        }

        return false
    }

    /**
     * Called when Instagram leaves the foreground.
     */
    @Synchronized
    fun onInstagramBackground() {
        if (prefs.isInstagramForeground) {
            val now = System.currentTimeMillis()
            val chunk = now - prefs.lastForegroundStart
            if (chunk > 0L) {
                prefs.accumulatedUsageMs += chunk
            }
            prefs.isInstagramForeground = false
            prefs.lastForegroundStart = 0L

            if (prefs.accumulatedUsageMs >= GuardPreferences.USAGE_LIMIT_MS) {
                triggerCooldown()
            }
        }
    }

    /**
     * Periodic check while Instagram is open in foreground.
     * Triggers cooldown immediately if 1-hour limit is reached.
     */
    @Synchronized
    fun checkForegroundProgress(): Boolean {
        if (isCooldownActive()) {
            return true
        }

        if (prefs.isInstagramForeground) {
            val total = getCurrentSessionUsageMs()
            if (total >= GuardPreferences.USAGE_LIMIT_MS) {
                triggerCooldown()
                return true
            }
        }
        return false
    }

    /**
     * Enforces the 3-hour cooldown lock period.
     */
    @Synchronized
    fun triggerCooldown() {
        val now = System.currentTimeMillis()
        prefs.unlockTimestamp = now + GuardPreferences.COOLDOWN_DURATION_MS
        prefs.accumulatedUsageMs = GuardPreferences.USAGE_LIMIT_MS
        prefs.isInstagramForeground = false
        prefs.lastForegroundStart = 0L
    }

    /**
     * Manually resets the session counter.
     */
    @Synchronized
    fun resetSession() {
        prefs.resetSession()
    }

    /**
     * Formats milliseconds into a clean string (e.g., "42m" or "1h 00m").
     */
    fun formatMinutesSeconds(ms: Long): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%dh %02dm", hours, minutes)
        } else {
            String.format(Locale.getDefault(), "%02dm", minutes)
        }
    }

    /**
     * Formats milliseconds into a live countdown timer "HH:MM:SS".
     */
    fun formatCountdown(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * Formats an epoch timestamp into standard time of day (e.g. "06:45 PM").
     */
    fun formatTimeOfDay(epochMs: Long): String {
        val sdf = SimpleDateFormat("hh:mm a", Locale.getDefault())
        return sdf.format(Date(epochMs))
    }

    companion object {
        /**
         * Checks whether the specified AccessibilityService is enabled in Android Settings.
         */
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
