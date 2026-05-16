package com.wakeup.pro

import android.content.Context
import org.json.JSONObject
import java.util.Locale

data class WakeWrappedSnapshot(
    val totalRings: Int = 0,
    val totalSnoozes: Int = 0,
    val firstTryWins: Int = 0,
    val lastRingAt: Long = 0L,
    val lastRingAlarmId: String? = null,
    val ringCounts: Map<String, Int> = emptyMap(),
    val snoozeCounts: Map<String, Int> = emptyMap()
) {
    fun cleanWakeRate(): Int = if (totalRings <= 0) 0 else ((firstTryWins * 100f) / totalRings).toInt()
}

class WakeWrappedStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun snapshot(): WakeWrappedSnapshot = parseSnapshot()

    fun recordRing(alarmId: String) {
        mutate { current ->
            current.copy(
                totalRings = current.totalRings + 1,
                lastRingAt = System.currentTimeMillis(),
                lastRingAlarmId = alarmId,
                ringCounts = current.ringCounts.plusCount(alarmId)
            )
        }
    }

    fun recordSnooze(alarmId: String) {
        mutate { current ->
            current.copy(
                totalSnoozes = current.totalSnoozes + 1,
                snoozeCounts = current.snoozeCounts.plusCount(alarmId)
            )
        }
    }

    fun recordFirstTryWin() {
        mutate { current -> current.copy(firstTryWins = current.firstTryWins + 1) }
    }

    private fun mutate(transform: (WakeWrappedSnapshot) -> WakeWrappedSnapshot) {
        save(transform(parseSnapshot()))
    }

    private fun parseSnapshot(): WakeWrappedSnapshot {
        val raw = prefs.getString(KEY_STATS, null) ?: return WakeWrappedSnapshot()
        return runCatching {
            val json = JSONObject(raw)
            WakeWrappedSnapshot(
                totalRings = json.optInt("totalRings", 0),
                totalSnoozes = json.optInt("totalSnoozes", 0),
                firstTryWins = json.optInt("firstTryWins", 0),
                lastRingAt = json.optLong("lastRingAt", 0L),
                lastRingAlarmId = json.optString("lastRingAlarmId", "").takeIf { it.isNotBlank() },
                ringCounts = json.optJSONObject("ringCounts").toCountMap(),
                snoozeCounts = json.optJSONObject("snoozeCounts").toCountMap()
            )
        }.getOrDefault(WakeWrappedSnapshot())
    }

    private fun save(snapshot: WakeWrappedSnapshot) {
        prefs.edit().putString(KEY_STATS, JSONObject().apply {
            put("totalRings", snapshot.totalRings)
            put("totalSnoozes", snapshot.totalSnoozes)
            put("firstTryWins", snapshot.firstTryWins)
            put("lastRingAt", snapshot.lastRingAt)
            put("lastRingAlarmId", snapshot.lastRingAlarmId ?: "")
            put("ringCounts", JSONObject(snapshot.ringCounts))
            put("snoozeCounts", JSONObject(snapshot.snoozeCounts))
        }.toString()).commit()
    }

    private fun JSONObject?.toCountMap(): Map<String, Int> {
        if (this == null) return emptyMap()
        val map = mutableMapOf<String, Int>()
        val iterator = keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            map[key] = optInt(key, 0)
        }
        return map
    }

    private fun Map<String, Int>.plusCount(key: String): Map<String, Int> =
        toMutableMap().apply { put(key, (getOrDefault(key, 0)) + 1) }

    companion object {
        private const val PREFS = "wake_up_pro_wrapped"
        private const val KEY_STATS = "stats"
    }
}
