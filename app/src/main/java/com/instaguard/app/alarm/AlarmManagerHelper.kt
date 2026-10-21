package com.instaguard.app.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.instaguard.app.service.AppBlockerService
import com.instaguard.app.ui.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Data Model for InstaGuard Pro Hardcore Smart Alarm.
 */
data class SmartAlarm(
    val id: String = UUID.randomUUID().toString(),
    var hour: Int = 6,
    var minute: Int = 30,
    var enabled: Boolean = true,
    var label: String = "Wake Up Mission",
    var repeatDays: Set<Int> = emptySet(), // 1 = Mon, 2 = Tue, 3 = Wed, 4 = Thu, 5 = Fri, 6 = Sat, 7 = Sun; empty = One-Time
    var deleteAfterRinging: Boolean = false,
    var soundId: Int = 2, // Default: ⚡ Extreme 3000Hz Piezo Buzzer
    var snoozeMinutes: Int = 0, // Default: Off (No Snooze)
    var dismissMissionType: Int = 1, // Default: 🧠 5 Hard Math Questions (Random)
    var forceMaxVolume: Boolean = true,
    var heavyVibration: Boolean = true,
    var aiVoiceAnnouncement: Boolean = true
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("hour", hour)
        put("minute", minute)
        put("enabled", enabled)
        put("label", label)
        val daysArray = JSONArray()
        repeatDays.forEach { daysArray.put(it) }
        put("repeatDays", daysArray)
        put("deleteAfterRinging", deleteAfterRinging)
        put("soundId", soundId)
        put("snoozeMinutes", snoozeMinutes)
        put("dismissMissionType", dismissMissionType)
        put("forceMaxVolume", forceMaxVolume)
        put("heavyVibration", heavyVibration)
        put("aiVoiceAnnouncement", aiVoiceAnnouncement)
    }

    companion object {
        fun fromJsonObject(json: JSONObject): SmartAlarm {
            val daysSet = mutableSetOf<Int>()
            val daysArray = json.optJSONArray("repeatDays")
            if (daysArray != null) {
                for (i in 0 until daysArray.length()) {
                    daysSet.add(daysArray.getInt(i))
                }
            }
            return SmartAlarm(
                id = json.optString("id", UUID.randomUUID().toString()),
                hour = json.optInt("hour", 6),
                minute = json.optInt("minute", 30),
                enabled = json.optBoolean("enabled", true),
                label = json.optString("label", "Wake Up Mission"),
                repeatDays = daysSet,
                deleteAfterRinging = json.optBoolean("deleteAfterRinging", false),
                soundId = json.optInt("soundId", 2),
                snoozeMinutes = json.optInt("snoozeMinutes", 0),
                dismissMissionType = json.optInt("dismissMissionType", 1),
                forceMaxVolume = json.optBoolean("forceMaxVolume", true),
                heavyVibration = json.optBoolean("heavyVibration", true),
                aiVoiceAnnouncement = json.optBoolean("aiVoiceAnnouncement", true)
            )
        }
    }
}

/**
 * Manages alarm persistence, exact system scheduling via AlarmManager.setAlarmClock,
 * calculation of trigger times, and countdown formatting.
 */
object AlarmManagerHelper {

    const val ACTION_TRIGGER_ALARM = "com.instaguard.app.ACTION_TRIGGER_ALARM"
    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_IS_TEST = "extra_is_test"
    const val EXTRA_IS_SNOOZE = "extra_is_snooze"

    private const val PREFS_NAME = "instaguard_smart_alarms_prefs"
    private const val KEY_ALARMS_JSON = "instaguard_smart_alarms_json"

    private fun getPrefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun loadAlarms(context: Context): MutableList<SmartAlarm> {
        val jsonStr = getPrefs(context).getString(KEY_ALARMS_JSON, null)
        val list = mutableListOf<SmartAlarm>()
        if (jsonStr.isNullOrBlank()) {
            // Seed a sensible default alarm for new users
            val defaultAlarm = SmartAlarm(
                hour = 6,
                minute = 30,
                enabled = false,
                label = "Morning Focus & Workout",
                soundId = 2,
                repeatDays = setOf(1, 2, 3, 4, 5),
                deleteAfterRinging = false,
                snoozeMinutes = 0,
                dismissMissionType = 1
            )
            list.add(defaultAlarm)
            saveAlarms(context, list)
            return list
        }

        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                list.add(SmartAlarm.fromJsonObject(array.getJSONObject(i)))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    @Synchronized
    fun saveAlarms(context: Context, alarms: List<SmartAlarm>) {
        val array = JSONArray()
        alarms.forEach { array.put(it.toJsonObject()) }
        getPrefs(context).edit().putString(KEY_ALARMS_JSON, array.toString()).apply()
    }

    fun getAlarmById(context: Context, id: String): SmartAlarm? {
        return loadAlarms(context).find { it.id == id }
    }

    fun saveAlarm(context: Context, alarm: SmartAlarm) {
        val list = loadAlarms(context)
        val index = list.indexOfFirst { it.id == alarm.id }
        if (index >= 0) {
            list[index] = alarm
        } else {
            list.add(0, alarm)
        }
        saveAlarms(context, list)

        if (alarm.enabled) {
            scheduleAlarm(context, alarm)
        } else {
            cancelAlarm(context, alarm.id)
        }
    }

    fun deleteAlarm(context: Context, alarmId: String) {
        val list = loadAlarms(context)
        list.removeAll { it.id == alarmId }
        saveAlarms(context, list)
        cancelAlarm(context, alarmId)
    }

    /**
     * Calculates the exact next millisecond epoch trigger time for an alarm.
     */
    fun calculateNextTriggerMs(alarm: SmartAlarm): Long {
        val now = Calendar.getInstance()
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        if (alarm.repeatDays.isEmpty()) {
            // One-time alarm
            if (target.timeInMillis <= now.timeInMillis) {
                target.add(Calendar.DAY_OF_YEAR, 1)
            }
            return target.timeInMillis
        }

        // Repeating on specific days of week (1=Mon..7=Sun)
        for (dayOffset in 0..7) {
            val checkCal = Calendar.getInstance().apply {
                timeInMillis = target.timeInMillis + dayOffset * 86_400_000L
            }
            val mappedDay = when (checkCal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY -> 1
                Calendar.TUESDAY -> 2
                Calendar.WEDNESDAY -> 3
                Calendar.THURSDAY -> 4
                Calendar.FRIDAY -> 5
                Calendar.SATURDAY -> 6
                Calendar.SUNDAY -> 7
                else -> 1
            }

            if (alarm.repeatDays.contains(mappedDay)) {
                if (checkCal.timeInMillis > now.timeInMillis) {
                    return checkCal.timeInMillis
                }
            }
        }

        // Fallback
        if (target.timeInMillis <= now.timeInMillis) {
            target.add(Calendar.DAY_OF_YEAR, 1)
        }
        return target.timeInMillis
    }

    /**
     * Schedules the alarm using AlarmManager.setAlarmClock for 100% exact delivery in Doze mode.
     */
    fun scheduleAlarm(context: Context, alarm: SmartAlarm) {
        val triggerMs = calculateNextTriggerMs(alarm)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val triggerIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            data = android.net.Uri.parse("instaguard://alarm/${alarm.id}")
            putExtra(EXTRA_ALARM_ID, alarm.id)
            putExtra("alarm_id", alarm.id)
            putExtra("delete_after_ringing", alarm.deleteAfterRinging)
            putExtra(EXTRA_IS_TEST, false)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java).apply {
            data = android.net.Uri.parse("instaguard://show/${alarm.id}")
        }
        val showPendingIntent = PendingIntent.getActivity(
            context,
            alarm.id.hashCode() + 1,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val clockInfo = AlarmManager.AlarmClockInfo(triggerMs, showPendingIntent)
            alarmManager.setAlarmClock(clockInfo, pendingIntent)
        } catch (e: SecurityException) {
            // In case exact alarm permission is missing on Android 12+
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            } catch (ex: Exception) {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Schedules a temporary snooze trigger without changing master repeat settings.
     */
    fun scheduleSnooze(context: Context, alarm: SmartAlarm, snoozeMinutes: Int) {
        val triggerMs = System.currentTimeMillis() + (snoozeMinutes * 60_000L)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        val triggerIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            data = android.net.Uri.parse("instaguard://alarm/${alarm.id}/snooze")
            putExtra(EXTRA_ALARM_ID, alarm.id)
            putExtra("alarm_id", alarm.id)
            putExtra("delete_after_ringing", alarm.deleteAfterRinging)
            putExtra(EXTRA_IS_SNOOZE, true)
            putExtra(EXTRA_IS_TEST, false)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarm.id.hashCode(),
            triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val showIntent = Intent(context, MainActivity::class.java).apply {
            data = android.net.Uri.parse("instaguard://show/${alarm.id}/snooze")
        }
        val showPendingIntent = PendingIntent.getActivity(
            context,
            alarm.id.hashCode() + 1,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            val clockInfo = AlarmManager.AlarmClockInfo(triggerMs, showPendingIntent)
            alarmManager.setAlarmClock(clockInfo, pendingIntent)
        } catch (e: Exception) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
        }
    }

    fun cancelAlarm(context: Context, alarmId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = ACTION_TRIGGER_ALARM
            data = android.net.Uri.parse("instaguard://alarm/$alarmId")
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra("alarm_id", alarmId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            triggerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Handles alarm dismissal, auto-delete, or rescheduling next repeat occurrence.
     * Synchronously persists changes to SharedPreferences and broadcasts update.
     */
    fun handleAlarmDismissed(context: Context, alarmId: String?) {
        val prefs = getPrefs(context)
        val targetId = if (!alarmId.isNullOrBlank()) alarmId else prefs.getString("active_ringing_alarm_id", null) ?: ""
        val list = loadAlarms(context)
        val alarm = list.find { it.id == targetId }
        val deleteAfterFromPrefs = prefs.getBoolean("active_ringing_delete_after", false)

        val shouldDelete = (alarm?.deleteAfterRinging == true) || deleteAfterFromPrefs

        if (shouldDelete) {
            cancelAlarm(context, targetId)
            list.removeAll { it.id == targetId }
            val array = JSONArray()
            list.forEach { array.put(it.toJsonObject()) }
            prefs.edit().putString(KEY_ALARMS_JSON, array.toString()).commit()
        } else if (alarm != null) {
            if (alarm.repeatDays.isEmpty()) {
                alarm.enabled = false
                val array = JSONArray()
                list.forEach { array.put(it.toJsonObject()) }
                prefs.edit().putString(KEY_ALARMS_JSON, array.toString()).commit()
                cancelAlarm(context, targetId)
            } else {
                scheduleAlarm(context, alarm)
            }
        }

        // Clear active ringing extras from SharedPreferences
        prefs.edit()
            .remove("active_ringing_alarm_id")
            .remove("active_ringing_delete_after")
            .commit()

        AppBlockerService.clearAlarmRingingState(context)

        try {
            context.sendBroadcast(Intent("com.instaguard.app.ACTION_ALARM_LIST_CHANGED"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun rescheduleAllEnabledAlarms(context: Context) {
        val alarms = loadAlarms(context)
        for (alarm in alarms) {
            if (alarm.enabled) {
                scheduleAlarm(context, alarm)
            }
        }
    }

    fun formatNextRingCountdown(alarm: SmartAlarm): String {
        if (!alarm.enabled) return "Disabled"
        val triggerMs = calculateNextTriggerMs(alarm)
        val diffMs = triggerMs - System.currentTimeMillis()
        if (diffMs <= 0) return "Rings now"
        val hours = diffMs / 3_600_000L
        val minutes = (diffMs % 3_600_000L) / 60_000L
        return if (hours > 0) {
            "Rings in ${hours}h ${minutes}m"
        } else {
            "Rings in ${minutes}m"
        }
    }

    fun formatTimeAmPm(hour: Int, minute: Int): String {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
        }
        return java.text.SimpleDateFormat("hh:mm a", Locale.getDefault()).format(cal.time).uppercase(Locale.getDefault())
    }

    fun formatRepeatDays(days: Set<Int>): String {
        if (days.isEmpty()) return "One-Time Alarm"
        if (days.size == 7) return "Everyday"
        if (days == setOf(1, 2, 3, 4, 5)) return "Weekdays (Mon–Fri)"
        if (days == setOf(6, 7)) return "Weekends (Sat–Sun)"

        val dayNames = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val activeNames = days.sorted().map { dayNames[it - 1] }
        return activeNames.joinToString(", ")
    }
}
