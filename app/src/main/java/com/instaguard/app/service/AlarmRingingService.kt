package com.instaguard.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.instaguard.app.R
import com.instaguard.app.alarm.AlarmManagerHelper
import com.instaguard.app.alarm.AlarmReceiver
import com.instaguard.app.alarm.AlarmRingingActivity
import com.instaguard.app.alarm.HardAlarmSoundEngine
import com.instaguard.app.alarm.SmartAlarm
import java.util.Locale

/**
 * Dedicated Foreground Alarm Ringing Service.
 * Holds CPU WakeLock, full screen intent, and manages continuous audio,
 * vibration, and TTS even when the phone screen is off/locked.
 * Starts audio immediately and independently of the Activity lifecycle.
 */
class AlarmRingingService : Service(), TextToSpeech.OnInitListener {

    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrator: Vibrator? = null
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var activeAlarm: SmartAlarm? = null
    private var pendingSpeechText: String? = null
    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        initVibrator()
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val action = intent.action

        if (action == ACTION_STOP_ALARM || action == ACTION_DISMISS_ALARM) {
            val alarmId = intent.getStringExtra(AlarmManagerHelper.EXTRA_ALARM_ID)
                ?: intent.getStringExtra("alarm_id")
                ?: activeAlarmId
            stopRingingAndDismiss(alarmId)
            return START_NOT_STICKY
        }

        // 1. Acquire Service CPU WakeLock FIRST, then release Receiver Handoff WakeLock
        acquireServiceWakeLocks()
        AlarmReceiver.releaseHandoffWakeLock()

        val alarmId = intent.getStringExtra(AlarmManagerHelper.EXTRA_ALARM_ID)
            ?: intent.getStringExtra("alarm_id") ?: "default_alarm"
        val isTest = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_TEST, false)
        val isSnooze = intent.getBooleanExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, false)
        val deleteAfter = intent.getBooleanExtra("delete_after_ringing", false)

        val alarm = AlarmManagerHelper.getAlarmById(this, alarmId) ?: SmartAlarm(
            id = alarmId,
            label = intent.getStringExtra("alarm_label") ?: if (isTest) "Test Wake-Up Mission" else "Wake Up Mission",
            soundId = intent.getIntExtra("sound_id", 2),
            snoozeMinutes = intent.getIntExtra("snooze_minutes", 0),
            forceMaxVolume = intent.getBooleanExtra("force_max_volume", true),
            heavyVibration = intent.getBooleanExtra("heavy_vibration", true),
            aiVoiceAnnouncement = intent.getBooleanExtra("ai_voice", true)
        )
        activeAlarm = alarm

        // 2. Persist active ringing state synchronously in SharedPreferences
        val sp = getSharedPreferences("InstaGuardPrefs", Context.MODE_PRIVATE)
        sp.edit()
            .putString("active_ringing_alarm_id", alarm.id)
            .putBoolean("active_ringing_delete_after", alarm.deleteAfterRinging || deleteAfter)
            .putBoolean("alarm_currently_ringing", true)
            .putLong("last_actual_alarm_ring_epoch", System.currentTimeMillis())
            .commit()

        // 3. Notify AppBlockerService anti-cheat state
        AppBlockerService.setAlarmRingingState(this, isRinging = true, alarmId = alarm.id)

        // 4. Start Foreground Notification with Full Screen Intent & Dismiss Action Button
        createNotificationChannel()
        val notification = buildForegroundNotification(alarm, isTest, isSnooze)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }

        // 5. Guard against duplicate triggers for the same active alarm
        if (!isCurrentlyRinging || activeAlarmId != alarmId) {
            isCurrentlyRinging = true
            activeAlarmId = alarmId
            requestAlarmAudioFocus()

            // Start mathematical audio synthesizer directly in service (works reliably even with screen OFF)
            HardAlarmSoundEngine.startPlaying(
                context = applicationContext,
                soundId = alarm.soundId,
                forceMaxVolume = alarm.forceMaxVolume,
                loop = true
            )

            // Continuous vibration
            if (alarm.heavyVibration) {
                startHeavyVibration()
            }

            // Text-To-Speech announcement
            if (alarm.aiVoiceAnnouncement) {
                speakAlarmAnnouncement(alarm.label)
            }
        }

        // 6. Launch full-screen AlarmRingingActivity
        val ringingIntent = Intent(this, AlarmRingingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AlarmManagerHelper.EXTRA_ALARM_ID, alarm.id)
            putExtra("alarm_id", alarm.id)
            putExtra(AlarmManagerHelper.EXTRA_IS_TEST, isTest)
            putExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, isSnooze)
        }
        try {
            startActivity(ringingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return START_STICKY
    }

    private fun acquireServiceWakeLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (wakeLock == null || wakeLock?.isHeld != true) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "InstaGuard:AlarmServiceCpuWakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire(15 * 60_000L) // 15 minutes safety timeout
            }
        }

        // Temporarily wake screen brightness
        try {
            @Suppress("DEPRECATION")
            val screenLock = pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "InstaGuard:ScreenTurnOnWakeLock"
            )
            screenLock.acquire(120_000L)
        } catch (e: Exception) {
            try {
                @Suppress("DEPRECATION")
                val screenLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                    "InstaGuard:ScreenTurnOnWakeLockFallback"
                )
                screenLock.acquire(30_000L)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        }
    }

    private fun buildForegroundNotification(alarm: SmartAlarm, isTest: Boolean, isSnooze: Boolean): Notification {
        val fullScreenIntent = Intent(this, AlarmRingingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(AlarmManagerHelper.EXTRA_ALARM_ID, alarm.id)
            putExtra("alarm_id", alarm.id)
            putExtra(AlarmManagerHelper.EXTRA_IS_TEST, isTest)
            putExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, isSnooze)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            2026,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, AlarmRingingService::class.java).apply {
            action = ACTION_DISMISS_ALARM
            putExtra(AlarmManagerHelper.EXTRA_ALARM_ID, alarm.id)
            putExtra("alarm_id", alarm.id)
        }
        val dismissPendingIntent = PendingIntent.getService(
            this,
            2027,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm_tab)
            .setContentTitle("⏰ InstaGuard Alarm Ringing!")
            .setContentText("Alarm: ${alarm.label}. Tap to dismiss.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setContentIntent(fullScreenPendingIntent)
            .addAction(
                R.drawable.ic_stop_hand,
                "Dismiss / Stop Alarm",
                dismissPendingIntent
            )
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "InstaGuard Hardcore Alarm",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Full-screen foreground alarm alerts"
                setSound(null, null) // Audio is handled mathematically via AudioTrack
                enableVibration(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            }
            nm?.createNotificationChannel(channel)
        }
    }

    private fun initVibrator() {
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vm?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun startHeavyVibration() {
        val pattern = longArrayOf(0, 1000, 300, 1000, 300)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern, 0)
            vibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, 0)
        }
    }

    private fun requestAlarmAudioFocus() {
        try {
            if (audioManager == null) {
                audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* maintain priority */ }
                    .build()
                audioFocusRequest = focusRequest
                audioManager?.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                audioManager?.requestAudioFocus(
                    null,
                    AudioManager.STREAM_ALARM,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun abandonAlarmAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(applicationContext, this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setSpeechRate(0.95f)
            isTtsReady = true
            pendingSpeechText?.let { speakAlarmAnnouncement(it) }
        }
    }

    private fun speakAlarmAnnouncement(label: String) {
        val text = "Attention! Wake up now! It is time for $label!"
        if (!isTtsReady) {
            pendingSpeechText = label
            return
        }
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "alarm_service_announcement")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopRingingAndDismiss(alarmId: String?) {
        isCurrentlyRinging = false
        activeAlarmId = null

        // 1. Stop mathematical sound synthesizer
        HardAlarmSoundEngine.stopPlaying(this)

        // 2. Stop vibration & TTS
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Abandon audio focus
        abandonAlarmAudioFocus()

        // 4. Release WakeLocks safely
        wakeLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        wakeLock = null
        AlarmReceiver.releaseHandoffWakeLock()

        // 5. Update AppBlockerService anti-cheat state
        AppBlockerService.clearAlarmRingingState(this)

        // 6. Handle dismissal & repeat/auto-delete logic synchronously
        AlarmManagerHelper.handleAlarmDismissed(this, alarmId)

        // 7. Stop foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Immortal alarm lock: if alarm is currently ringing, re-launch AlarmRingingActivity immediately!
        if (AppBlockerService.isAlarmCurrentlyRinging) {
            val restartIntent = Intent(this, AlarmRingingActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try {
                startActivity(restartIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCurrentlyRinging = false
        activeAlarmId = null
        AppBlockerService.clearAlarmRingingState(this)
        HardAlarmSoundEngine.stopPlaying(this)
        try {
            vibrator?.cancel()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            tts?.stop()
            tts?.shutdown()
            tts = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        abandonAlarmAudioFocus()
        wakeLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        wakeLock = null
        AlarmReceiver.releaseHandoffWakeLock()
    }

    companion object {
        const val CHANNEL_ID = "instaguard_alarm_channel_v132"
        const val NOTIFICATION_ID = 9991
        const val ACTION_START_ALARM = "com.instaguard.app.ACTION_START_ALARM"
        const val ACTION_STOP_ALARM = "com.instaguard.app.ACTION_STOP_ALARM"
        const val ACTION_DISMISS_ALARM = "com.instaguard.app.ACTION_DISMISS_ALARM"

        @Volatile
        var isCurrentlyRinging = false

        @Volatile
        var activeAlarmId: String? = null

        fun startAlarm(
            context: Context,
            alarmId: String,
            isTest: Boolean = false,
            isSnooze: Boolean = false,
            deleteAfter: Boolean = false,
            soundId: Int = 2,
            forceMaxVolume: Boolean = true,
            heavyVibration: Boolean = true,
            aiVoice: Boolean = true
        ) {
            val intent = Intent(context, AlarmRingingService::class.java).apply {
                action = ACTION_START_ALARM
                putExtra(AlarmManagerHelper.EXTRA_ALARM_ID, alarmId)
                putExtra("alarm_id", alarmId)
                putExtra(AlarmManagerHelper.EXTRA_IS_TEST, isTest)
                putExtra(AlarmManagerHelper.EXTRA_IS_SNOOZE, isSnooze)
                putExtra("delete_after_ringing", deleteAfter)
                putExtra("sound_id", soundId)
                putExtra("force_max_volume", forceMaxVolume)
                putExtra("heavy_vibration", heavyVibration)
                putExtra("ai_voice", aiVoice)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun stopAlarmAfterSolved(context: Context, alarmId: String?) {
            isCurrentlyRinging = false
            activeAlarmId = null
            HardAlarmSoundEngine.stopPlaying(context)
            AppBlockerService.clearAlarmRingingState(context)
            AlarmManagerHelper.handleAlarmDismissed(context, alarmId)

            val intent = Intent(context, AlarmRingingService::class.java).apply {
                action = ACTION_STOP_ALARM
                putExtra(AlarmManagerHelper.EXTRA_ALARM_ID, alarmId)
                putExtra("alarm_id", alarmId)
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
