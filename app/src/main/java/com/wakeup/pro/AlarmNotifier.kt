package com.wakeup.pro

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

class AlarmNotifier(private val context: Context) {
    fun showAlarm(alarm: WakeAlarm, snoozeCount: Int = 0) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
            .putExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, snoozeCount)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            alarm.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("WakeUp Pro")
            .setContentText("${alarm.displayTime} - ${alarm.label.ifBlank { "Wake Up" }}")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(pendingIntent)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        NotificationManagerCompat.from(context).notify(alarm.id.hashCode(), notification)
    }

    fun cancel(alarmId: String) {
        NotificationManagerCompat.from(context).cancel(alarmId.hashCode())
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarm alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Full-screen alarm alerts for WakeUp Pro"
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "wake_up_pro_alarm_alerts"
    }
}
