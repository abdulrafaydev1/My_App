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
import android.view.KeyEvent
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
 * Dispatches media pause/stop keys and seizes exclusive audio focus to guarantee zero Picture-in-Picture bypasses.
 */
class LockScreenActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockScreenBinding
    private lateinit var guardManager: GuardManager
    private val timerHandler = Handler(Looper.getMainLooper())

    private var lockedAppKey: String = "INSTAGRAM"
    private var appName: String = "Instagram"

    private val countdownRunnable = object : Runnable {
        override fun run() {
            val remainingMs = AppBlockerService.getRemainingLockMs(lockedAppKey, this@LockScreenActivity)
            if (remainingMs <= 0L) {
                binding.tvCountdownTimer.text = "00:00:00.000"
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
        super.onCreate(savedInstanceState)
        binding = ActivityLockScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        guardManager = GuardManager(applicationContext)

        updateLockedAppFromIntent()
        applyMonospaceTypeface()
        configureWindowFlags()
        setupBackPressInterceptor()
        setupListeners()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) setIntent(intent)
        updateLockedAppFromIntent()
    }

    private fun updateLockedAppFromIntent() {
        val currentIntent = intent
        lockedAppKey = currentIntent?.getStringExtra("EXTRA_APP_KEY")
            ?: currentIntent?.getStringExtra(EXTRA_APP_KEY)
            ?: "INSTAGRAM"

        val trackedApp = TrackedApp.fromKey(lockedAppKey)
        appName = currentIntent?.getStringExtra("EXTRA_APP_NAME")
            ?: currentIntent?.getStringExtra(EXTRA_APP_NAME)
            ?: (trackedApp?.displayName ?: lockedAppKey)

        applyDynamicTexts()
    }

    override fun onResume() {
        super.onResume()
        // Force-pause media playback and seize audio focus whenever lock screen is active
        stopAllMediaPlayback()

        val remainingMs = AppBlockerService.getRemainingLockMs(lockedAppKey, this)
        if (remainingMs <= 0L) {
            Toast.makeText(this, "$appName cooldown completed.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val unlockTime = AppBlockerService.unlockTimestampMap[lockedAppKey]
            ?: guardManager.prefs.getUnlockTimestamp(lockedAppKey)
        if (unlockTime > 0L) {
            binding.tvUnlockTimeEstimate.text = "Unlocks at ${guardManager.formatTimeOfDay(unlockTime)}"
        }

        timerHandler.removeCallbacks(countdownRunnable)
        timerHandler.post(countdownRunnable)
    }

    override fun onPause() {
        super.onPause()
        timerHandler.removeCallbacks(countdownRunnable)
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
        stopAllMediaPlayback()
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(homeIntent)
        finish()
    }

    /**
     * Dispatches hardware media pause/stop keys and seizes exclusive audio focus,
     * preventing YouTube/TikTok videos from playing in PiP or in the background.
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

    override fun onDestroy() {
        super.onDestroy()
        timerHandler.removeCallbacks(countdownRunnable)
    }

    companion object {
        const val EXTRA_APP_KEY = "extra_app_key"
        const val EXTRA_APP_NAME = "extra_app_name"
    }
}
