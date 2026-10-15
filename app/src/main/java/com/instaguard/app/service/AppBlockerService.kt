package com.instaguard.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.model.TrackedApp
import com.instaguard.app.ui.LockScreenActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance AccessibilityService that monitors Instagram, YouTube, Facebook,
 * and TikTok independently in real-time with millisecond precision.
 * Prevents YouTube Picture-in-Picture (PiP) bypasses by dispatching hardware media pause/stop keys
 * and seizing exclusive audio focus before launching LockScreenActivity directly over the blocked app.
 */
class AppBlockerService : AccessibilityService() {

    private lateinit var prefs: GuardPreferences
    private val enforcementHandler = Handler(Looper.getMainLooper())
    private var isLoopRunning = false

    private val enforcementRunnable = object : Runnable {
        override fun run() {
            val activeKey = currentForegroundAppKey
            if (activeKey != null) {
                val now = System.currentTimeMillis()
                val start = foregroundStartMs
                val liveDelta = if (start > 0L) now - start else 0L
                val totalMs = (accumulatedUsageMs[activeKey] ?: 0L) + liveDelta
                val limitMs = prefs.getUsageLimitMs()

                if (totalMs >= limitMs) {
                    Log.w(TAG, "[$activeKey] Limit reached ($totalMs ms >= $limitMs ms). Triggering cooldown lock.")
                    val cooldownMs = prefs.getCooldownDurationMs()
                    val unlockTime = now + cooldownMs

                    unlockTimestampMs[activeKey] = unlockTime
                    accumulatedUsageMs[activeKey] = 0L
                    prefs.setUnlockTimestamp(activeKey, unlockTime)
                    prefs.setAccumulatedUsage(activeKey, 0L)

                    currentForegroundAppKey = null
                    foregroundStartMs = 0L
                    stopEnforcementLoop()

                    val app = TrackedApp.fromKey(activeKey) ?: TrackedApp.YOUTUBE
                    enforceLockScreen(app)
                    return
                }

                enforcementHandler.postDelayed(this, 100L)
            } else {
                stopEnforcementLoop()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = GuardPreferences(applicationContext)
        syncFromPreferences(prefs)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: ""
        val className = event.className?.toString() ?: ""

        // Ignore transient system overlays (volume bar, notifications, power menu)
        if (packageName == "android" || packageName == "com.android.systemui") {
            return
        }

        // Ignore virtual keyboard packages
        if (packageName.startsWith("com.google.android.inputmethod") ||
            packageName.contains("keyboard") ||
            packageName.contains("ime")
        ) {
            return
        }

        // Ignore our own LockScreenActivity to prevent self-interception loops
        if (packageName == applicationContext.packageName && className.contains("LockScreenActivity")) {
            return
        }

        val trackedApp = TrackedApp.fromPackageName(packageName)
        if (trackedApp != null) {
            handleTrackedAppWindow(trackedApp)
        } else {
            handleNonTrackedAppWindow()
        }
    }

    private fun handleTrackedAppWindow(app: TrackedApp) {
        val now = System.currentTimeMillis()
        val appKey = app.key

        // 1. Check if the app is currently locked in cooldown
        val unlockTime = unlockTimestampMs[appKey] ?: prefs.getUnlockTimestamp(appKey)
        if (unlockTime > 0L) {
            if (now < unlockTime) {
                Log.w(TAG, "[${app.displayName}] Opened during active cooldown. Blocking immediately.")
                commitPreviousForegroundApp(now)
                enforceLockScreen(app)
                return
            } else {
                // Cooldown has expired, auto-unlock
                unlockTimestampMs[appKey] = 0L
                accumulatedUsageMs[appKey] = 0L
                prefs.resetApp(appKey)
            }
        }

        // 2. If transitioning from another tracked app, commit its usage first
        if (currentForegroundAppKey != null && currentForegroundAppKey != appKey) {
            commitPreviousForegroundApp(now)
        }

        // 3. Begin tracking this app
        if (currentForegroundAppKey != appKey) {
            currentForegroundAppKey = appKey
            foregroundStartMs = now
            startEnforcementLoop()
            Log.d(TAG, "[${app.displayName}] Foreground tracking started at $now")
        }
    }

    private fun handleNonTrackedAppWindow() {
        if (currentForegroundAppKey != null) {
            val now = System.currentTimeMillis()
            commitPreviousForegroundApp(now)
            stopEnforcementLoop()
        }
    }

    private fun commitPreviousForegroundApp(now: Long) {
        val prevKey = currentForegroundAppKey ?: return
        val start = foregroundStartMs
        if (start > 0L) {
            val delta = now - start
            if (delta > 0L) {
                val current = accumulatedUsageMs[prevKey] ?: prefs.getAccumulatedUsage(prevKey)
                val updated = current + delta
                accumulatedUsageMs[prevKey] = updated
                prefs.setAccumulatedUsage(prevKey, updated)
                Log.d(TAG, "[$prevKey] Committed $delta ms. Total accumulated: $updated ms")
            }
        }
        currentForegroundAppKey = null
        foregroundStartMs = 0L
    }

    private fun startEnforcementLoop() {
        if (!isLoopRunning) {
            isLoopRunning = true
            enforcementHandler.removeCallbacks(enforcementRunnable)
            enforcementHandler.post(enforcementRunnable)
        }
    }

    private fun stopEnforcementLoop() {
        isLoopRunning = false
        enforcementHandler.removeCallbacks(enforcementRunnable)
    }

    /**
     * Enforces the focus lock screen for the given tracked app.
     * 1. Pauses all active media playback and seizes exclusive audio focus immediately.
     * 2. Launches LockScreenActivity directly over the blocked app WITHOUT pressing Home first,
     *    which prevents YouTube from shrinking into a Picture-in-Picture floating window.
     */
    private fun enforceLockScreen(app: TrackedApp) {
        // 1. Force-pause media playback and seize audio focus immediately
        stopAllMediaPlayback()

        // 2. Launch LockScreenActivity directly on top of the blocked app
        val lockIntent = Intent(applicationContext, LockScreenActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(LockScreenActivity.EXTRA_APP_KEY, app.key)
            putExtra(LockScreenActivity.EXTRA_APP_NAME, app.displayName)
        }
        startActivity(lockIntent)
    }

    /**
     * Dispatches hardware media pause/stop keys and seizes exclusive audio focus,
     * forcing YouTube/TikTok video players to pause immediately without entering PiP.
     */
    private fun stopAllMediaPlayback() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 1. Dispatch Hardware Media PAUSE and STOP key events to immediately pause YouTube/TikTok video
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))

            // 2. Seize Exclusive Audio Focus so Android OS forces YouTube to pause playback immediately
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener { }
                .build()

            audioManager.requestAudioFocus(focusRequest)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInterrupt() {
        stopEnforcementLoop()
        handleNonTrackedAppWindow()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopEnforcementLoop()
        handleNonTrackedAppWindow()
    }

    companion object {
        private const val TAG = "AppBlockerService"

        val accumulatedUsageMs = ConcurrentHashMap<String, Long>()
        val unlockTimestampMs = ConcurrentHashMap<String, Long>()

        @Volatile
        var currentForegroundAppKey: String? = null

        @Volatile
        var foregroundStartMs: Long = 0L

        fun syncFromPreferences(prefs: GuardPreferences) {
            for (app in TrackedApp.entries) {
                accumulatedUsageMs[app.key] = prefs.getAccumulatedUsage(app.key)
                unlockTimestampMs[app.key] = prefs.getUnlockTimestamp(app.key)
            }
        }

        fun getLiveUsageMs(appKey: String, prefs: GuardPreferences): Long {
            if (isAppLocked(appKey, prefs)) {
                return prefs.getUsageLimitMs()
            }

            var base = accumulatedUsageMs[appKey] ?: prefs.getAccumulatedUsage(appKey)
            if (currentForegroundAppKey == appKey && foregroundStartMs > 0L) {
                val delta = System.currentTimeMillis() - foregroundStartMs
                if (delta > 0L) {
                    base += delta
                }
            }
            return base.coerceAtMost(prefs.getUsageLimitMs())
        }

        fun isAppLocked(appKey: String, prefs: GuardPreferences): Boolean {
            val unlockTime = unlockTimestampMs[appKey] ?: prefs.getUnlockTimestamp(appKey)
            if (unlockTime <= 0L) return false

            val now = System.currentTimeMillis()
            if (now >= unlockTime) {
                unlockTimestampMs[appKey] = 0L
                accumulatedUsageMs[appKey] = 0L
                prefs.resetApp(appKey)
                return false
            }
            return true
        }

        fun getRemainingCooldownMs(appKey: String, prefs: GuardPreferences): Long {
            if (!isAppLocked(appKey, prefs)) return 0L
            val unlockTime = unlockTimestampMs[appKey] ?: prefs.getUnlockTimestamp(appKey)
            return maxOf(0L, unlockTime - System.currentTimeMillis())
        }

        fun unlockApp(appKey: String, prefs: GuardPreferences) {
            unlockTimestampMs[appKey] = 0L
            accumulatedUsageMs[appKey] = 0L
            prefs.resetApp(appKey)
        }

        fun resetAll(prefs: GuardPreferences) {
            currentForegroundAppKey = null
            foregroundStartMs = 0L
            for (app in TrackedApp.entries) {
                accumulatedUsageMs[app.key] = 0L
                unlockTimestampMs[app.key] = 0L
            }
            prefs.resetAllApps()
        }
    }
}
