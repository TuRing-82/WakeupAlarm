package com.wakeup.pro

import org.json.JSONArray
import org.json.JSONObject

enum class AlarmType {
    SIMPLE,
    WIFI,
    MOTION
}

enum class Sensitivity {
    LOW,
    MEDIUM,
    HIGH
}

enum class MotionMode {
    WALK,
    SHAKE
}

data class AlarmConfig(
    val wifiLocationSet: Boolean = false,
    val wifiSensitivity: Sensitivity = Sensitivity.MEDIUM,
    val motionSteps: Int = 20,
    val motionSensitivity: Sensitivity = Sensitivity.MEDIUM,
    val motionMode: MotionMode = MotionMode.WALK,
    val snoozeMinutes: Int = 5,
    val snoozeRepeatCount: Int = 3
)

data class WakeAlarm(
    val id: String,
    val hour: Int,
    val minute: Int,
    val label: String,
    val type: AlarmType,
    val enabled: Boolean,
    val repeatDays: Set<Int>,
    val config: AlarmConfig = AlarmConfig()
) {
    val displayTime: String
        get() {
            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            val period = if (hour >= 12) "PM" else "AM"
            return "%02d:%02d %s".format(displayHour, minute, period)
        }

    val typeLabel: String
        get() = type.name.lowercase().replaceFirstChar { it.uppercase() }
}

fun WakeAlarm.toJson(): JSONObject = JSONObject()
    .put("id", id)
    .put("hour", hour)
    .put("minute", minute)
    .put("label", label)
    .put("type", type.name)
    .put("enabled", enabled)
    .put("repeatDays", JSONArray(repeatDays.sorted()))
    .put(
        "config",
        JSONObject()
            .put("wifiLocationSet", config.wifiLocationSet)
            .put("wifiSensitivity", config.wifiSensitivity.name)
            .put("motionSteps", config.motionSteps)
            .put("motionSensitivity", config.motionSensitivity.name)
            .put("motionMode", config.motionMode.name)
            .put("snoozeMinutes", config.snoozeMinutes)
            .put("snoozeRepeatCount", config.snoozeRepeatCount)
    )

fun alarmFromJson(json: JSONObject): WakeAlarm {
    val configJson = json.optJSONObject("config") ?: JSONObject()
    val days = mutableSetOf<Int>()
    if (json.has("repeatDays")) {
        val daysJson = json.optJSONArray("repeatDays") ?: JSONArray()
        for (index in 0 until daysJson.length()) {
            days.add(daysJson.optInt(index))
        }
    }

    return WakeAlarm(
        id = json.getString("id"),
        hour = json.getInt("hour"),
        minute = json.getInt("minute"),
        label = json.optString("label", "Wake Up"),
        type = runCatching { AlarmType.valueOf(json.optString("type")) }.getOrDefault(AlarmType.SIMPLE),
        enabled = json.optBoolean("enabled", true),
        repeatDays = if (json.has("repeatDays")) days else defaultRepeatDays(),
        config = AlarmConfig(
            wifiLocationSet = configJson.optBoolean("wifiLocationSet", false),
            wifiSensitivity = enumValue(configJson.optString("wifiSensitivity"), Sensitivity.MEDIUM),
            motionSteps = configJson.optInt("motionSteps", 20).coerceAtLeast(1),
            motionSensitivity = enumValue(configJson.optString("motionSensitivity"), Sensitivity.MEDIUM),
            motionMode = enumValue(configJson.optString("motionMode"), MotionMode.WALK),
            snoozeMinutes = configJson.optInt("snoozeMinutes", 5).coerceAtLeast(1),
            snoozeRepeatCount = configJson.optInt("snoozeRepeatCount", 3).coerceAtLeast(0)
        )
    )
}

inline fun <reified T : Enum<T>> enumValue(raw: String, fallback: T): T =
    runCatching { enumValueOf<T>(raw) }.getOrDefault(fallback)

fun defaultRepeatDays(): Set<Int> = setOf(
    java.util.Calendar.MONDAY,
    java.util.Calendar.TUESDAY,
    java.util.Calendar.WEDNESDAY,
    java.util.Calendar.THURSDAY,
    java.util.Calendar.FRIDAY
)

fun allRepeatDays(): Set<Int> = setOf(
    java.util.Calendar.MONDAY,
    java.util.Calendar.TUESDAY,
    java.util.Calendar.WEDNESDAY,
    java.util.Calendar.THURSDAY,
    java.util.Calendar.FRIDAY,
    java.util.Calendar.SATURDAY,
    java.util.Calendar.SUNDAY
)
