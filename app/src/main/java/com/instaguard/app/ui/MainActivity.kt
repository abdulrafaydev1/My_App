package com.instaguard.app.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.instaguard.app.InstaGuardWidgetProvider
import com.instaguard.app.R
import com.instaguard.app.alarm.AlarmFragment
import com.instaguard.app.data.GuardPreferences
import com.instaguard.app.databinding.ActivityMainBinding
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager
import com.instaguard.app.util.UsageStatsHelper
import java.util.Locale

/**
 * Main Activity for InstaGuard Pro v15.2 (Obsidian & Volt-Lime Design System).
 * Features a Floating Translucent Pill Navigation Dock with synchronized continuous
 * directional dual-screen horizontal sliding and gliding active pill indicator (iOS App Store Style),
 * Bento-Grid screen layouts, real-time 30 FPS live ticker updates, and complete digital wellbeing shields.
 * 100% crash-proof lifecycle management with lazy view lookups and fail-safe null guards.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var guardManager: GuardManager
        private set

    private val uiHandler = Handler(Looper.getMainLooper())
    private val navInterpolator = FastOutSlowInInterpolator()
    private var previewTts: TextToSpeech? = null
    private var isPreviewTtsReady = false

    private lateinit var homeFragment: HomeDashboardFragment
    private lateinit var analyticsFragment: AnalyticsFragment
    private lateinit var alarmFragment: AlarmFragment
    private lateinit var shieldsFragment: ShieldsFragment
    private lateinit var settingsFragment: SettingsFragment
    private var activeFragment: Fragment? = null

    private var activeNavAnimator: ValueAnimator? = null
    private var currentTabIndex: Int = 0
    private var isProgrammaticNavSelection: Boolean = false
    private val iosSmoothInterpolator = PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f)
    private val argbEvaluator = ArgbEvaluator()

    private val liveTickerRunnable = object : Runnable {
        override fun run() {
            tickLiveMetrics()
            uiHandler.postDelayed(this, 33L) // 33ms (~30 FPS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
        } catch (e: Exception) {
            e.printStackTrace()
            finish()
            return
        }

        try {
            guardManager = GuardManager(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            checkAndRequestAlarmOptimizations()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            initPreviewTts()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        setupFragments(savedInstanceState)
        setupBottomNavigation()
        setupToolbar()
        setupBackNavigation()
    }

    private fun setupFragments(savedInstanceState: Bundle?) {
        val fm = supportFragmentManager
        if (savedInstanceState == null) {
            homeFragment = HomeDashboardFragment()
            analyticsFragment = AnalyticsFragment()
            alarmFragment = AlarmFragment()
            shieldsFragment = ShieldsFragment()
            settingsFragment = SettingsFragment()

            fm.beginTransaction()
                .add(R.id.fragmentContainer, homeFragment, "HOME")
                .add(R.id.fragmentContainer, analyticsFragment, "ANALYTICS")
                .add(R.id.fragmentContainer, alarmFragment, "ALARM")
                .add(R.id.fragmentContainer, shieldsFragment, "SHIELDS")
                .add(R.id.fragmentContainer, settingsFragment, "SETTINGS")
                .commit()
        } else {
            homeFragment = fm.findFragmentByTag("HOME") as? HomeDashboardFragment ?: HomeDashboardFragment()
            analyticsFragment = fm.findFragmentByTag("ANALYTICS") as? AnalyticsFragment ?: AnalyticsFragment()
            alarmFragment = fm.findFragmentByTag("ALARM") as? AlarmFragment ?: AlarmFragment()
            shieldsFragment = fm.findFragmentByTag("SHIELDS") as? ShieldsFragment ?: ShieldsFragment()
            settingsFragment = fm.findFragmentByTag("SETTINGS") as? SettingsFragment ?: SettingsFragment()
        }

        activeFragment = homeFragment
        binding.fragmentContainer.post {
            ensureTabScreensVisibility()
        }
    }

    private fun getTabScreenView(index: Int): View? {
        val fragment = when (index) {
            0 -> if (::homeFragment.isInitialized) homeFragment else null
            1 -> if (::analyticsFragment.isInitialized) analyticsFragment else null
            2 -> if (::alarmFragment.isInitialized) alarmFragment else null
            3 -> if (::shieldsFragment.isInitialized) shieldsFragment else null
            4 -> if (::settingsFragment.isInitialized) settingsFragment else null
            else -> null
        }
        return fragment?.view
    }

    private fun getNavTabItemView(index: Int): View? {
        val menuId = getMenuIdForIndex(index)
        return try {
            binding.bottomNavigationView.findViewById(menuId)
        } catch (_: Exception) {
            null
        }
    }

    private fun getFragmentForIndex(index: Int): Fragment {
        return when (index) {
            0 -> if (::homeFragment.isInitialized) homeFragment else HomeDashboardFragment()
            1 -> if (::analyticsFragment.isInitialized) analyticsFragment else AnalyticsFragment()
            2 -> if (::alarmFragment.isInitialized) alarmFragment else AlarmFragment()
            3 -> if (::shieldsFragment.isInitialized) shieldsFragment else ShieldsFragment()
            4 -> if (::settingsFragment.isInitialized) settingsFragment else SettingsFragment()
            else -> if (::homeFragment.isInitialized) homeFragment else HomeDashboardFragment()
        }
    }

    private fun ensureTabScreensVisibility() {
        try {
            for (i in 0..4) {
                val v = getTabScreenView(i) ?: continue
                v.translationX = 0f
                v.alpha = 1f
                try {
                    v.setLayerType(View.LAYER_TYPE_NONE, null)
                } catch (_: Exception) {}
                v.visibility = if (i == currentTabIndex) View.VISIBLE else View.GONE
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupBottomNavigation() {
        binding.floatingNavDockContainer.post {
            try {
                val initialTab = getNavTabItemView(currentTabIndex)
                if (initialTab != null && initialTab.width > 0) {
                    val lp = binding.navSlidingPillIndicator.layoutParams
                    if (lp != null) {
                        lp.width = initialTab.width
                        binding.navSlidingPillIndicator.layoutParams = lp
                    }
                    binding.navSlidingPillIndicator.translationX = getTabPillX(currentTabIndex)
                }
                updateTabColorsImmediate(currentTabIndex)
                ensureTabScreensVisibility()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        binding.bottomNavigationView.setOnItemSelectedListener { item ->
            if (isProgrammaticNavSelection) return@setOnItemSelectedListener true
            val targetIndex = getIndexForMenuId(item.itemId)
            if (targetIndex != -1) {
                switchTabWithContinuousSlide(targetIndex, animate = true)
            }
            true
        }

        binding.bottomNavigationView.setOnItemReselectedListener { item ->
            val index = getIndexForMenuId(item.itemId)
            if (index != -1) {
                scrollActiveTabToTop(index)
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTabIndex != 0) {
                    switchTabWithContinuousSlide(0, animate = true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })
    }

    private fun switchTabWithContinuousSlide(targetIndex: Int, animate: Boolean = true) {
        if (targetIndex !in 0..4) return

        if (targetIndex == currentTabIndex && activeNavAnimator?.isRunning != true) {
            scrollActiveTabToTop(targetIndex)
            return
        }

        val previousIndex = if (currentTabIndex in 0..4) currentTabIndex else 0
        val outgoingView = getTabScreenView(previousIndex)
        val incomingView = getTabScreenView(targetIndex)

        // If either screen view is not ready yet, perform instant safe switch
        if (outgoingView == null || incomingView == null) {
            currentTabIndex = targetIndex
            activeFragment = getFragmentForIndex(targetIndex)
            ensureTabScreensVisibility()
            updateTabColorsImmediate(targetIndex)
            onTabDestinationEntered(targetIndex)
            return
        }

        val direction = if (targetIndex > previousIndex) 1f else -1f

        // 1. Capture live in-flight pill position before cancelling running animation
        val startPillX = binding.navSlidingPillIndicator.translationX
        val startPillWidth = binding.navSlidingPillIndicator.width.takeIf { it > 0 }
            ?: (getNavTabItemView(previousIndex)?.width ?: 0)

        try {
            activeNavAnimator?.cancel()
        } catch (_: Exception) {}

        // 2. Hide any other non-participating tab views
        for (i in 0..4) {
            if (i != previousIndex && i != targetIndex) {
                getTabScreenView(i)?.let { v ->
                    v.visibility = View.GONE
                    v.translationX = 0f
                    v.alpha = 1f
                    try {
                        v.setLayerType(View.LAYER_TYPE_NONE, null)
                    } catch (_: Exception) {}
                }
            }
        }

        currentTabIndex = targetIndex
        val targetTabView = getNavTabItemView(targetIndex)
        val targetPillX = getTabPillX(targetIndex)
        val targetPillWidth = targetTabView?.width ?: 0

        // Keep BottomNavigationView selectedItemId in sync without re-triggering listener
        isProgrammaticNavSelection = true
        try {
            binding.bottomNavigationView.selectedItemId = getMenuIdForIndex(targetIndex)
        } catch (_: Exception) {}
        isProgrammaticNavSelection = false

        // Update activeFragment reference
        activeFragment = getFragmentForIndex(targetIndex)

        val viewportWidth = (binding.fragmentContainer.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels).toFloat()

        if (!animate || viewportWidth <= 0f) {
            outgoingView.visibility = View.GONE
            outgoingView.translationX = 0f
            outgoingView.alpha = 1f
            try { outgoingView.setLayerType(View.LAYER_TYPE_NONE, null) } catch (_: Exception) {}

            incomingView.visibility = View.VISIBLE
            incomingView.translationX = 0f
            incomingView.alpha = 1f
            try { incomingView.setLayerType(View.LAYER_TYPE_NONE, null) } catch (_: Exception) {}

            binding.navSlidingPillIndicator.translationX = targetPillX
            if (targetPillWidth > 0) {
                val lp = binding.navSlidingPillIndicator.layoutParams
                if (lp != null) {
                    lp.width = targetPillWidth
                    binding.navSlidingPillIndicator.layoutParams = lp
                }
            }
            updateTabColorsImmediate(targetIndex)
            onTabDestinationEntered(targetIndex)
            return
        }

        // 3. Prepare both screens for simultaneous hardware-accelerated horizontal slide
        outgoingView.visibility = View.VISIBLE
        incomingView.visibility = View.VISIBLE
        incomingView.bringToFront()

        try { outgoingView.setLayerType(View.LAYER_TYPE_HARDWARE, null) } catch (_: Exception) {}
        try { incomingView.setLayerType(View.LAYER_TYPE_HARDWARE, null) } catch (_: Exception) {}

        val startOutgoingX = outgoingView.translationX
        val endOutgoingX = -direction * viewportWidth
        val startIncomingX = direction * viewportWidth
        val endIncomingX = 0f

        outgoingView.translationX = startOutgoingX
        incomingView.translationX = startIncomingX
        incomingView.alpha = 1f

        val tabDistance = kotlin.math.abs(targetIndex - previousIndex)
        val durationMs = if (tabDistance > 1) 290L else 250L

        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = iosSmoothInterpolator

            addUpdateListener { anim ->
                val fraction = anim.animatedValue as Float

                outgoingView.translationX = startOutgoingX + (endOutgoingX - startOutgoingX) * fraction
                incomingView.translationX = startIncomingX + (endIncomingX - startIncomingX) * fraction
                outgoingView.alpha = 1f - (0.18f * fraction)

                binding.navSlidingPillIndicator.translationX = startPillX + (targetPillX - startPillX) * fraction
                if (startPillWidth != targetPillWidth && targetPillWidth > 0) {
                    val currentW = (startPillWidth + (targetPillWidth - startPillWidth) * fraction).toInt()
                    val lp = binding.navSlidingPillIndicator.layoutParams
                    if (lp != null && lp.width != currentW) {
                        lp.width = currentW
                        binding.navSlidingPillIndicator.layoutParams = lp
                    }
                }

                blendTabColors(previousIndex, targetIndex, fraction)
            }

            addListener(object : AnimatorListenerAdapter() {
                private var wasCancelled = false

                override fun onAnimationCancel(animation: Animator) {
                    wasCancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    try { outgoingView.setLayerType(View.LAYER_TYPE_NONE, null) } catch (_: Exception) {}
                    try { incomingView.setLayerType(View.LAYER_TYPE_NONE, null) } catch (_: Exception) {}

                    if (!wasCancelled) {
                        outgoingView.visibility = View.GONE
                        outgoingView.translationX = 0f
                        outgoingView.alpha = 1f

                        incomingView.visibility = View.VISIBLE
                        incomingView.translationX = 0f
                        incomingView.alpha = 1f

                        binding.navSlidingPillIndicator.translationX = targetPillX
                        updateTabColorsImmediate(targetIndex)
                        onTabDestinationEntered(targetIndex)
                    }
                }
            })
        }

        activeNavAnimator = animator
        animator.start()
    }

    private fun getTabPillX(index: Int): Float {
        val tabView = getNavTabItemView(index) ?: return 0f
        var x = tabView.left.toFloat()
        var parent = tabView.parent as? View
        while (parent != null && parent != binding.bottomNavigationView && parent != binding.floatingNavDockContainer) {
            x += parent.left.toFloat()
            parent = parent.parent as? View
        }
        return x
    }

    private fun getMenuIdForIndex(index: Int): Int {
        return when (index) {
            0 -> R.id.nav_home
            1 -> R.id.nav_analytics
            2 -> R.id.nav_alarm
            3 -> R.id.nav_shields
            4 -> R.id.nav_settings
            else -> R.id.nav_home
        }
    }

    private fun getIndexForMenuId(menuId: Int): Int {
        return when (menuId) {
            R.id.nav_home -> 0
            R.id.nav_analytics -> 1
            R.id.nav_alarm -> 2
            R.id.nav_shields -> 3
            R.id.nav_settings -> 4
            else -> -1
        }
    }

    private fun applyTabColor(tabIndex: Int, color: Int) {
        val tabItem = getNavTabItemView(tabIndex) as? ViewGroup ?: return
        fun applyRecursively(view: View) {
            when (view) {
                is ImageView -> {
                    try {
                        view.setColorFilter(color, PorterDuff.Mode.SRC_IN)
                    } catch (_: Exception) {}
                }
                is TextView -> {
                    try {
                        view.setTextColor(color)
                    } catch (_: Exception) {}
                }
                is ViewGroup -> {
                    for (c in 0 until view.childCount) {
                        applyRecursively(view.getChildAt(c))
                    }
                }
            }
        }
        applyRecursively(tabItem)
    }

    private fun updateTabColorsImmediate(targetIndex: Int) {
        val activeColor = try {
            ContextCompat.getColor(this, R.color.ig_accent_amber)
        } catch (_: Exception) {
            Color.parseColor("#FF8A1E")
        }
        val inactiveColor = try {
            ContextCompat.getColor(this, R.color.ig_nav_inactive)
        } catch (_: Exception) {
            Color.parseColor("#C9D1D9")
        }

        for (i in 0..4) {
            val color = if (i == targetIndex) activeColor else inactiveColor
            applyTabColor(i, color)
        }
    }

    private fun blendTabColors(previousIndex: Int, targetIndex: Int, fraction: Float) {
        val activeColor = try {
            ContextCompat.getColor(this, R.color.ig_accent_amber)
        } catch (_: Exception) {
            Color.parseColor("#FF8A1E")
        }
        val inactiveColor = try {
            ContextCompat.getColor(this, R.color.ig_nav_inactive)
        } catch (_: Exception) {
            Color.parseColor("#C9D1D9")
        }

        val outgoingColor = argbEvaluator.evaluate(fraction, activeColor, inactiveColor) as Int
        val incomingColor = argbEvaluator.evaluate(fraction, inactiveColor, activeColor) as Int

        applyTabColor(previousIndex, outgoingColor)
        applyTabColor(targetIndex, incomingColor)

        for (i in 0..4) {
            if (i != previousIndex && i != targetIndex) {
                applyTabColor(i, inactiveColor)
            }
        }
    }

    private fun scrollActiveTabToTop(tabIndex: Int) {
        val rootView = getTabScreenView(tabIndex) ?: return
        when (rootView) {
            is NestedScrollView -> rootView.smoothScrollTo(0, 0)
            is ScrollView -> rootView.smoothScrollTo(0, 0)
            is ViewGroup -> {
                val nested = findScrollView(rootView)
                if (nested is NestedScrollView) nested.smoothScrollTo(0, 0)
                else if (nested is ScrollView) nested.smoothScrollTo(0, 0)
            }
        }
    }

    private fun findScrollView(view: View): View? {
        if (view is NestedScrollView || view is ScrollView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findScrollView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun onTabDestinationEntered(targetIndex: Int) {
        val targetFragment = getFragmentForIndex(targetIndex)
        activeFragment = targetFragment

        if (targetFragment == alarmFragment) {
            checkAndRequestAlarmOptimizations()
        }
        refreshActiveTab(targetFragment)
    }

    private fun setupToolbar() {
        binding.toolbarMain.title = ""
        binding.tvToolbarTitle.text = "InstaGuard Pro"
        binding.tvToolbarBadge.text = "v15.2 PRO"
        binding.btnRefresh.setOnClickListener {
            it.animate().rotationBy(360f).setDuration(350L).setInterpolator(navInterpolator).start()
            AppBlockerService.syncFromPrefs(this)
            refreshAllFragments()
            InstaGuardWidgetProvider.updateAllWidgets(this)
            Toast.makeText(this, R.string.btn_refresh, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkAndRequestAlarmOptimizations() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            } catch (_: Exception) {}
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            if (am != null && !am.canScheduleExactAlarms()) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            if (nm != null && !nm.canUseFullScreenIntent()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {}
            }
        }
    }

    private fun refreshActiveTab(fragment: Fragment) {
        try {
            if (!fragment.isAdded || fragment.view == null) return
            val hasUsage = UsageStatsHelper.hasUsageStatsPermission(this)
            val hasAccessibility = GuardManager.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)

            when (fragment) {
                is HomeDashboardFragment -> fragment.updateDashboard(guardManager, hasUsage, hasAccessibility)
                is AnalyticsFragment -> fragment.updateAnalytics(guardManager)
                is AlarmFragment -> fragment.refreshAlarmsUI()
                is ShieldsFragment -> fragment.refreshShieldsUI(guardManager)
                is SettingsFragment -> fragment.refreshSettingsUI(guardManager)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun refreshAllFragments() {
        try {
            val hasUsage = UsageStatsHelper.hasUsageStatsPermission(this)
            val hasAccessibility = GuardManager.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)

            if (::homeFragment.isInitialized && homeFragment.isAdded && homeFragment.view != null) {
                homeFragment.updateDashboard(guardManager, hasUsage, hasAccessibility)
            }
            if (::analyticsFragment.isInitialized && analyticsFragment.isAdded && analyticsFragment.view != null) {
                analyticsFragment.updateAnalytics(guardManager)
            }
            if (::alarmFragment.isInitialized && alarmFragment.isAdded && alarmFragment.view != null) {
                alarmFragment.refreshAlarmsUI()
            }
            if (::shieldsFragment.isInitialized && shieldsFragment.isAdded && shieldsFragment.view != null) {
                shieldsFragment.refreshShieldsUI(guardManager)
            }
            if (::settingsFragment.isInitialized && settingsFragment.isAdded && settingsFragment.view != null) {
                settingsFragment.refreshSettingsUI(guardManager)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun tickLiveMetrics() {
        try {
            val hasUsage = UsageStatsHelper.hasUsageStatsPermission(this)
            val hasAccessibility = GuardManager.isAccessibilityServiceEnabled(this, AppBlockerService::class.java)

            // Always update HomeDashboard at 30 FPS when visible or added
            if (::homeFragment.isInitialized && homeFragment.isAdded && homeFragment.view != null) {
                homeFragment.updateDashboard(guardManager, hasUsage, hasAccessibility)
            }

            // When Analytics is foreground, tick its live screen-time counters
            if (activeFragment == analyticsFragment && ::analyticsFragment.isInitialized && analyticsFragment.isAdded && analyticsFragment.view != null) {
                analyticsFragment.updateAnalytics(guardManager)
            }

            // When Shields is foreground, update live scanner HUD diagnostic
            if (activeFragment == shieldsFragment && ::shieldsFragment.isInitialized && shieldsFragment.isAdded && shieldsFragment.view != null) {
                shieldsFragment.updateLiveHud()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            AppBlockerService.isOurAppScreenOpen = true
            AppBlockerService.syncFromPrefs(this)

            refreshAllFragments()
            InstaGuardWidgetProvider.updateAllWidgets(this)
            ensureTabScreensVisibility()
            updateTabColorsImmediate(currentTabIndex)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        try {
            uiHandler.removeCallbacks(liveTickerRunnable)
            uiHandler.post(liveTickerRunnable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            AppBlockerService.isOurAppScreenOpen = false
            uiHandler.removeCallbacks(liveTickerRunnable)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            previewTts?.stop()
            previewTts?.shutdown()
            previewTts = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initPreviewTts() {
        try {
            previewTts = TextToSpeech(applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    previewTts?.language = Locale.US
                    previewTts?.setSpeechRate(0.95f)
                    isPreviewTtsReady = true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // -------------------------------------------------------------
    // Action Delegates from Tabs & Fragments
    // -------------------------------------------------------------

    fun notifyModeChanged() {
        refreshAllFragments()
        InstaGuardWidgetProvider.updateAllWidgets(this)
    }

    fun startDeepWork(durationMs: Long) {
        AppBlockerService.activateDeepWorkMode(this, durationMs)
        notifyModeChanged()
        val hours = durationMs / 3_600_000L
        Toast.makeText(
            this,
            "🚀 ${hours}-Hour Deep Work Study Lock Activated! All social apps locked.",
            Toast.LENGTH_LONG
        ).show()
    }

    fun confirmCancelStudyMode() {
        if (!AppBlockerService.isDeepWorkModeActive(this)) {
            Toast.makeText(this, "ℹ️ Study Mode is not currently active.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Stop Study Mode?")
            .setMessage("Are you sure you want to cancel your active Deep Work / Study Mode lock?")
            .setPositiveButton("Yes, Cancel Now") { dialog, _ ->
                AppBlockerService.cancelDeepWorkMode(this)
                notifyModeChanged()
                Toast.makeText(this, "✅ Deep Work / Study Mode Cancelled!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Keep Focusing") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    fun promptPinForVisualShieldDisable() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(22f), dpToPx(14f), dpToPx(22f), dpToPx(8f))
        }

        val tvInfo = TextView(this).apply {
            text = "Enter your 4-digit Master PIN to disable AI Visual Shield & Safe Browser Guard."
            setTextColor(Color.parseColor("#9BA3B0"))
            textSize = 13f
        }

        val etPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.parseColor("#F5F7FA"))
            hint = "••••"
            setHintTextColor(Color.parseColor("#636B78"))
        }

        container.addView(tvInfo)
        container.addView(etPin)

        AlertDialog.Builder(this)
            .setTitle("Disable AI Visual Shield?")
            .setView(container)
            .setPositiveButton("VERIFY & DISABLE") { dialog, _ ->
                val entered = etPin.text.toString().trim()
                if (entered == guardManager.prefs.masterPin) {
                    guardManager.prefs.isAiVisualShieldEnabled = false
                    shieldsFragment.refreshShieldsUI(guardManager)
                    Toast.makeText(this, "AI Visual Shield Disabled", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Incorrect Master PIN!", Toast.LENGTH_SHORT).show()
                    shieldsFragment.refreshShieldsUI(guardManager)
                }
            }
            .setNegativeButton("CANCEL") { dialog, _ ->
                shieldsFragment.refreshShieldsUI(guardManager)
                dialog.dismiss()
            }
            .setOnCancelListener {
                shieldsFragment.refreshShieldsUI(guardManager)
            }
            .show()
    }

    fun startNightSleepDisableMathChallenge(step: Int) {
        if (step > 3) {
            showNightSleepDisablePinDialog()
            return
        }

        val num1 = (14..28).random()
        val num2 = (5..9).random()
        val num3 = (20..85).random()
        val isAddition = (0..1).random() == 1

        val problemStr = if (isAddition) "($num1 × $num2) + $num3" else "($num1 × $num2) - $num3"
        val expectedAnswer = if (isAddition) (num1 * num2) + num3 else (num1 * num2) - num3

        val container = FrameLayout(this).apply {
            setPadding(56, 24, 56, 12)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#F5F7FA"))
            hint = "Answer"
            setHintTextColor(Color.parseColor("#636B78"))
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("🌙 Night Sleep Guard Challenge (Step $step of 3)")
            .setMessage("It is currently past 11:30 PM. Solve this challenge to disable Night Sleep Guard:\n\n$problemStr = ?")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton(if (step < 3) "Next Step ➔" else "Proceed to PIN ➔") { dialog, _ ->
                val enteredAnswer = input.text.toString().trim().toIntOrNull()
                if (enteredAnswer != null && enteredAnswer == expectedAnswer) {
                    dialog.dismiss()
                    Toast.makeText(this, "✅ Step $step solved!", Toast.LENGTH_SHORT).show()
                    startNightSleepDisableMathChallenge(step + 1)
                } else {
                    dialog.dismiss()
                    Toast.makeText(this, "❌ Incorrect answer! Challenge reset to Step 1.", Toast.LENGTH_LONG).show()
                    startNightSleepDisableMathChallenge(step = 1)
                }
            }
            .setNegativeButton("Keep Sleep Guard ON") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showNightSleepDisablePinDialog() {
        val container = FrameLayout(this).apply {
            setPadding(56, 24, 56, 12)
        }
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.MONOSPACE
            setTextColor(Color.parseColor("#F5F7FA"))
            hint = "••••"
            setHintTextColor(Color.parseColor("#636B78"))
            filters = arrayOf(InputFilter.LengthFilter(4))
        }
        container.addView(input)

        AlertDialog.Builder(this)
            .setTitle("🔐 Final Step: Master PIN")
            .setMessage("Enter your 4-digit Master PIN to disable Night Sleep Guard during sleep hours.")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Authorize") { dialog, _ ->
                val entered = input.text.toString().trim()
                if (entered == guardManager.prefs.masterPin) {
                    guardManager.prefs.isNightSleepGuardEnabled = false
                    shieldsFragment.refreshShieldsUI(guardManager)
                    notifyModeChanged()
                    Toast.makeText(this, "🌙 Night Sleep Guard Disabled.", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "❌ Incorrect Master PIN!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showMasterPinSettingsDialog() {
        val anyLocked = AppBlockerService.APP_KEYS.any { AppBlockerService.isAppLocked(it, this) }
        if (anyLocked) {
            Toast.makeText(this, "Cannot change Master PIN while any app is locked!", Toast.LENGTH_LONG).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24f), dpToPx(16f), dpToPx(24f), dpToPx(8f))
        }

        val tvCurrentLabel = TextView(this).apply {
            text = "Current 4-Digit PIN:"
            setTextColor(Color.parseColor("#9BA3B0"))
            textSize = 13f
        }
        val etCurrentPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.parseColor("#F5F7FA"))
            hint = "••••"
            setHintTextColor(Color.parseColor("#636B78"))
        }

        val tvNewLabel = TextView(this).apply {
            text = "New 4-Digit PIN:"
            setTextColor(Color.parseColor("#9BA3B0"))
            textSize = 13f
            setPadding(0, dpToPx(12f), 0, 0)
        }
        val etNewPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.parseColor("#F5F7FA"))
            hint = "••••"
            setHintTextColor(Color.parseColor("#636B78"))
        }

        val tvConfirmLabel = TextView(this).apply {
            text = "Confirm New PIN:"
            setTextColor(Color.parseColor("#9BA3B0"))
            textSize = 13f
            setPadding(0, dpToPx(12f), 0, 0)
        }
        val etConfirmPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.parseColor("#F5F7FA"))
            hint = "••••"
            setHintTextColor(Color.parseColor("#636B78"))
        }

        container.addView(tvCurrentLabel)
        container.addView(etCurrentPin)
        container.addView(tvNewLabel)
        container.addView(etNewPin)
        container.addView(tvConfirmLabel)
        container.addView(etConfirmPin)

        AlertDialog.Builder(this)
            .setTitle("🔐 Master PIN Settings")
            .setMessage("Change your 4-digit Master PIN for emergency 5-minute unlock.")
            .setView(container)
            .setPositiveButton("SAVE PIN") { dialog, _ ->
                val currentEntered = etCurrentPin.text.toString().trim()
                val newEntered = etNewPin.text.toString().trim()
                val confirmEntered = etConfirmPin.text.toString().trim()

                if (currentEntered != guardManager.prefs.masterPin) {
                    Toast.makeText(this, "Incorrect current PIN!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newEntered.length != 4 || !newEntered.all { it.isDigit() }) {
                    Toast.makeText(this, "New PIN must be exactly 4 digits!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                if (newEntered != confirmEntered) {
                    Toast.makeText(this, "New PIN and confirmation do not match!", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                guardManager.prefs.masterPin = newEntered
                Toast.makeText(this, "Master PIN updated successfully!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    fun showAddCustomAppDialog() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveList = pm.queryIntentActivities(mainIntent, 0)

        val corePackages = setOf(
            packageName,
            "com.instagram.android", "com.instagram.lite",
            "com.google.android.youtube", "com.google.android.youtube.tv",
            "com.facebook.katana", "com.facebook.lite", "com.facebook.orca",
            "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.ss.android.ugc.tiktok.live.lite"
        )

        data class AppEntry(val label: String, val packageName: String)
        val appEntries = resolveList.mapNotNull { resolveInfo ->
            val pkg = resolveInfo.activityInfo.packageName
            if (corePackages.contains(pkg)) null
            else {
                val label = resolveInfo.loadLabel(pm).toString()
                AppEntry(label, pkg)
            }
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase(Locale.ROOT) }

        if (appEntries.isEmpty()) {
            Toast.makeText(this, "No additional launchable apps found.", Toast.LENGTH_SHORT).show()
            return
        }

        val currentTracked = guardManager.prefs.getCustomTrackedPackages().toMutableSet()
        val appNames = appEntries.map { "${it.label} (${it.packageName})" }.toTypedArray()
        val checkedItems = BooleanArray(appEntries.size) { i ->
            currentTracked.contains(appEntries[i].packageName)
        }

        AlertDialog.Builder(this)
            .setTitle("📱 Select Apps to Block & Track")
            .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                val pkg = appEntries[which].packageName
                if (isChecked) {
                    currentTracked.add(pkg)
                } else {
                    currentTracked.remove(pkg)
                }
            }
            .setPositiveButton("SAVE") { dialog, _ ->
                guardManager.prefs.setCustomTrackedPackages(currentTracked)
                notifyModeChanged()
                Toast.makeText(this, "Shield updated: ${currentTracked.size} custom apps monitored!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    fun showMaintenanceModeDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(24f), dpToPx(16f), dpToPx(24f), dpToPx(8f))
        }

        val tvInfo = TextView(this).apply {
            text = "Enter your 4-digit Master PIN to unlock a 5-minute Maintenance Window. This temporarily pauses anti-tamper shields for uninstallation or system management."
            setTextColor(Color.parseColor("#9BA3B0"))
            textSize = 13f
        }

        val etPin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(4))
            setTextColor(Color.parseColor("#F5F7FA"))
            hint = "••••"
            setHintTextColor(Color.parseColor("#636B78"))
            setPadding(0, dpToPx(12f), 0, dpToPx(8f))
        }

        container.addView(tvInfo)
        container.addView(etPin)

        AlertDialog.Builder(this)
            .setTitle("🛠️ 5-Minute Maintenance Mode")
            .setView(container)
            .setPositiveButton("ACTIVATE") { dialog, _ ->
                val entered = etPin.text.toString().trim()
                if (entered == guardManager.prefs.masterPin) {
                    val maintenanceUntil = System.currentTimeMillis() + 5 * 60 * 1000L
                    guardManager.prefs.maintenanceUntilMs = maintenanceUntil
                    Toast.makeText(this, "🛠️ Maintenance Mode active for 5 minutes! Anti-tamper paused.", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                } else {
                    Toast.makeText(this, "Incorrect Master PIN!", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    fun showEditCustomVoiceDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(22f), dpToPx(14f), dpToPx(22f), dpToPx(8f))
        }

        val tvBlockedLabel = TextView(this).apply {
            text = "Voice Message When App Is Blocked / Locked:"
            setTextColor(Color.parseColor("#9BA3B0"))
            textSize = 12.5f
        }
        val etBlockedVoice = EditText(this).apply {
            setText(guardManager.prefs.customVoiceBlockedMsg)
            hint = "Voice message when app is blocked..."
            setTextColor(Color.parseColor("#F5F7FA"))
            setHintTextColor(Color.parseColor("#636B78"))
            textSize = 14f
        }

        val tvWarningLabel = TextView(this).apply {
            text = "Extra Message on 10m / 5m / 1m Warning:"
            setTextColor(Color.parseColor("#9BA3B0"))
            textSize = 12.5f
            setPadding(0, dpToPx(12f), 0, 0)
        }
        val etWarningVoice = EditText(this).apply {
            setText(guardManager.prefs.customVoiceWarningMsg)
            hint = "Extra message on 10m/5m/1m warning..."
            setTextColor(Color.parseColor("#F5F7FA"))
            setHintTextColor(Color.parseColor("#636B78"))
            textSize = 14f
        }

        container.addView(tvBlockedLabel)
        container.addView(etBlockedVoice)
        container.addView(tvWarningLabel)
        container.addView(etWarningVoice)

        AlertDialog.Builder(this)
            .setTitle("🗣️ Edit Custom Voice Alert")
            .setView(container)
            .setPositiveButton("💾 Save Voice") { dialog, _ ->
                val newBlocked = etBlockedVoice.text.toString().trim()
                    .ifEmpty { GuardPreferences.DEFAULT_CUSTOM_VOICE_BLOCKED_MSG }
                val newWarning = etWarningVoice.text.toString().trim()
                    .ifEmpty { GuardPreferences.DEFAULT_CUSTOM_VOICE_WARNING_MSG }

                guardManager.prefs.customVoiceBlockedMsg = newBlocked
                guardManager.prefs.customVoiceWarningMsg = newWarning
                settingsFragment.refreshSettingsUI(guardManager)
                Toast.makeText(this, "✅ Custom Voice Message Saved!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNeutralButton("🔄 Reset Default") { dialog, _ ->
                guardManager.prefs.customVoiceBlockedMsg = GuardPreferences.DEFAULT_CUSTOM_VOICE_BLOCKED_MSG
                guardManager.prefs.customVoiceWarningMsg = GuardPreferences.DEFAULT_CUSTOM_VOICE_WARNING_MSG
                settingsFragment.refreshSettingsUI(guardManager)
                Toast.makeText(this, "🔄 Default Voice Messages Restored!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun testCustomVoiceMessageNow() {
        val blockedMsg = guardManager.prefs.customVoiceBlockedMsg.trim()
            .ifEmpty { GuardPreferences.DEFAULT_CUSTOM_VOICE_BLOCKED_MSG }
            .replace("{app}", "Instagram", ignoreCase = true)

        val spokenByService = AppBlockerService.previewCustomVoice(blockedMsg)
        if (!spokenByService) {
            if (previewTts == null || !isPreviewTtsReady) {
                initPreviewTts()
            }
            previewTts?.speak(blockedMsg, TextToSpeech.QUEUE_FLUSH, null, "preview_voice_now")
        }
        Toast.makeText(this, "🔊 Speaking Custom Voice Alert...", Toast.LENGTH_SHORT).show()
    }

    fun openUsageSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    fun openOverlaySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Overlay permission not required on this Android version.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun dpToPx(dp: Float): Int {
        val density = resources.displayMetrics.density
        return (dp * density + 0.5f).toInt()
    }
}
