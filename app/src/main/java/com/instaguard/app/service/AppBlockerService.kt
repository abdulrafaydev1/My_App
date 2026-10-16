package com.instaguard.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
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
import android.view.accessibility.AccessibilityNodeInfo
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.model.TrackedApp
import com.instaguard.app.ui.LockScreenActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance AccessibilityService that monitors Instagram, YouTube, Facebook,
 * and TikTok independently in real-time with millisecond precision.
 * Enforces a strict 1-hour usage limit and a hardened 5-hour cooldown lock per app.
 * Includes auto-checkpoint persistence every 3 seconds to guarantee reboot/crash protection.
 * Defends against Picture-in-Picture (PiP) bypasses via 3-Layer PiP Killer Logic.
 */
class AppBlockerService : AccessibilityService() {

    private lateinit var prefs: GuardPreferences
    private val enforcementHandler = Handler(Looper.getMainLooper())
    private var isLoopRunning = false
    private var checkpointTickCounter = 0

    private val enforcementRunnable = object : Runnable {
        override fun run() {
            val anyLocked = isAnyAppLocked()
            if (anyLocked) {
                killPipWindowsIfAny()
            }

            val activeKey = currentForegroundAppKey
            if (activeKey != null) {
                val now = System.currentTimeMillis()
                val usedMs = getLiveUsageMs(activeKey, applicationContext)
                val limitMs = prefs.getUsageLimitMs()

                if (usedMs >= limitMs) {
                    Log.w(TAG, "[$activeKey] Limit reached ($usedMs ms >= $limitMs ms). Triggering 5-hour cooldown lock.")
                    val cooldownMs = prefs.getCooldownDurationMs()
                    val unlockTime = now + cooldownMs

                    // Lock ONLY activeKey!
                    unlockTimestampMap[activeKey] = unlockTime
                    accumulatedUsageMap[activeKey] = 0L
                    prefs.setUnlockTimestamp(activeKey, unlockTime)
                    prefs.setAccumulatedUsage(activeKey, 0L)

                    currentForegroundAppKey = null
                    foregroundStartMs = 0L
                    checkpointTickCounter = 0

                    val app = TrackedApp.fromKey(activeKey) ?: TrackedApp.INSTAGRAM
                    blockApp(app)

                    // Keep loop running while any app is locked to actively hunt PiP windows
                    enforcementHandler.postDelayed(this, 100L)
                    return
                }

                // Auto-checkpoint persistence: Flush live usage to SharedPreferences every 30 ticks (~3 seconds)
                checkpointTickCounter++
                if (checkpointTickCounter >= 30) {
                    checkpointTickCounter = 0
                    val start = foregroundStartMs
                    if (start > 0L) {
                        val delta = now - start
                        if (delta > 0L) {
                            val current = accumulatedUsageMap[activeKey] ?: prefs.getAccumulatedUsage(activeKey)
                            val updated = current + delta
                            accumulatedUsageMap[activeKey] = updated
                            prefs.setAccumulatedUsage(activeKey, updated)
                            foregroundStartMs = now
                            Log.d(TAG, "[$activeKey] Periodic auto-checkpoint saved: $updated ms")
                        }
                    }
                }

                enforcementHandler.postDelayed(this, 100L)
            } else if (anyLocked) {
                // Keep 100ms enforcement loop alive while any app is locked to hunt & kill PiP windows
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
        if (isAnyAppLocked()) {
            startEnforcementLoop()
        }
        Log.i(TAG, "AppBlockerService created; per-app state loaded.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        appContext = applicationContext
        prefs = GuardPreferences(applicationContext)
        syncFromPrefs(applicationContext)
        if (isAnyAppLocked()) {
            startEnforcementLoop()
        }
        Log.i(TAG, "AppBlockerService connected; per-app state verified.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Immediately inspect and kill PiP windows on any window change while an app is locked
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (isAnyAppLocked()) {
                killPipWindowsIfAny()
                startEnforcementLoop()
            }
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        if (isAnyAppLocked()) {
            killPipWindowsIfAny()
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
            if (isAnyAppLocked()) {
                startEnforcementLoop()
            } else {
                stopEnforcementLoop()
            }
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
            Log.w(TAG, "[${app.displayName}] Opened during active 5h cooldown. Blocking immediately.")
            commitPreviousForegroundApp(now)
            blockApp(app)
            startEnforcementLoop()
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
            checkpointTickCounter = 0
            startEnforcementLoop()
            Log.d(TAG, "[${app.displayName}] Foreground tracking started at $now")
        }
    }

    private fun handleNonTrackedAppWindow() {
        if (currentForegroundAppKey != null) {
            val now = System.currentTimeMillis()
            commitPreviousForegroundApp(now)
        }
        if (isAnyAppLocked()) {
            startEnforcementLoop()
        } else {
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
        checkpointTickCounter = 0
    }

    private fun isAnyAppLocked(): Boolean {
        return APP_KEYS.any { isAppLocked(it, applicationContext) }
    }

    private fun startEnforcementLoop() {
        if (!isLoopRunning) {
            isLoopRunning = true
            checkpointTickCounter = 0
            enforcementHandler.removeCallbacks(enforcementRunnable)
            enforcementHandler.post(enforcementRunnable)
        }
    }

    private fun stopEnforcementLoop() {
        isLoopRunning = false
        checkpointTickCounter = 0
        enforcementHandler.removeCallbacks(enforcementRunnable)
    }

    private fun enforceLockScreen(app: TrackedApp) {
        blockApp(app)
    }

    /**
     * 3-Layer PiP Killer & App Blocker:
     * a) Immediately stops media playback and seizes both transient exclusive & permanent audio focus.
     * b) If blocking YOUTUBE, sends GLOBAL_ACTION_BACK to exit Shorts/fullscreen player and kills background processes.
     * c) Immediately launches LockScreenActivity with NEW_TASK | CLEAR_TOP | SINGLE_TOP.
     * d) Schedules delayed follow-up sweeps at 200ms and 500ms to re-run stopAllMediaPlayback() and killPipWindowsIfAny().
     */
    private fun blockApp(app: TrackedApp) {
        // a) Pause all media playback and seize audio focus immediately
        stopAllMediaPlayback()

        // b & c) If YOUTUBE, exit Shorts/fullscreen player and kill background process
        if (app.key == "YOUTUBE") {
            performGlobalAction(GLOBAL_ACTION_BACK)
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses("com.google.android.youtube")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // d) Immediately launch LockScreenActivity
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

        // Follow-up sweeps at 200ms and 500ms to crush any delayed PiP transition
        Handler(Looper.getMainLooper()).postDelayed({
            stopAllMediaPlayback()
            killPipWindowsIfAny()
        }, 200L)

        Handler(Looper.getMainLooper()).postDelayed({
            stopAllMediaPlayback()
            killPipWindowsIfAny()
        }, 500L)
    }

    /**
     * Dispatches hardware media pause/stop keys and seizes BOTH transient exclusive
     * and permanent audio focus so YouTube/Shorts believes another media player has permanently taken over.
     */
    private fun stopAllMediaPlayback() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 1. Dispatch Hardware Media PAUSE and STOP key events
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_STOP))
            audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_STOP))

            // 2. Seize Exclusive Transient AND Permanent Audio Focus
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()

            val transientFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(transientExclusiveFocusRequest(transientFocusRequest))

            val permanentFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(permanentFocusRequest)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun transientExclusiveFocusRequest(req: AudioFocusRequest): AudioFocusRequest = req

    /**
     * Active PiP Window Hunter:
     * Inspects all interactive windows for Picture-in-Picture mode, pauses media,
     * clicks Close/Dismiss nodes inside the PiP tree, sends GLOBAL_ACTION_BACK,
     * and kills YouTube's background process whenever YOUTUBE is locked.
     */
    private fun killPipWindowsIfAny() {
        try {
            // 1. Check all interactive windows for Picture-in-Picture mode
            val windowList = windows
            if (windowList != null) {
                for (window in windowList) {
                    if (window.isInPictureInPictureMode) {
                        // Pause media immediately
                        stopAllMediaPlayback()
                        // Try clicking Close/Dismiss inside PiP window if exposed
                        val root = window.root
                        if (root != null) {
                            dismissPipNodeRecursively(root)
                        }
                        // Send global back to collapse PiP
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            }
            // 2. If YOUTUBE is currently locked, continuously kill its background process
            if (isAppLocked("YOUTUBE")) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.killBackgroundProcesses("com.google.android.youtube")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dismissPipNodeRecursively(node: AccessibilityNodeInfo?) {
        if (node == null) return
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val text = node.text?.toString()?.lowercase() ?: ""
        if (desc.contains("close") || desc.contains("dismiss") || text.contains("close")) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            dismissPipNodeRecursively(node.getChild(i))
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
            val now = System.currentTimeMillis()
            for (key in APP_KEYS) {
                val usage = prefs.getAccumulatedUsage(key)
                val unlock = prefs.getUnlockTimestamp(key)
                if (unlock > 0L && unlock <= now) {
                    accumulatedUsageMap[key] = 0L
                    unlockTimestampMap[key] = 0L
                    prefs.resetApp(key)
                } else {
                    accumulatedUsageMap[key] = usage
                    unlockTimestampMap[key] = unlock
                }
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
    }
}
