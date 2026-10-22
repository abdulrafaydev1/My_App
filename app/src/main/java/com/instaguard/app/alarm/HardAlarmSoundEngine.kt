package com.instaguard.app.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import kotlin.math.PI
import kotlin.math.sin

/**
 * The 12 selectable Hard Alarm Sounds synthesized mathematically at runtime.
 */
enum class HardAlarmSound(val id: Int, val title: String) {
    NUCLEAR_SIREN(0, "🚨 Nuclear Air-Raid Siren (Extreme)"),
    SUBMARINE_KLAXON(1, "🛳️ Submarine Red-Alert Klaxon"),
    PIEZO_BUZZER(2, "⚡ Extreme 3000Hz Piezo Buzzer"),
    CYBER_POLICE_SIREN(3, "🚓 Cyber Police Dual-Tone Siren"),
    INDUSTRIAL_FIRE_BELL(4, "🔔 Industrial Fire Bell Strobe"),
    MILITARY_BUGLE_HORN(5, "🎺 Military Reveille Wake-Up Horn"),
    BIOHAZARD_HORN(6, "☣️ Biohazard Warning Horn"),
    EARTHQUAKE_SONAR(7, "📡 Earthquake Sonar Ping Blaster"),
    TRAIN_BASS_HORN(8, "📢 Heavy Freight Train triple-Chord Horn"),
    HYPER_DIGITAL_BEEP(9, "💻 Hyper-Speed Digital Piercer"),
    SOS_MORSE_BLASTER(10, "🆘 SOS Morse Code Piercer (... --- ...)"),
    SYSTEM_MAX_ALARM_PLUS_VOICE(11, "🗣️ Loudest System Alarm + AI Voice Loop");

    companion object {
        fun fromId(id: Int): HardAlarmSound = entries.find { it.id == id } ?: NUCLEAR_SIREN
    }
}

/**
 * Hardcore Smart Alarm Synthesizer Engine.
 * Generates 12 piercing mathematical audio waveforms in real-time using AudioTrack
 * with zero external MP3/WAV dependencies for 100% offline reliability.
 */
object HardAlarmSoundEngine {

    private const val SAMPLE_RATE = 44100
    private const val BUFFER_SIZE_SAMPLES = 2048

    @Volatile
    private var isPlaying = false
    @Volatile
    private var currentPlayingSoundId: Int = -1
    private var audioWakeLock: PowerManager.WakeLock? = null
    private var playbackThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var fallbackMediaPlayer: MediaPlayer? = null
    private var previousVolume: Int = -1
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previewStopRunnable: Runnable? = null

    @Synchronized
    fun startPlaying(context: Context, soundId: Int, forceMaxVolume: Boolean, loop: Boolean = true) {
        if (isPlaying && currentPlayingSoundId == soundId) {
            // Keep existing audio stream playing smoothly without interrupting it
            return
        }
        stopPlaying(context)

        // Acquire internal audio partial wake lock to keep synthesizer running on locked/closed screen
        try {
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (audioWakeLock == null || audioWakeLock?.isHeld != true) {
                audioWakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "InstaGuard:AudioEngineWakeLock")?.apply {
                    setReferenceCounted(false)
                    acquire(15 * 60_000L) // 15 mins safety timeout
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (forceMaxVolume && audioManager != null) {
            try {
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, max, 0)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val minBufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(BUFFER_SIZE_SAMPLES * 2)

        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
            .build()

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()

        var track: AudioTrack? = null
        try {
            track = AudioTrack(
                attributes,
                format,
                minBufSize,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            startFallbackMediaPlayer(context, loop)
            return
        }

        audioTrack = track
        isPlaying = true
        currentPlayingSoundId = soundId

        playbackThread = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                track.play()
            } catch (e: Exception) {
                e.printStackTrace()
                mainHandler.post {
                    startFallbackMediaPlayer(context, loop)
                }
                return@Thread
            }

            val buffer = ShortArray(BUFFER_SIZE_SAMPLES)
            var sampleIndex: Long = 0
            val sound = HardAlarmSound.fromId(soundId)

            while (isPlaying) {
                for (i in buffer.indices) {
                    val sample = generateSample(sound, sampleIndex)
                    buffer[i] = (sample * Short.MAX_VALUE * 0.95).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    sampleIndex++
                }

                val written = track.write(buffer, 0, buffer.size)
                if (written < 0) break

                if (!loop && sampleIndex >= SAMPLE_RATE * 3.5) {
                    break
                }
            }

            try {
                track.stop()
                track.release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }, "HardAlarmSynthThread").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Preview sound for testing inside Alarm Editor or Tab. Automatically stops after durationMs.
     */
    @Synchronized
    fun previewSound(context: Context, soundId: Int, durationMs: Long = 3500L) {
        previewStopRunnable?.let { mainHandler.removeCallbacks(it) }
        startPlaying(context, soundId, forceMaxVolume = false, loop = true)

        val runnable = Runnable {
            stopPlaying(context)
        }
        previewStopRunnable = runnable
        mainHandler.postDelayed(runnable, durationMs)
    }

    @Synchronized
    fun stopPlaying(context: Context? = null) {
        previewStopRunnable?.let {
            mainHandler.removeCallbacks(it)
            previewStopRunnable = null
        }

        isPlaying = false
        currentPlayingSoundId = -1
        playbackThread?.interrupt()
        playbackThread = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioTrack = null

        stopFallbackMediaPlayer()

        try {
            if (audioWakeLock?.isHeld == true) {
                audioWakeLock?.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        audioWakeLock = null

        // Restore alarm volume if altered
        if (previousVolume >= 0 && context != null) {
            try {
                val audioManager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.setStreamVolume(AudioManager.STREAM_ALARM, previousVolume, 0)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            previousVolume = -1
        }
    }

    private fun startFallbackMediaPlayer(context: Context, loop: Boolean) {
        try {
            stopFallbackMediaPlayer()
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: Settings.System.DEFAULT_ALARM_ALERT_URI

            fallbackMediaPlayer = MediaPlayer().apply {
                setDataSource(context.applicationContext, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = loop
                prepare()
                start()
            }
            isPlaying = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun stopFallbackMediaPlayer() {
        try {
            fallbackMediaPlayer?.stop()
            fallbackMediaPlayer?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        fallbackMediaPlayer = null
    }

    fun isCurrentlyPlaying(): Boolean = isPlaying

    /**
     * Synthesizes audio samples in range [-1.0 .. 1.0] for the 12 Hard Alarm sounds.
     */
    private fun generateSample(sound: HardAlarmSound, index: Long): Double {
        val t = index.toDouble() / SAMPLE_RATE

        return when (sound) {
            HardAlarmSound.NUCLEAR_SIREN -> {
                // Sweeping 450Hz–1450Hz exponential air raid siren
                val period = 2.4
                val phaseInCycle = (t % period) / period
                val freq = if (phaseInCycle < 0.5) {
                    450.0 + (1450.0 - 450.0) * (phaseInCycle * 2.0)
                } else {
                    1450.0 - (1450.0 - 450.0) * ((phaseInCycle - 0.5) * 2.0)
                }
                val base = sin(2.0 * PI * freq * t)
                val thirdHarmonic = sin(6.0 * PI * freq * t) * 0.35
                (base + thirdHarmonic).coerceIn(-1.0, 1.0)
            }

            HardAlarmSound.SUBMARINE_KLAXON -> {
                // Dual-harmonic 680Hz / 920Hz submarine dive horn
                val period = 1.1
                val onTime = 0.65
                val active = (t % period) < onTime
                if (!active) return 0.0
                val h1 = sin(2.0 * PI * 680.0 * t)
                val h2 = sin(2.0 * PI * 920.0 * t) * 0.75
                ((h1 + h2) * 0.65).coerceIn(-1.0, 1.0)
            }

            HardAlarmSound.PIEZO_BUZZER -> {
                // Piercing 3000Hz square-wave buzzer bursts (75ms ON / 35ms OFF)
                val period = 0.110
                val on = (t % period) < 0.075
                if (!on) return 0.0
                val sine = sin(2.0 * PI * 3000.0 * t)
                if (sine >= 0.0) 0.85 else -0.85
            }

            HardAlarmSound.CYBER_POLICE_SIREN -> {
                // Dual-tone alternating 750Hz (200ms) / 1500Hz (200ms) with screeching yelp
                val cycle = (t % 0.40) < 0.20
                val freq = if (cycle) 780.0 else 1560.0
                val s = sin(2.0 * PI * freq * t)
                val square = if (s >= 0) 0.5 else -0.5
                ((s * 0.5) + square).coerceIn(-1.0, 1.0)
            }

            HardAlarmSound.INDUSTRIAL_FIRE_BELL -> {
                // Metallic 1200Hz + 2400Hz pulse ringing at 14 Hz
                val ringMod = sin(2.0 * PI * 14.0 * t)
                val active = ringMod > 0.0
                if (!active) return 0.0
                val b1 = sin(2.0 * PI * 1200.0 * t)
                val b2 = sin(2.0 * PI * 2400.0 * t) * 0.45
                ((b1 + b2) * ringMod).coerceIn(-1.0, 1.0)
            }

            HardAlarmSound.MILITARY_BUGLE_HORN -> {
                // Brass reveille wake-up bugle motive: C5 (523Hz), E5 (659Hz), G5 (784Hz), C6 (1046Hz)
                val notes = doubleArrayOf(523.25, 659.25, 783.99, 1046.50)
                val noteIndex = ((t * 4.0).toInt()) % notes.size
                val freq = notes[noteIndex]
                // Sawtooth brass wave
                val saw = (2.0 * ((t * freq) % 1.0)) - 1.0
                (saw * 0.75).coerceIn(-1.0, 1.0)
            }

            HardAlarmSound.BIOHAZARD_HORN -> {
                // Deep saw-wave industrial horn sweeping 320Hz–880Hz
                val cycle = (t % 1.5) / 1.5
                val freq = 320.0 + (880.0 - 320.0) * cycle
                val saw = (2.0 * ((t * freq) % 1.0)) - 1.0
                (saw * 0.85).coerceIn(-1.0, 1.0)
            }

            HardAlarmSound.EARTHQUAKE_SONAR -> {
                // Resonant 1800Hz sonar ping with rapid exponential decay every 0.6s
                val cycleT = t % 0.6
                val decay = Math.exp(-cycleT * 7.0)
                val s = sin(2.0 * PI * 1800.0 * t)
                (s * decay).coerceIn(-1.0, 1.0)
            }

            HardAlarmSound.TRAIN_BASS_HORN -> {
                // Heavy locomotive air horn triple chord: 311Hz + 370Hz + 440Hz
                val h1 = sin(2.0 * PI * 311.13 * t)
                val h2 = sin(2.0 * PI * 369.99 * t) * 0.8
                val h3 = sin(2.0 * PI * 440.00 * t) * 0.7
                ((h1 + h2 + h3) * 0.45).coerceIn(-1.0, 1.0)
            }

            HardAlarmSound.HYPER_DIGITAL_BEEP -> {
                // 8 fast staccato beeps per second at 2200Hz
                val beepPeriod = 0.125
                val on = (t % beepPeriod) < 0.065
                if (!on) return 0.0
                val s = sin(2.0 * PI * 2200.0 * t)
                if (s >= 0) 0.8 else -0.8
            }

            HardAlarmSound.SOS_MORSE_BLASTER -> {
                // SOS pattern: 3 dots, 3 dashes, 3 dots at 1600Hz
                val dot = 0.10
                val dash = 0.30
                val gap = 0.10
                val letterGap = 0.30
                val cycleLen = (3 * (dot + gap)) + (3 * (dash + gap)) + (3 * (dot + gap)) + 1.0
                val localT = t % cycleLen

                var cursor = 0.0
                var isOn = false
                // S (...)
                for (i in 0..2) {
                    if (localT >= cursor && localT < cursor + dot) { isOn = true; break }
                    cursor += dot + gap
                }
                cursor += letterGap
                // O (---)
                if (!isOn) {
                    for (i in 0..2) {
                        if (localT >= cursor && localT < cursor + dash) { isOn = true; break }
                        cursor += dash + gap
                    }
                }
                cursor += letterGap
                // S (...)
                if (!isOn) {
                    for (i in 0..2) {
                        if (localT >= cursor && localT < cursor + dot) { isOn = true; break }
                        cursor += dot + gap
                    }
                }

                if (!isOn) 0.0 else sin(2.0 * PI * 1600.0 * t) * 0.9
            }

            HardAlarmSound.SYSTEM_MAX_ALARM_PLUS_VOICE -> {
                // Alternating high-power 900Hz / 1300Hz pulse
                val freq = if ((t % 0.3) < 0.15) 900.0 else 1350.0
                val s = sin(2.0 * PI * freq * t)
                (s * 0.95).coerceIn(-1.0, 1.0)
            }
        }
    }
}
