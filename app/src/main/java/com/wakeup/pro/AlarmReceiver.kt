package com.wakeup.pro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
        val snoozeCount = intent.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0)
        val repository = AlarmRepository(context)
        val alarm = repository.getAlarm(alarmId) ?: return

        if (alarm.repeatDays.isEmpty()) {
            repository.saveAlarm(alarm.copy(enabled = false))
        } else {
            AlarmScheduler(context).schedule(alarm)
        }
        AlarmNotifier(context).showAlarm(alarm, snoozeCount)

        val activityIntent = Intent(context, MainActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            .putExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, snoozeCount)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(activityIntent)
    }
}
