package com.instaguard.app.data

import android.content.Context
import android.content.SharedPreferences
import com.instaguard.app.model.TrackedApp

/**
 * Thread-safe wrapper for SharedPreferences to persist strictly isolated per-app
 * real-time usage, hardened 5-hour cooldown timestamps, daily history archives,
 * Master PIN, and emergency bypass status for Instagram, YouTube, Facebook, and TikTok.
 */
class GuardPreferences(context: Context) {

    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        const val PREFS_NAME = "InstaGuardPrefs"

        const val KEY_IS_TEST_MODE = "is_test_mode"
        const val KEY_LAST_ACTIVE_DATE = "last_active_date"
        const val KEY_MASTER_PIN = "master_pin"
        const val DEFAULT_MASTER_PIN = "1234"

        // Normal Mode: 1 Hour Limit (3,600,000 ms), 5 Hours Cooldown (18,000,000 ms)
        const val USAGE_LIMIT_MS = 60 * 60 * 1000L // 3,600,000 ms (1 hour)
        const val COOLDOWN_DURATION_MS = 5 * 60 * 60 * 1000L // 18,000,000 ms (5 hours)

        // Test Mode: 1 Minute Limit (60,000 ms), 2 Minutes Cooldown (120,000 ms)
        const val TEST_USAGE_LIMIT_MS = 60 * 1000L // 60,000 ms
        const val TEST_COOLDOWN_DURATION_MS = 2 * 60 * 1000L // 120,000 ms

        fun usageKey(appKey: String): String = "usage_ms_${appKey.uppercase()}"
        fun unlockKey(appKey: String): String = "unlock_ms_${appKey.uppercase()}"
        fun dailyTotalKey(dateStr: String, appKey: String): String = "daily_total_ms_${dateStr}_${appKey.uppercase()}"
        fun emergencyKey(appKey: String): String = "emergency_used_date_${appKey.uppercase()}"
    }

    /**
     * Whether Test Mode (1 Min Limit / 2 Min Cooldown) is active.
     */
    var isTestMode: Boolean
        get() = prefs.getBoolean(KEY_IS_TEST_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_TEST_MODE, value).apply()

    fun getUsageLimitMs(): Long {
        return if (isTestMode) TEST_USAGE_LIMIT_MS else USAGE_LIMIT_MS
    }

    fun getCooldownDurationMs(): Long {
        return if (isTestMode) TEST_COOLDOWN_DURATION_MS else COOLDOWN_DURATION_MS
    }

    /**
     * Reads accumulated foreground usage (in ms) for a specific app key.
     */
    fun getAccumulatedUsage(appKey: String): Long {
        return prefs.getLong(usageKey(appKey), 0L)
    }

    /**
     * Persists accumulated foreground usage (in ms) for a specific app key.
     */
    fun setAccumulatedUsage(appKey: String, value: Long) {
        prefs.edit().putLong(usageKey(appKey), maxOf(0L, value)).apply()
    }

    /**
     * Reads cooldown unlock timestamp (epoch ms) for a specific app key.
     */
    fun getUnlockTimestamp(appKey: String): Long {
        return prefs.getLong(unlockKey(appKey), 0L)
    }

    /**
     * Persists cooldown unlock timestamp (epoch ms) for a specific app key.
     */
    fun setUnlockTimestamp(appKey: String, value: Long) {
        prefs.edit().putLong(unlockKey(appKey), maxOf(0L, value)).apply()
    }

    /**
     * Last active date string ("yyyy-MM-dd") to detect midnight 12:00 AM crossovers.
     */
    var lastActiveDate: String
        get() = prefs.getString(KEY_LAST_ACTIVE_DATE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_ACTIVE_DATE, value).apply()

    /**
     * Master PIN for emergency bypass and settings authorization (defaults to "1234").
     */
    var masterPin: String
        get() = prefs.getString(KEY_MASTER_PIN, DEFAULT_MASTER_PIN) ?: DEFAULT_MASTER_PIN
        set(value) = prefs.edit().putString(KEY_MASTER_PIN, value).apply()

    /**
     * Reads daily total usage (in ms) for a specific date and app.
     */
    fun getDailyTotal(dateStr: String, appKey: String): Long {
        return prefs.getLong(dailyTotalKey(dateStr, appKey), 0L)
    }

    /**
     * Persists daily total usage (in ms) for a specific date and app.
     */
    fun setDailyTotal(dateStr: String, appKey: String, value: Long) {
        prefs.edit().putLong(dailyTotalKey(dateStr, appKey), maxOf(0L, value)).apply()
    }

    /**
     * Reads the date string on which emergency bypass was last used for an app.
     */
    fun getEmergencyUsedDate(appKey: String): String {
        return prefs.getString(emergencyKey(appKey), "") ?: ""
    }

    /**
     * Persists the date string on which emergency bypass was used for an app.
     */
    fun setEmergencyUsedDate(appKey: String, dateStr: String) {
        prefs.edit().putString(emergencyKey(appKey), dateStr).apply()
    }

    /**
     * Resets usage and cooldown state for an individual app when cooldown completes naturally.
     */
    fun resetApp(appKey: String) {
        prefs.edit()
            .putLong(usageKey(appKey), 0L)
            .putLong(unlockKey(appKey), 0L)
            .apply()
    }

    /**
     * Resets usage and cooldown for all apps and scrubs legacy keys.
     */
    fun resetAllApps() {
        val editor = prefs.edit()
        for (app in TrackedApp.entries) {
            editor.putLong(usageKey(app.key), 0L)
            editor.putLong(unlockKey(app.key), 0L)
        }
        editor.remove("accumulated_usage_ms")
        editor.remove("insta_foreground_start_ms")
        editor.remove("is_insta_in_foreground")
        editor.remove("unlock_timestamp_ms")
        editor.remove("unlock_time")
        editor.apply()
    }
}
