package com.instaguard.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Thread-safe wrapper for SharedPreferences to persist session usage,
 * cooldown timestamps, and lock status across device reboots.
 */
class GuardPreferences(context: Context) {

    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "instaguard_preferences"

        private const val KEY_UNLOCK_TIMESTAMP = "unlock_timestamp"
        private const val KEY_ACCUMULATED_USAGE_MS = "accumulated_usage_ms"
        private const val KEY_SESSION_START_TIME = "session_start_time"
        private const val KEY_LAST_FOREGROUND_START = "last_foreground_start"
        private const val KEY_IS_INSTAGRAM_FOREGROUND = "is_instagram_foreground"

        const val TARGET_PACKAGE = "com.instagram.android"
        const val USAGE_LIMIT_MS = 60 * 60 * 1000L // 1 Hour (3,600,000 ms)
        const val COOLDOWN_DURATION_MS = 3 * 60 * 60 * 1000L // 3 Hours (10,800,000 ms)
    }

    /**
     * Timestamp (in epoch ms) when the 3-hour cooldown expires.
     * Returns 0L if no cooldown is active.
     */
    var unlockTimestamp: Long
        get() = prefs.getLong(KEY_UNLOCK_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_UNLOCK_TIMESTAMP, value).apply()

    /**
     * Accumulated active foreground time of Instagram in the current session (in ms).
     */
    var accumulatedUsageMs: Long
        get() = prefs.getLong(KEY_ACCUMULATED_USAGE_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_ACCUMULATED_USAGE_MS, value).apply()

    /**
     * Timestamp when the current 1-hour session started.
     */
    var sessionStartTime: Long
        get() = prefs.getLong(KEY_SESSION_START_TIME, 0L)
        set(value) = prefs.edit().putLong(KEY_SESSION_START_TIME, value).apply()

    /**
     * Timestamp when Instagram was brought to foreground.
     */
    var lastForegroundStart: Long
        get() = prefs.getLong(KEY_LAST_FOREGROUND_START, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_FOREGROUND_START, value).apply()

    /**
     * Whether Instagram is currently actively in the foreground.
     */
    var isInstagramForeground: Boolean
        get() = prefs.getBoolean(KEY_IS_INSTAGRAM_FOREGROUND, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_INSTAGRAM_FOREGROUND, value).apply()

    /**
     * Resets all session usage and cooldown states.
     */
    fun resetSession() {
        prefs.edit()
            .putLong(KEY_UNLOCK_TIMESTAMP, 0L)
            .putLong(KEY_ACCUMULATED_USAGE_MS, 0L)
            .putLong(KEY_SESSION_START_TIME, 0L)
            .putLong(KEY_LAST_FOREGROUND_START, 0L)
            .putBoolean(KEY_IS_INSTAGRAM_FOREGROUND, false)
            .apply()
    }
}
