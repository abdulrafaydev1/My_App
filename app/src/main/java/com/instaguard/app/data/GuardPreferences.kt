package com.instaguard.app.data

import android.content.Context
import android.content.SharedPreferences
import com.instaguard.app.model.TrackedApp

/**
 * Thread-safe wrapper for SharedPreferences to persist per-app real-time usage,
 * cooldown timestamps, and test mode preferences for all 4 social platforms.
 */
class GuardPreferences(context: Context) {

    val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        const val PREFS_NAME = "InstaGuardPrefs"

        const val KEY_IS_TEST_MODE = "is_test_mode"

        // Legacy compatibility keys
        const val KEY_ACCUMULATED_USAGE_MS = "accumulated_usage_ms"
        const val KEY_INSTA_FOREGROUND_START_MS = "insta_foreground_start_ms"
        const val KEY_IS_INSTA_IN_FOREGROUND = "is_insta_in_foreground"
        const val KEY_UNLOCK_TIMESTAMP_MS = "unlock_timestamp_ms"

        // Normal Mode: 1 Hour Limit (3,600,000 ms), 3 Hours Cooldown (10,800,000 ms)
        const val USAGE_LIMIT_MS = 60 * 60 * 1000L // 3,600,000 ms
        const val COOLDOWN_DURATION_MS = 3 * 60 * 60 * 1000L // 10,800,000 ms

        // Test Mode: 1 Minute Limit (60,000 ms), 2 Minutes Cooldown (120,000 ms)
        const val TEST_USAGE_LIMIT_MS = 60 * 1000L // 60,000 ms
        const val TEST_COOLDOWN_DURATION_MS = 2 * 60 * 1000L // 120,000 ms
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
        val key = "usage_${appKey.uppercase()}"
        val value = prefs.getLong(key, -1L)
        if (value >= 0L) return value
        // Fallback to legacy key for Instagram if present
        if (appKey.equals(TrackedApp.INSTAGRAM.key, ignoreCase = true)) {
            return prefs.getLong(KEY_ACCUMULATED_USAGE_MS, 0L)
        }
        return 0L
    }

    /**
     * Persists accumulated foreground usage (in ms) for a specific app key.
     */
    fun setAccumulatedUsage(appKey: String, value: Long) {
        val editor = prefs.edit().putLong("usage_${appKey.uppercase()}", value)
        if (appKey.equals(TrackedApp.INSTAGRAM.key, ignoreCase = true)) {
            editor.putLong(KEY_ACCUMULATED_USAGE_MS, value)
        }
        editor.apply()
    }

    /**
     * Reads cooldown unlock timestamp (epoch ms) for a specific app key.
     */
    fun getUnlockTimestamp(appKey: String): Long {
        val key = "unlock_${appKey.uppercase()}"
        val value = prefs.getLong(key, -1L)
        if (value >= 0L) return value
        // Fallback to legacy key for Instagram if present
        if (appKey.equals(TrackedApp.INSTAGRAM.key, ignoreCase = true)) {
            return prefs.getLong(KEY_UNLOCK_TIMESTAMP_MS, 0L)
        }
        return 0L
    }

    /**
     * Persists cooldown unlock timestamp (epoch ms) for a specific app key.
     */
    fun setUnlockTimestamp(appKey: String, value: Long) {
        val editor = prefs.edit().putLong("unlock_${appKey.uppercase()}", value)
        if (appKey.equals(TrackedApp.INSTAGRAM.key, ignoreCase = true)) {
            editor.putLong(KEY_UNLOCK_TIMESTAMP_MS, value)
        }
        editor.apply()
    }

    /**
     * Resets usage and cooldown state for an individual app.
     */
    fun resetApp(appKey: String) {
        val editor = prefs.edit()
            .putLong("usage_${appKey.uppercase()}", 0L)
            .putLong("unlock_${appKey.uppercase()}", 0L)
        if (appKey.equals(TrackedApp.INSTAGRAM.key, ignoreCase = true)) {
            editor.putLong(KEY_ACCUMULATED_USAGE_MS, 0L)
            editor.putLong(KEY_INSTA_FOREGROUND_START_MS, 0L)
            editor.putBoolean(KEY_IS_INSTA_IN_FOREGROUND, false)
            editor.putLong(KEY_UNLOCK_TIMESTAMP_MS, 0L)
        }
        editor.apply()
    }

    /**
     * Resets usage and cooldown for all 4 social apps.
     */
    fun resetAllApps() {
        val editor = prefs.edit()
        for (app in TrackedApp.entries) {
            editor.putLong("usage_${app.key}", 0L)
            editor.putLong("unlock_${app.key}", 0L)
        }
        editor.putLong(KEY_ACCUMULATED_USAGE_MS, 0L)
        editor.putLong(KEY_INSTA_FOREGROUND_START_MS, 0L)
        editor.putBoolean(KEY_IS_INSTA_IN_FOREGROUND, false)
        editor.putLong(KEY_UNLOCK_TIMESTAMP_MS, 0L)
        editor.apply()
    }

    // Legacy aliases for backward compatibility
    var accumulatedUsageMs: Long
        get() = getAccumulatedUsage(TrackedApp.INSTAGRAM.key)
        set(value) = setAccumulatedUsage(TrackedApp.INSTAGRAM.key, value)

    var unlockTimestampMs: Long
        get() = getUnlockTimestamp(TrackedApp.INSTAGRAM.key)
        set(value) = setUnlockTimestamp(TrackedApp.INSTAGRAM.key, value)

    var instaForegroundStartMs: Long
        get() = prefs.getLong(KEY_INSTA_FOREGROUND_START_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_INSTA_FOREGROUND_START_MS, value).apply()

    var isInstaInForeground: Boolean
        get() = prefs.getBoolean(KEY_IS_INSTA_IN_FOREGROUND, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_INSTA_IN_FOREGROUND, value).apply()

    fun resetSession() {
        resetAllApps()
    }
}
