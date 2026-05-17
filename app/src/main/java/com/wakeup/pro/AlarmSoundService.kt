package com.wakeup.pro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class AlarmSoundService : Service() {
    private var alarmPlayer: MediaPlayer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val alarmId = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
        val snoozeCount = intent?.getIntExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, 0) ?: 0
        val alarm = alarmId?.let { AlarmRepository(this).getAlarm(it) }
        if (alarm == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(NOTIFICATION_ID, ringingNotification(alarm, snoozeCount))
        playAlarmSound(alarm)
        return START_STICKY
    }

    override fun onDestroy() {
        stopAlarmAudio()
        super.onDestroy()
    }

    private fun playAlarmSound(alarm: WakeAlarm) {
        stopAlarmAudio()
        alarmPlayer = if (alarm.config.ringtoneSource == RINGTONE_APP) {
            MediaPlayer.create(this, appRingtoneRes(alarm.config.appRingtone))
        } else {
            val uri = alarm.config.ringtoneUri
                .takeIf { alarm.config.ringtoneSource == RINGTONE_DEVICE_PICKED && it.isNotBlank() }
                ?.let { Uri.parse(it) }
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
                ?: android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            mediaPlayerFromUri(uri) ?: MediaPlayer.create(this, R.raw.wake_gradual_rise)
        }?.apply {
            isLooping = true
            setOnCompletionListener {
                runCatching { seekTo(0); start() }
            }
            start()
        }
    }

    private fun stopAlarmAudio() {
        alarmPlayer?.stop()
        alarmPlayer?.release()
        alarmPlayer = null
    }

    private fun mediaPlayerFromUri(uri: Uri?): MediaPlayer? {
        if (uri == null) return null
        return runCatching {
            MediaPlayer().apply {
                setAudioAttributes(alarmAudioAttributes())
                setDataSource(this@AlarmSoundService, uri)
                prepare()
            }
        }.getOrNull()
    }

    private fun ringingNotification(alarm: WakeAlarm, snoozeCount: Int): Notification {
        ensureChannel()
        val intent = Intent(this, MainActivity::class.java)
            .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
            .putExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, snoozeCount)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            this,
            alarm.id.hashCode() + SERVICE_NOTIFICATION_OFFSET,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle("WakeUp Pro alarm ringing")
            .setContentText("${alarm.displayTime} - ${alarm.label.ifBlank { "Wake Up" }}")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarm audio",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Keeps WakeUp Pro alarm audio playing until stopped or snoozed"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val ACTION_START = "com.wakeup.pro.action.START_ALARM_SOUND"
        private const val ACTION_STOP = "com.wakeup.pro.action.STOP_ALARM_SOUND"
        private const val CHANNEL_ID = "wake_up_pro_alarm_audio"
        private const val NOTIFICATION_ID = 7001
        private const val SERVICE_NOTIFICATION_OFFSET = 70_000
        private const val RINGTONE_DEVICE_PICKED = "DEVICE_PICKED"
        private const val RINGTONE_APP = "APP"

        fun start(context: Context, alarmId: String, snoozeCount: Int) {
            val intent = Intent(context, AlarmSoundService::class.java)
                .setAction(ACTION_START)
                .putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                .putExtra(AlarmScheduler.EXTRA_SNOOZE_COUNT, snoozeCount)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(Intent(context, AlarmSoundService::class.java).setAction(ACTION_STOP))
        }

        fun alarmAudioAttributes(): AudioAttributes =
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

        fun appRingtoneRes(id: String): Int = when (id) {
            "wake_morning_chime" -> R.raw.wake_morning_chime
            "wake_bright_pulse" -> R.raw.wake_bright_pulse
            "wake_gentle_ascend" -> R.raw.wake_gentle_ascend
            "wake_focus_bells" -> R.raw.wake_focus_bells
            else -> R.raw.wake_gradual_rise
        }
    }
}
