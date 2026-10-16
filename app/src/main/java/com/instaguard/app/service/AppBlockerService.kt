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
import android.os.PowerManager
import android.os.SystemClock
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
 * v4.3 Features:
 * - Monotonic 50ms Delta Stopwatch Engine (SystemClock.elapsedRealtime) with zero timer freeze/drift.
 * - Strict 1-hour usage limit and hardened 5-hour cooldown lock per app.
 * - Layer 1: OS-level UsageEvents reconstruction when data is empty.
 * - Layer 2: Anti-Uninstall, Anti-Force-Stop, and Anti-Clear-Data Tamper Shield.
 * - 3-Layer YouTube/Shorts Picture-in-Picture (PiP) Killer Logic.
 */
class AppBlockerService : AccessibilityService() {

    private lateinit var prefs: GuardPreferences
    private val tickerHandler = Handler(Looper.getMainLooper())
    private var isTickerRunning = false
    private var tickCounter = 0
    private var lastTamperBlockMs = 0L

    /**
     * Monotonic 50ms Delta-Tick Stopwatch Ticker.
     * Continuously computes elapsed time delta using SystemClock.elapsedRealtime()
     * and adds delta directly to active app's accumulated usage.
     */
    private val tickerRunnable = object : Runnable {
        override fun run() {
            val nowElapsed = SystemClock.elapsedRealtime()
            val deltaMs = if (lastTickElapsedRealtime > 0L) {
                (nowElapsed - lastTickElapsedRealtime).coerceIn(0L, 500L)
            } else {
                0L
            }
            lastTickElapsedRealtime = nowElapsed

            // 1. Verify screen is interactive (ON)
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isInteractive) {
                currentForegroundAppKey = null
                tickerHandler.postDelayed(this, 50L)
                return
            }

            // 2. Active PiP Hunter whenever any app is in 5-hour cooldown lock
            if (isAnyAppLocked()) {
                killPipWindowsIfAny()
            }

            // 3. Fallback active window inspection from rootInActiveWindow
            val rootPkg = try {
                rootInActiveWindow?.packageName?.toString()
            } catch (e: Exception) {
                null
            }
            if (rootPkg != null && !IGNORED_SYSTEM_PACKAGES.contains(rootPkg)) {
                val mapped = mapPackageToAppKey(rootPkg)
                if (mapped != null) {
                    currentForegroundAppKey = mapped
                } else if (rootPkg == applicationContext.packageName) {
                    currentForegroundAppKey = null
                }
            }

            // 4. Increment usage counter for active tracked app
            val activeKey = currentForegroundAppKey
            if (activeKey != null) {
                if (isAppLocked(activeKey, applicationContext)) {
                    currentForegroundAppKey = null
                    stopAllMediaPlayback()
                    launchLockScreen(activeKey)
                } else {
                    val updatedUsage = (accumulatedUsageMap[activeKey] ?: 0L) + deltaMs
                    accumulatedUsageMap[activeKey] = updatedUsage

                    val limitMs = prefs.getUsageLimitMs()
                    if (updatedUsage >= limitMs) {
                        Log.w(TAG, "[$activeKey] Limit reached ($updatedUsage >= $limitMs). Locking for 5 hours.")
                        val cooldownMs = prefs.getCooldownDurationMs()
                        unlockTimestampMap[activeKey] = System.currentTimeMillis() + cooldownMs
                        accumulatedUsageMap[activeKey] = 0L
                        currentForegroundAppKey = null
                        saveStateToPrefs(activeKey)
                        stopAllMediaPlayback()
                        launchLockScreen(activeKey)
                    }
                }
            }

            // 5. Periodic auto-checkpoint persistence every 40 ticks (~2000ms)
            tickCounter++
            if (tickCounter >= 40) {
                tickCounter = 0
                if (activeKey != null) {
                    saveStateToPrefs(activeKey)
                }
            }

            tickerHandler.postDelayed(this, 50L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        prefs = GuardPreferences(applicationContext)
        syncFromPrefs(applicationContext)
        startStopwatchTicker()
        Log.i(TAG, "AppBlockerService created; Monotonic 50ms Stopwatch Engine started.")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        appContext = applicationContext
        prefs = GuardPreferences(applicationContext)
        syncFromPrefs(applicationContext)
        startStopwatchTicker()
        Log.i(TAG, "AppBlockerService connected; Monotonic 50ms Stopwatch Engine running.")
    }

    private fun startStopwatchTicker() {
        if (!isTickerRunning) {
            isTickerRunning = true
            lastTickElapsedRealtime = SystemClock.elapsedRealtime()
            tickerHandler.removeCallbacks(tickerRunnable)
            tickerHandler.post(tickerRunnable)
        }
    }

    private fun stopStopwatchTicker() {
        isTickerRunning = false
        tickerHandler.removeCallbacks(tickerRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Interactive window changes -> PiP hunt
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            if (isAnyAppLocked()) {
                killPipWindowsIfAny()
            }
            return
        }

        val packageName = event.packageName?.toString() ?: ""

        // 2. LAYER 2: Anti-Uninstall / Anti-Force-Stop / Anti-Clear-Data Shield
        // Runs on both TYPE_WINDOW_STATE_CHANGED and TYPE_WINDOW_CONTENT_CHANGED inside Settings/Installer
        if (packageName in SYSTEM_TAMPER_PACKAGES) {
            if (isAnyAppLocked() || hasAnyActiveSession()) {
                if (inspectAndBlockTamperAttempt(packageName)) {
                    return
                }
            }
        }

        // CRITICAL: TYPE_WINDOW_CONTENT_CHANGED must NEVER update or reset currentForegroundAppKey!
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return
        }

        // 3. Strict Package Filtering: Ignore status bar, notifications, and soft keyboards
        if (IGNORED_SYSTEM_PACKAGES.contains(packageName) ||
            packageName.startsWith("com.google.android.inputmethod") ||
            packageName.contains("keyboard") ||
            packageName.contains("ime")
        ) {
            return
        }

        // 4. Handle InstaGuard's own Activities (MainActivity / LockScreenActivity)
        if (packageName == applicationContext.packageName) {
            currentForegroundAppKey?.let { saveStateToPrefs(it) }
            currentForegroundAppKey = null
            return
        }

        // 5. Check if foreground window matches one of our 4 tracked social apps
        val detectedKey = mapPackageToAppKey(packageName)
        if (detectedKey != null) {
            if (isAppLocked(detectedKey, applicationContext)) {
                Log.w(TAG, "[$detectedKey] Opened during active cooldown lock. Enforcing LockScreen.")
                currentForegroundAppKey = null
                stopAllMediaPlayback()
                launchLockScreen(detectedKey)
            } else {
                if (currentForegroundAppKey != null && currentForegroundAppKey != detectedKey) {
                    saveStateToPrefs(currentForegroundAppKey!!)
                }
                currentForegroundAppKey = detectedKey
                Log.d(TAG, "[$detectedKey] Foreground active via TYPE_WINDOW_STATE_CHANGED")
            }
        } else {
            // User switched to another real application or Home Launcher
            currentForegroundAppKey?.let { saveStateToPrefs(it) }
            currentForegroundAppKey = null
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

        // IMPORTANT EXCEPTION: Do NOT block when user is granting permissions!
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
            currentForegroundAppKey?.let { saveStateToPrefs(it) }
            currentForegroundAppKey = null

            // 1. Immediately navigate Back and Home
            performGlobalAction(GLOBAL_ACTION_BACK)
            performGlobalAction(GLOBAL_ACTION_HOME)

            // 2. Launch LockScreen with Tamper Warning
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

    private fun launchLockScreen(appKey: String) {
        val app = TrackedApp.fromKey(appKey) ?: TrackedApp.INSTAGRAM

        // If YOUTUBE, exit Shorts/fullscreen player and kill background process
        if (app.key == "YOUTUBE") {
            performGlobalAction(GLOBAL_ACTION_BACK)
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                am.killBackgroundProcesses("com.google.android.youtube")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

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

        // Delayed sweeps to crush PiP transition
        Handler(Looper.getMainLooper()).postDelayed({
            stopAllMediaPlayback()
            killPipWindowsIfAny()
        }, 200L)

        Handler(Looper.getMainLooper()).postDelayed({
            stopAllMediaPlayback()
            killPipWindowsIfAny()
        }, 500L)
    }

    private fun saveStateToPrefs(appKey: String) {
        val usage = accumulatedUsageMap[appKey] ?: 0L
        val unlock = unlockTimestampMap[appKey] ?: 0L
        prefs.setAccumulatedUsage(appKey, usage)
        prefs.setUnlockTimestamp(appKey, unlock)
    }

    private fun isAnyAppLocked(): Boolean {
        return APP_KEYS.any { isAppLocked(it, applicationContext) }
    }

    private fun hasAnyActiveSession(): Boolean {
        return APP_KEYS.any { getLiveUsageMs(it, applicationContext) > 0L }
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
            audioManager.requestAudioFocus(transientFocusRequest)

            val permanentFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(permanentFocusRequest)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Active PiP Window Hunter:
     * Inspects all interactive windows for Picture-in-Picture mode, pauses media,
     * clicks Close/Dismiss nodes inside the PiP tree, sends GLOBAL_ACTION_BACK,
     * and kills YouTube's background process whenever YOUTUBE is locked.
     */
    private fun killPipWindowsIfAny() {
        try {
            val windowList = windows
            if (windowList != null) {
                for (window in windowList) {
                    if (window.isInPictureInPictureMode) {
                        stopAllMediaPlayback()
                        val root = window.root
                        if (root != null) {
                            dismissPipNodeRecursively(root)
                        }
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                }
            }
            if (isAppLocked("YOUTUBE", applicationContext)) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
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
        currentForegroundAppKey?.let { saveStateToPrefs(it) }
        currentForegroundAppKey = null
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStopwatchTicker()
        currentForegroundAppKey?.let { saveStateToPrefs(it) }
        currentForegroundAppKey = null
    }

    companion object {
        private const val TAG = "AppBlockerService"
        const val TAMPER_WARNING_MESSAGE =
            "Tamper Protection: Cannot Uninstall or Reset InstaGuard while Focus Timer is active!"

        val APP_KEYS = listOf("INSTAGRAM", "YOUTUBE", "FACEBOOK", "TIKTOK")

        val IGNORED_SYSTEM_PACKAGES = setOf(
            "com.android.systemui",
            "android",
            "com.google.android.inputmethod.latin",
            "com.touchtype.swiftkey",
            "com.samsung.android.honeyboard",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller"
        )

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
        var lastTickElapsedRealtime: Long = 0L

        @Volatile
        var appContext: Context? = null

        fun mapPackageToAppKey(packageName: String): String? {
            return TrackedApp.fromPackageName(packageName)?.key
        }

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

            // Only reconstruct from OS system events if SharedPreferences is 100% empty
            if (allZero) {
                syncAndReconstructFromSystemEvents(context)
            }
        }

        /**
         * LAYER 1: OS-Level Usage & Cooldown Reconstruction via UsageStatsManager.queryEvents.
         * Runs ONLY when SharedPreferences is completely empty (e.g. after reinstall or clear data).
         * Will NEVER overwrite existing in-memory data with lower values.
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

                val limitMs = prefs.getUsageLimitMs()
                val cooldownMs = prefs.getCooldownDurationMs()

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

                    val activeUnlock = reconstructedUnlockAt[appKey] ?: 0L
                    if (activeUnlock in 1..eventTime) {
                        reconstructedUnlockAt[appKey] = 0L
                        reconstructedUsage[appKey] = 0L
                        lastResumeMap[appKey] = 0L
                    }

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

                // Finalize remaining in-progress foreground time up to `now`
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
                        Log.w(TAG, "[$appKey] Restored 5h cooldown from OS UsageEvents: unlocks at $recUnlock")
                    } else if (currentUnlock <= now && recUsage > currentUsage) {
                        accumulatedUsageMap[appKey] = maxOf(currentUsage, recUsage)
                        prefs.setAccumulatedUsage(appKey, accumulatedUsageMap[appKey] ?: 0L)
                        Log.i(TAG, "[$appKey] Restored usage from OS UsageEvents: $recUsage ms")
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun getLiveUsageMs(appKey: String, context: Context? = null): Long {
            val unlockAt = unlockTimestampMap[appKey] ?: 0L
            if (unlockAt > 0L && System.currentTimeMillis() >= unlockAt) {
                unlockTimestampMap[appKey] = 0L
                accumulatedUsageMap[appKey] = 0L
                val ctx = context ?: appContext
                ctx?.let { GuardPreferences(it).resetApp(appKey) }
            }
            return maxOf(0L, accumulatedUsageMap[appKey] ?: 0L)
        }

        fun getRemainingLockMs(appKey: String, context: Context? = null): Long {
            val unlockAt = unlockTimestampMap[appKey] ?: 0L
            val now = System.currentTimeMillis()
            val remaining = unlockAt - now

            if (unlockAt > 0L && remaining <= 0L) {
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
