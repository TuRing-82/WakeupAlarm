package com.wakeup.pro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

class AlarmScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun scheduleAll(alarms: List<WakeAlarm>) {
        alarms.forEach { alarm ->
            cancel(alarm.id)
            if (alarm.enabled) schedule(alarm)
        }
    }

    fun schedule(alarm: WakeAlarm) {
        if (!alarm.enabled) return
        val triggerAt = nextTriggerTime(alarm) ?: return
        setAlarmSafely(triggerAt, alarm.id, receiverIntent(alarm.id))
    }

    fun scheduleSnooze(alarm: WakeAlarm, delayMinutes: Int = 5) {
        val triggerAt = System.currentTimeMillis() + delayMinutes * 60_000L
        setAlarmSafely(triggerAt, alarm.id, receiverIntent(alarm.id, SNOOZE_REQUEST_OFFSET))
    }

    fun cancel(alarmId: String) {
        alarmManager.cancel(receiverIntent(alarmId))
        alarmManager.cancel(receiverIntent(alarmId, SNOOZE_REQUEST_OFFSET))
    }

    private fun receiverIntent(alarmId: String, requestOffset: Int = 0): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).putExtra(EXTRA_ALARM_ID, alarmId)
        return PendingIntent.getBroadcast(
            context,
            alarmId.hashCode() + requestOffset,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun launchIntent(alarmId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_ALARM_ID, alarmId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            alarmId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun setAlarmSafely(triggerAt: Long, alarmId: String, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()) {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAt, launchIntent(alarmId)),
                    pendingIntent
                )
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
        }
    }

    companion object {
        const val EXTRA_ALARM_ID = "alarm_id"
        private const val SNOOZE_REQUEST_OFFSET = 50_000

        fun nextTriggerTime(alarm: WakeAlarm, from: Calendar = Calendar.getInstance()): Long? {
            var best: Calendar? = null
            for (offset in 0..7) {
                val candidate = from.clone() as Calendar
                candidate.add(Calendar.DAY_OF_YEAR, offset)
                if (!alarm.repeatDays.contains(candidate.get(Calendar.DAY_OF_WEEK))) continue
                candidate.set(Calendar.HOUR_OF_DAY, alarm.hour)
                candidate.set(Calendar.MINUTE, alarm.minute)
                candidate.set(Calendar.SECOND, 0)
                candidate.set(Calendar.MILLISECOND, 0)
                if (candidate.timeInMillis <= from.timeInMillis) continue
                if (best == null || candidate.timeInMillis < best.timeInMillis) best = candidate
            }
            return best?.timeInMillis
        }
    }
}
