package com.instaguard.app.service

import android.accessibilityservice.AccessibilityService
import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
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
import com.instaguard.app.util.UsageStatsHelper
import java.util.concurrent.ConcurrentHashMap

/**
 * High-performance AccessibilityService that monitors Instagram, YouTube, Facebook,
 * and TikTok independently in real-time with millisecond precision.
 * v4.2 Features:
 * - Strict 1-hour usage limit and hardened 5-hour cooldown lock per app.
 * - Layer 1: OS-level UsageEvents reconstruction (survives Uninstall/Reinstall & Clear Data).
 * - Layer 2: Anti-Uninstall, Anti-Force-Stop, and Anti-Clear-Data Tamper Shield.
 * - 3-Layer YouTube/Shorts Picture-in-Picture (PiP) Killer Logic.
 */
class AppBlockerService : AccessibilityService() {

    private lateinit var prefs: GuardPreferences
    private val enforcementHandler = Handler(Looper.getMainLooper())
    private var isLoopRunning = false
    private var checkpointTickCounter = 0
    private var lastTamperBlockMs = 0L

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
        syncAndReconstructFromSystemEvents(applicationContext)
        if (isAnyAppLocked()) {
            startEnforcementLoop()
        }
        Log.i(TAG, "AppBlockerService created; per-app state loaded & reconstructed.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        appContext = applicationContext
        prefs = GuardPreferences(applicationContext)
        syncFromPrefs(applicationContext)
        syncAndReconstructFromSystemEvents(applicationContext)
        if (isAnyAppLocked()) {
            startEnforcementLoop()
        }
        Log.i(TAG, "AppBlockerService connected; per-app state verified & reconstructed.")
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

        val packageName = event.packageName?.toString() ?: ""

        // LAYER 2: Anti-Uninstall, Anti-Force-Stop & Anti-Clear-Data Shield
        if (packageName in SYSTEM_TAMPER_PACKAGES) {
            if (isAnyAppLocked() || hasAnyActiveSession()) {
                if (inspectAndBlockTamperAttempt(packageName)) {
                    return
                }
            }
        }

        // Only process foreground app transitions on TYPE_WINDOW_STATE_CHANGED
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        if (isAnyAppLocked()) {
            killPipWindowsIfAny()
        }

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

    /**
     * LAYER 2: Inspects Android Settings / PackageInstaller / SecurityCenter windows
     * for attempts to Uninstall, Force Stop, or Clear Data/Storage of InstaGuard
     * while any app is locked or has active session usage.
     * Explicitly allows Accessibility Settings and Usage Access permission screens.
     */
    private fun inspectAndBlockTamperAttempt(packageName: String): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastTamperBlockMs < 600L) {
            return true
        }

        val root = try {
            rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return false

        val screenTextBuilder = StringBuilder()
        collectWindowTextRecursively(root, screenTextBuilder)
        val fullText = screenTextBuilder.toString().lowercase()

        // Must reference our app ("instaguard")
        if (!fullText.contains("instaguard")) {
            return false
        }

        val isPackageInstaller = packageName.contains("packageinstaller")

        // IMPORTANT EXCEPTION: Do NOT block when the user is on Accessibility Settings,
        // Usage Access, or Display Over Other Apps screens enabling permissions!
        val isPermissionScreen = fullText.contains("accessibility") ||
            fullText.contains("usage access") ||
            fullText.contains("usage data") ||
            fullText.contains("display over other apps") ||
            fullText.contains("appear on top")

        val isExplicitUninstallDialog = isPackageInstaller ||
            fullText.contains("do you want to uninstall") ||
            fullText.contains("delete app")

        if (isPermissionScreen && !isExplicitUninstallDialog) {
            return false
        }

        val hasTamperKeyword = fullText.contains("uninstall") ||
            fullText.contains("force stop") ||
            fullText.contains("storage & cache") ||
            fullText.contains("clear storage") ||
            fullText.contains("clear data") ||
            fullText.contains("do you want to uninstall") ||
            fullText.contains("delete app")

        if (hasTamperKeyword) {
            lastTamperBlockMs = now
            Log.w(TAG, "Tamper attempt detected in $packageName! Blocking immediately.")
            commitPreviousForegroundApp(now)

            // 1. Immediately navigate Back and Home out of Settings / PackageInstaller
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)

            // 2. Find primary locked or active app to show on LockScreenActivity with Tamper Warning
            val targetKey = APP_KEYS.firstOrNull { isAppLocked(it, applicationContext) }
                ?: APP_KEYS.firstOrNull { getLiveUsageMs(it, applicationContext) > 0L }
                ?: "INSTAGRAM"
            val targetApp = TrackedApp.fromKey(targetKey) ?: TrackedApp.INSTAGRAM

            val lockIntent = Intent(applicationContext, LockScreenActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(LockScreenActivity.EXTRA_APP_KEY, targetApp.key)
                putExtra(LockScreenActivity.EXTRA_APP_NAME, targetApp.displayName)
                putExtra("EXTRA_APP_KEY", targetApp.key)
                putExtra("EXTRA_APP_NAME", targetApp.displayName)
                putExtra(LockScreenActivity.EXTRA_TAMPER_WARNING, TAMPER_WARNING_MESSAGE)
            }
            startActivity(lockIntent)
            return true
        }

        return false
    }

    private fun collectWindowTextRecursively(node: AccessibilityNodeInfo?, out: StringBuilder) {
        if (node == null) return
        node.text?.let {
            if (it.isNotEmpty()) {
                out.append(it).append(' ')
            }
        }
        node.contentDescription?.let {
            if (it.isNotEmpty()) {
                out.append(it).append(' ')
            }
        }
        val count = node.childCount
        for (i in 0 until count) {
            collectWindowTextRecursively(node.getChild(i), out)
        }
    }

    private fun handleTrackedAppWindow(detectedAppKey: String, app: TrackedApp) {
        val now = System.currentTimeMillis()

        // Ensure state is reconstructed if preferences were cleared
        if (isStateEmptyInMemory()) {
            syncAndReconstructFromSystemEvents(applicationContext)
        }

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

    private fun hasAnyActiveSession(): Boolean {
        return APP_KEYS.any { getLiveUsageMs(it, applicationContext) > 0L }
    }

    private fun isStateEmptyInMemory(): Boolean {
        return APP_KEYS.all { (accumulatedUsageMap[it] ?: 0L) == 0L && (unlockTimestampMap[it] ?: 0L) == 0L }
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
        const val TAMPER_WARNING_MESSAGE =
            "Tamper Protection: Cannot Uninstall or Reset InstaGuard while Focus Timer is active!"

        val APP_KEYS = listOf("INSTAGRAM", "YOUTUBE", "FACEBOOK", "TIKTOK")

        private val SYSTEM_TAMPER_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.miui.packageinstaller",
            "com.miui.securitycenter",
            "com.transsion.phonemanager",
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.vivo.permissionmanager"
        )

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
            var allZero = true
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
                    if (usage > 0L || unlock > 0L) {
                        allZero = false
                    }
                }
            }
            if (allZero) {
                syncAndReconstructFromSystemEvents(context)
            }
        }

        /**
         * LAYER 1: OS-Level Usage & Cooldown Reconstruction via UsageStatsManager.queryEvents.
         * Reconstructs exact foreground usage and 5-hour cooldown lock timestamps across the
         * past 6 hours (1h allowance + 5h cooldown) even after app uninstall/reinstall or Clear Data.
         */
        fun syncAndReconstructFromSystemEvents(context: Context) {
            try {
                appContext = context.applicationContext
                if (!UsageStatsHelper.hasUsageStatsPermission(context)) {
                    return
                }

                val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                    ?: return

                val prefs = GuardPreferences(context)
                val now = System.currentTimeMillis()
                val windowStart = now - 6 * 3_600_000L // Last 6 hours (1h limit + 5h cooldown)

                val limitMs = prefs.getUsageLimitMs() // 3_600_000L in Normal Mode
                val cooldownMs = prefs.getCooldownDurationMs() // 18_000_000L in Normal Mode

                val reconstructedUsage = HashMap<String, Long>()
                val reconstructedUnlockAt = HashMap<String, Long>()
                val lastResumeMap = HashMap<String, Long>()

                for (key in APP_KEYS) {
                    reconstructedUsage[key] = 0L
                    reconstructedUnlockAt[key] = 0L
                    lastResumeMap[key] = 0L
                }

                val usageEvents = usageStatsManager.queryEvents(windowStart, now)
                val event = UsageEvents.Event()

                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    val pkg = event.packageName ?: continue
                    val trackedApp = TrackedApp.fromPackageName(pkg) ?: continue
                    val appKey = trackedApp.key
                    val eventTime = event.timeStamp

                    // Check if a previously triggered cooldown expired before or at this event
                    val activeUnlock = reconstructedUnlockAt[appKey] ?: 0L
                    if (activeUnlock in 1..eventTime) {
                        reconstructedUnlockAt[appKey] = 0L
                        reconstructedUsage[appKey] = 0L
                        lastResumeMap[appKey] = 0L
                    }

                    // Ignore any brief opens while the app is inside its cooldown window
                    if ((reconstructedUnlockAt[appKey] ?: 0L) > eventTime) {
                        lastResumeMap[appKey] = 0L
                        continue
                    }

                    when (event.eventType) {
                        UsageEvents.Event.ACTIVITY_RESUMED -> {
                            val prevResume = lastResumeMap[appKey] ?: 0L
                            if (prevResume > 0L && eventTime > prevResume) {
                                val delta = eventTime - prevResume
                                val prevUsage = reconstructedUsage[appKey] ?: 0L
                                val newUsage = prevUsage + delta
                                if (newUsage >= limitMs) {
                                    val needed = maxOf(0L, limitMs - prevUsage)
                                    val lockTriggeredAtMs = prevResume + needed
                                    val unlockAt = lockTriggeredAtMs + cooldownMs
                                    if (unlockAt > eventTime) {
                                        reconstructedUnlockAt[appKey] = unlockAt
                                        reconstructedUsage[appKey] = 0L
                                        lastResumeMap[appKey] = 0L
                                    } else {
                                        // Cooldown already expired before eventTime
                                        reconstructedUnlockAt[appKey] = 0L
                                        reconstructedUsage[appKey] = 0L
                                        lastResumeMap[appKey] = eventTime
                                    }
                                } else {
                                    reconstructedUsage[appKey] = newUsage
                                    lastResumeMap[appKey] = eventTime
                                }
                            } else {
                                lastResumeMap[appKey] = eventTime
                            }
                        }
                        UsageEvents.Event.ACTIVITY_PAUSED,
                        UsageEvents.Event.ACTIVITY_STOPPED -> {
                            val prevResume = lastResumeMap[appKey] ?: 0L
                            if (prevResume > 0L && eventTime >= prevResume) {
                                val delta = eventTime - prevResume
                                lastResumeMap[appKey] = 0L
                                val prevUsage = reconstructedUsage[appKey] ?: 0L
                                val newUsage = prevUsage + delta
                                if (newUsage >= limitMs) {
                                    val needed = maxOf(0L, limitMs - prevUsage)
                                    val lockTriggeredAtMs = prevResume + needed
                                    reconstructedUnlockAt[appKey] = lockTriggeredAtMs + cooldownMs
                                    reconstructedUsage[appKey] = 0L
                                } else {
                                    reconstructedUsage[appKey] = newUsage
                                }
                            }
                        }
                    }
                }

                // Account for any app still in foreground at `now` and finalize state
                for (appKey in APP_KEYS) {
                    val activeUnlock = reconstructedUnlockAt[appKey] ?: 0L
                    if (activeUnlock in 1..now) {
                        reconstructedUnlockAt[appKey] = 0L
                        reconstructedUsage[appKey] = 0L
                    }

                    val lastResume = lastResumeMap[appKey] ?: 0L
                    if ((reconstructedUnlockAt[appKey] ?: 0L) == 0L && lastResume > 0L && now > lastResume) {
                        val delta = now - lastResume
                        val prevUsage = reconstructedUsage[appKey] ?: 0L
                        val newUsage = prevUsage + delta
                        if (newUsage >= limitMs) {
                            val needed = maxOf(0L, limitMs - prevUsage)
                            val lockTriggeredAtMs = lastResume + needed
                            val unlockAt = lockTriggeredAtMs + cooldownMs
                            if (unlockAt > now) {
                                reconstructedUnlockAt[appKey] = unlockAt
                                reconstructedUsage[appKey] = 0L
                            } else {
                                reconstructedUnlockAt[appKey] = 0L
                                reconstructedUsage[appKey] = 0L
                            }
                        } else {
                            reconstructedUsage[appKey] = newUsage
                        }
                    }

                    val recUnlock = reconstructedUnlockAt[appKey] ?: 0L
                    val recUsage = reconstructedUsage[appKey] ?: 0L
                    val currentUnlock = unlockTimestampMap[appKey] ?: prefs.getUnlockTimestamp(appKey)
                    val currentUsage = accumulatedUsageMap[appKey] ?: prefs.getAccumulatedUsage(appKey)

                    if (recUnlock > now && recUnlock > currentUnlock) {
                        unlockTimestampMap[appKey] = recUnlock
                        accumulatedUsageMap[appKey] = 0L
                        prefs.setUnlockTimestamp(appKey, recUnlock)
                        prefs.setAccumulatedUsage(appKey, 0L)
                        Log.w(TAG, "[$appKey] Reconstructed active 5h cooldown from OS UsageEvents: unlocks at $recUnlock")
                    } else if (currentUnlock <= now && recUsage > currentUsage) {
                        accumulatedUsageMap[appKey] = recUsage
                        prefs.setAccumulatedUsage(appKey, recUsage)
                        Log.i(TAG, "[$appKey] Reconstructed usage from OS UsageEvents: $recUsage ms")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
