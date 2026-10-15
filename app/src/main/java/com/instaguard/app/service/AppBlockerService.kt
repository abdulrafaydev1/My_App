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
 * Strictly isolates usage timers and cooldown locks per app so locking one app never locks others.
 * Defends against Picture-in-Picture (PiP) bypasses via hardware media keys and exclusive audio focus.
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
                val usedMs = getLiveUsageMs(activeKey, applicationContext)
                val limitMs = prefs.getUsageLimitMs()

                if (usedMs >= limitMs) {
                    Log.w(TAG, "[$activeKey] Limit reached ($usedMs ms >= $limitMs ms). Triggering cooldown lock.")
                    val cooldownMs = prefs.getCooldownDurationMs()
                    val unlockTime = now + cooldownMs

                    // Lock ONLY activeKey!
                    unlockTimestampMap[activeKey] = unlockTime
                    accumulatedUsageMap[activeKey] = 0L
                    prefs.setUnlockTimestamp(activeKey, unlockTime)
                    prefs.setAccumulatedUsage(activeKey, 0L)

                    currentForegroundAppKey = null
                    foregroundStartMs = 0L
                    stopEnforcementLoop()

                    val app = TrackedApp.fromKey(activeKey) ?: TrackedApp.INSTAGRAM
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
        appContext = applicationContext
        prefs = GuardPreferences(applicationContext)
        syncFromPrefs(applicationContext)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        val packageName = event.packageName?.toString() ?: ""

        // Ignore transient system overlays (volume bar, notifications, power menu, systemui)
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

        // Ignore our own package so launching LockScreenActivity or opening MainActivity
        // doesn't corrupt another app's lock state, EXCEPT to commit elapsed foreground time
        // of the previously open tracked app.
        if (packageName == applicationContext.packageName) {
            commitPreviousForegroundApp(System.currentTimeMillis())
            stopEnforcementLoop()
            return
        }

        val trackedApp = TrackedApp.fromPackageName(packageName)
        val detectedAppKey = trackedApp?.key

        if (detectedAppKey != null) {
            handleTrackedAppWindow(detectedAppKey, trackedApp)
        } else {
            handleNonTrackedAppWindow()
        }
    }

    private fun handleTrackedAppWindow(detectedAppKey: String, app: TrackedApp) {
        val now = System.currentTimeMillis()

        // 1. Check if ONLY the detected app is locked
        if (isAppLocked(detectedAppKey, applicationContext)) {
            Log.w(TAG, "[${app.displayName}] Opened during active cooldown. Blocking immediately.")
            commitPreviousForegroundApp(now)
            stopEnforcementLoop()
            enforceLockScreen(app)
            return
        }

        // 2. If transitioning from another tracked app, commit its usage first
        if (currentForegroundAppKey != null && currentForegroundAppKey != detectedAppKey) {
            commitPreviousForegroundApp(now)
        }

        // 3. Begin tracking this app if it's newly foregrounded
        if (currentForegroundAppKey != detectedAppKey) {
            currentForegroundAppKey = detectedAppKey
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
                val current = accumulatedUsageMap[prevKey] ?: prefs.getAccumulatedUsage(prevKey)
                val updated = current + delta
                accumulatedUsageMap[prevKey] = updated
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
            putExtra("EXTRA_APP_KEY", app.key)
            putExtra("EXTRA_APP_NAME", app.displayName)
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
        val APP_KEYS = listOf("INSTAGRAM", "YOUTUBE", "FACEBOOK", "TIKTOK")

        val accumulatedUsageMap = ConcurrentHashMap<String, Long>()
        val unlockTimestampMap = ConcurrentHashMap<String, Long>()

        @Volatile
        var currentForegroundAppKey: String? = null

        @Volatile
        var foregroundStartMs: Long = 0L

        @Volatile
        var appContext: Context? = null

        fun syncFromPrefs(context: Context) {
            appContext = context.applicationContext
            val prefs = GuardPreferences(context)
            for (key in APP_KEYS) {
                accumulatedUsageMap[key] = prefs.getAccumulatedUsage(key)
                unlockTimestampMap[key] = prefs.getUnlockTimestamp(key)
            }
        }

        fun getLiveUsageMs(appKey: String, context: Context? = null): Long {
            val now = System.currentTimeMillis()
            val unlockAt = unlockTimestampMap[appKey] ?: 0L

            // If cooldown expired in the past, reset ONLY for this appKey
            if (unlockAt > 0L && unlockAt <= now) {
                unlockTimestampMap[appKey] = 0L
                accumulatedUsageMap[appKey] = 0L
                val ctx = context ?: appContext
                ctx?.let { GuardPreferences(it).resetApp(appKey) }
            }

            val base = accumulatedUsageMap[appKey] ?: 0L
            val activeDelta = if (currentForegroundAppKey == appKey && foregroundStartMs > 0L) {
                now - foregroundStartMs
            } else {
                0L
            }
            return base + activeDelta
        }

        fun getRemainingLockMs(appKey: String, context: Context? = null): Long {
            val unlockAt = unlockTimestampMap[appKey] ?: 0L
            val now = System.currentTimeMillis()
            val remaining = unlockAt - now

            if (unlockAt > 0L && remaining <= 0L) {
                // Cooldown expired: reset ONLY for this appKey
                unlockTimestampMap[appKey] = 0L
                accumulatedUsageMap[appKey] = 0L
                val ctx = context ?: appContext
                ctx?.let { GuardPreferences(it).resetApp(appKey) }
                return 0L
            }

            return maxOf(0L, remaining)
        }

        fun isAppLocked(appKey: String, context: Context? = null): Boolean {
            return getRemainingLockMs(appKey, context) > 0L
        }

        fun unlockApp(appKey: String, context: Context? = null) {
            unlockTimestampMap[appKey] = 0L
            accumulatedUsageMap[appKey] = 0L
            val ctx = context ?: appContext
            ctx?.let { GuardPreferences(it).resetApp(appKey) }
        }

        fun resetAll(context: Context? = null) {
            currentForegroundAppKey = null
            foregroundStartMs = 0L
            for (key in APP_KEYS) {
                accumulatedUsageMap[key] = 0L
                unlockTimestampMap[key] = 0L
            }
            val ctx = context ?: appContext
            ctx?.let { GuardPreferences(it).resetAllApps() }
        }
    }
}
