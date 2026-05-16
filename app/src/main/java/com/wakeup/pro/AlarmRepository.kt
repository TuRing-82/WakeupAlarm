package com.wakeup.pro

import android.content.Context
import org.json.JSONArray
import java.util.UUID

class AlarmRepository(context: Context) {
    private val prefs = context.getSharedPreferences("wake_up_pro", Context.MODE_PRIVATE)

    fun getAlarms(): List<WakeAlarm> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return seedAlarms()
        val parsed = runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> alarmFromJson(array.getJSONObject(index)) }
        }.getOrElse { seedAlarms() }

        return parsed.sortedWith(compareBy<WakeAlarm> { it.hour }.thenBy { it.minute })
    }

    fun getAlarm(id: String): WakeAlarm? = getAlarms().firstOrNull { it.id == id }

    fun saveAlarm(alarm: WakeAlarm) {
        val alarms = getAlarms().toMutableList()
        val index = alarms.indexOfFirst { it.id == alarm.id }
        if (index >= 0) {
            alarms[index] = alarm
        } else {
            alarms.add(alarm)
        }
        saveAll(alarms)
    }

    fun updateAlarms(alarms: List<WakeAlarm>) = saveAll(alarms)

    private fun saveAll(alarms: List<WakeAlarm>) {
        val array = JSONArray()
        alarms.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_ALARMS, array.toString()).apply()
    }

    private fun seedAlarms(): List<WakeAlarm> {
        val alarms = listOf(
            WakeAlarm(
                id = UUID.randomUUID().toString(),
                hour = 6,
                minute = 0,
                label = "Morning Workout",
                type = AlarmType.WIFI,
                enabled = true,
                repeatDays = defaultRepeatDays(),
                config = AlarmConfig(wifiLocationSet = true)
            ),
            WakeAlarm(
                id = UUID.randomUUID().toString(),
                hour = 7,
                minute = 30,
                label = "Morning Gym",
                type = AlarmType.MOTION,
                enabled = true,
                repeatDays = defaultRepeatDays(),
                config = AlarmConfig(motionSteps = 20, motionSensitivity = Sensitivity.MEDIUM)
            )
        )
        saveAll(alarms)
        return alarms
    }

    companion object {
        private const val KEY_ALARMS = "alarms"
    }
}
