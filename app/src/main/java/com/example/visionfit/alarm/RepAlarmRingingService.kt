package com.example.visionfit.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import com.example.visionfit.R

class RepAlarmRingingService : Service() {
    private var ringtone: Ringtone? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "VisionFit:AlarmRingingWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // Stay awake for up to 10 mins
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val alarmId = intent?.getStringExtra(RepAlarmScheduler.EXTRA_ALARM_ID) ?: return START_NOT_STICKY
        val soundUriString = intent.getStringExtra(EXTRA_SOUND_URI)
        val snoozeEnabled = intent.getBooleanExtra(EXTRA_SNOOZE_ENABLED, true)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(EXTRA_BODY).orEmpty()
        val channelId = "rep_alarm_ringing"
        createChannel(channelId)

        val notification = buildNotification(channelId, alarmId, title, body, snoozeEnabled)
        
        // Using mediaPlayback type to resolve build incompatibility while keeping background priority
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }

        startForeground(NOTIFICATION_ID, notification, serviceType)
        playAlarm(soundUriString)
        return START_STICKY
    }

    override fun onDestroy() {
        ringtone?.stop()
        ringtone = null
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun playAlarm(soundUriString: String?) {
        ringtone?.stop()
        mediaPlayer?.stop()
        mediaPlayer?.release()
        mediaPlayer = null
        val uri = soundUriString?.let(Uri::parse)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) isLooping = true
            audioAttributes = attributes
            runCatching { play() }
        }
        val ringtoneIsPlaying = runCatching { ringtone?.isPlaying == true }.getOrDefault(false)
        if (!ringtoneIsPlaying && uri != null) {
            mediaPlayer = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setDataSource(this@RepAlarmRingingService, uri)
                    isLooping = true
                    prepare()
                    start()
                }
            }.getOrNull()?.also {
                it.setOnErrorListener { mp, _, _ ->
                    mp.release()
                    mediaPlayer = null
                    true
                }
            }
        }
        startVibration()
    }

    private fun startVibration() {
        vibrator?.cancel()
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            manager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        val pattern = longArrayOf(0L, 500L, 350L, 500L)
        val effect = VibrationEffect.createWaveform(pattern, 0)
        vibrator?.vibrate(effect)
    }

    private fun buildNotification(
        channelId: String,
        alarmId: String,
        title: String,
        body: String,
        snoozeEnabled: Boolean
    ): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            9101,
            AlarmRingingActivity.launchIntent(this, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val snoozeIntent = PendingIntent.getBroadcast(
            this,
            9102 + alarmId.hashCode(),
            Intent(this, RepAlarmActionReceiver::class.java).apply {
                action = ACTION_SNOOZE
                putExtra(RepAlarmScheduler.EXTRA_ALARM_ID, alarmId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title.ifBlank { "Rep alarm" })
            .setContentText(body.ifBlank { "Open app to complete reps and stop alarm." })
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(openIntent, true)
            .setContentIntent(openIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (snoozeEnabled) {
            builder.addAction(0, "Snooze 5m", snoozeIntent)
        }
        return builder.build()
    }

    private fun createChannel(channelId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            channelId,
            "Active rep alarms",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Ringing alarms that require workout completion."
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setBypassDnd(true)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.example.visionfit.alarm.START"
        const val ACTION_STOP = "com.example.visionfit.alarm.STOP"
        const val ACTION_SNOOZE = "com.example.visionfit.alarm.SNOOZE"
        const val EXTRA_SOUND_URI = "extra_sound_uri"
        const val EXTRA_SNOOZE_ENABLED = "extra_snooze_enabled"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_BODY = "extra_body"
        private const val NOTIFICATION_ID = 9100

        fun start(
            context: Context,
            alarmId: String,
            soundUri: String?,
            snoozeEnabled: Boolean,
            title: String,
            body: String
        ) {
            val intent = Intent(context, RepAlarmRingingService::class.java).apply {
                action = ACTION_START
                putExtra(RepAlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(EXTRA_SOUND_URI, soundUri)
                putExtra(EXTRA_SNOOZE_ENABLED, snoozeEnabled)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_BODY, body)
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RepAlarmRingingService::class.java))
        }
    }
}
