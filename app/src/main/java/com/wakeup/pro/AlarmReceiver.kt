package com.wakeup.pro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: return
        val repository = AlarmRepository(context)
        val alarm = repository.getAlarm(alarmId) ?: return

        AlarmScheduler(context).schedule(alarm)
        AlarmNotifier(context).showAlarm(alarm)

        val activityIntent = Intent(context, MainActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        context.startActivity(activityIntent)
    }
}
