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
    val snoozeCounts: Map<String, Int> = emptyMap(),
    val totalMotionSteps: Int = 0,
    val motionCompletions: Int = 0,
    val motionStepsByAlarm: Map<String, Int> = emptyMap()
) {
    fun cleanWakeRate(): Int = if (totalRings <= 0) 0 else ((firstTryWins * 100f) / totalRings).toInt()
    fun averageMotionSteps(): Int = if (motionCompletions <= 0) 0 else totalMotionSteps / motionCompletions
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

    fun recordMotionCompletion(alarmId: String, steps: Int) {
        val safeSteps = steps.coerceAtLeast(0)
        mutate { current ->
            current.copy(
                totalMotionSteps = current.totalMotionSteps + safeSteps,
                motionCompletions = current.motionCompletions + 1,
                motionStepsByAlarm = current.motionStepsByAlarm.plusAmount(alarmId, safeSteps)
            )
        }
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
                snoozeCounts = json.optJSONObject("snoozeCounts").toCountMap(),
                totalMotionSteps = json.optInt("totalMotionSteps", 0),
                motionCompletions = json.optInt("motionCompletions", 0),
                motionStepsByAlarm = json.optJSONObject("motionStepsByAlarm").toCountMap()
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
            put("totalMotionSteps", snapshot.totalMotionSteps)
            put("motionCompletions", snapshot.motionCompletions)
            put("motionStepsByAlarm", JSONObject(snapshot.motionStepsByAlarm))
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

    private fun Map<String, Int>.plusAmount(key: String, amount: Int): Map<String, Int> =
        toMutableMap().apply { put(key, (getOrDefault(key, 0)) + amount) }

    companion object {
        private const val PREFS = "wake_up_pro_wrapped"
        private const val KEY_STATS = "stats"
    }
}
