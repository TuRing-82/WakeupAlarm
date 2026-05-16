package com.wakeup.pro

import android.content.Context
import org.json.JSONArray

class AlarmRepository(context: Context) {
    private val prefs = context.getSharedPreferences("wake_up_pro", Context.MODE_PRIVATE)

    fun getAlarms(): List<WakeAlarm> {
        val raw = prefs.getString(KEY_ALARMS, null) ?: return emptyList()
        val parsed = runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> alarmFromJson(array.getJSONObject(index)) }
        }.getOrElse { emptyList() }

        val userAlarms = parsed.filterNot { isLegacySeedAlarm(it) }
        if (userAlarms.size != parsed.size) saveAll(userAlarms)
        return userAlarms.sortedWith(compareBy<WakeAlarm> { it.hour }.thenBy { it.minute })
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

    private fun isLegacySeedAlarm(alarm: WakeAlarm): Boolean =
        (alarm.label == "Morning Workout" && alarm.type == AlarmType.WIFI && alarm.hour == 6 && alarm.minute == 0) ||
            (alarm.label == "Morning Gym" && alarm.type == AlarmType.MOTION && alarm.hour == 7 && alarm.minute == 30)

    private fun saveAll(alarms: List<WakeAlarm>) {
        val array = JSONArray()
        alarms.forEach { array.put(it.toJson()) }
        // Use a synchronous write because the UI immediately re-reads alarms after toggles/edits.
        prefs.edit().putString(KEY_ALARMS, array.toString()).commit()
    }

    companion object {
        private const val KEY_ALARMS = "alarms"
    }
}
