package com.wakeup.pro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            val repository = AlarmRepository(context)
            AlarmScheduler(context).scheduleAll(repository.getAlarms())
        }
    }
}
