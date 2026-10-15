package com.instaguard.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.instaguard.app.databinding.ActivityLockScreenBinding
import com.instaguard.app.model.TrackedApp
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.util.GuardManager

/**
 * Full-screen, non-bypassable lock activity displayed during an app's focus cooldown period.
 * Dynamically adapts to whichever of the 4 social apps is locked (Instagram, YouTube, Facebook, TikTok).
 * Configured with overlay window flags and audio focus seizure to prevent PiP bypasses.
 */
class LockScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenBinding
    private lateinit var guardManager: GuardManager
    private val timerHandler = Handler(Looper.getMainLooper())

    private var appKey: String = TrackedApp.INSTAGRAM.key
    private var appName: String = TrackedApp.INSTAGRAM.displayName

    private val countdownRunnable = object : Runnable {
        override fun run() {
            val remainingMs = AppBlockerService.getRemainingCooldownMs(appKey, guardManager.prefs)
            if (remainingMs <= 0L) {
                binding.tvCountdownTimer.text = "00:00:00.000"
                AppBlockerService.unlockApp(appKey, guardManager.prefs)
                Toast.makeText(
                    this@LockScreenActivity,
                    "$appName cooldown completed. You may now return.",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            } else {
                binding.tvCountdownTimer.text = guardManager.formatWithMillis(remainingMs)
                timerHandler.postDelayed(this, 33L)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Configure overlay window type if permission is available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (Settings.canDrawOverlays(this)) {
                try {
                    window.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not set TYPE_APPLICATION_OVERLAY: ${e.message}")
                }
            }
        }

        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        guardManager = GuardManager(applicationContext)

        extractAppDetails(intent)
        applyDynamicTexts()
        applyMonospaceTypeface()
        configureWindowFlags()
        setupBackPressInterceptor()
        setupListeners()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let { extractAppDetails(it) }
        applyDynamicTexts()
    }

    override fun onResume() {
        super.onResume()
        seizeAudioFocus()

        if (!AppBlockerService.isAppLocked(appKey, guardManager.prefs)) {
            Toast.makeText(this, "$appName cooldown completed.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val unlockTime = guardManager.prefs.getUnlockTimestamp(appKey)
        binding.tvUnlockTimeEstimate.text = "Unlocks at ${guardManager.formatTimeOfDay(unlockTime)}"

        timerHandler.removeCallbacks(countdownRunnable)
        timerHandler.post(countdownRunnable)
    }

    override fun onPause() {
        super.onPause()
        timerHandler.removeCallbacks(countdownRunnable)
    }

    private fun extractAppDetails(intent: Intent) {
        appKey = intent.getStringExtra(EXTRA_APP_KEY) ?: TrackedApp.INSTAGRAM.key
        appName = intent.getStringExtra(EXTRA_APP_NAME)
            ?: (TrackedApp.fromKey(appKey)?.displayName ?: "App")
    }

    private fun applyDynamicTexts() {
        binding.tvLockBadgeTitle.text = "${appName.uppercase()} FOCUS SHIELD"
        binding.tvLockTitle.text = "$appName is Locked for Focus"
        binding.tvLockSubtitle.text = "You have used your $appName allowance. Take a break while your focus resets."
    }

    private fun applyMonospaceTypeface() {
        binding.tvCountdownTimer.typeface = Typeface.MONOSPACE
    }

    private fun configureWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
    }

    /**
     * Prevents bypassing the lock screen via system Back button or gestures.
     * Back navigation automatically redirects to the device's Home Screen.
     */
    private fun setupBackPressInterceptor() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                redirectToHomeScreen()
            }
        })
    }

    private fun setupListeners() {
        binding.btnGoHome.setOnClickListener {
            redirectToHomeScreen()
        }
    }

    private fun redirectToHomeScreen() {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
        finish()
    }

    private fun seizeAudioFocus() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(audioAttributes)
                    .build()
                audioManager.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error seizing audio focus", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(countdownRunnable)
    }

    companion object {
        private const val TAG = "LockScreenActivity"
        const val EXTRA_APP_KEY = "extra_app_key"
        const val EXTRA_APP_NAME = "extra_app_name"
    }
}
