package com.example.visionfit.usage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.visionfit.data.SettingsStore
import com.example.visionfit.model.AppBlockMode
import com.example.visionfit.util.isUsageAccessGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val CREDIT_CONSUME_INTERVAL_MS = 1000L
private const val NOTIFICATION_CHANNEL_ID = "visionfit_credits"
private const val NOTIFICATION_ID = 1001

class UsageTrackingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val usageStatsManager by lazy {
        getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }
    private var settingsStore: SettingsStore? = null
    private var appRules: Map<String, AppBlockMode> = emptyMap()
    private var currentCreditsSeconds: Long = 0L
    private var lastPackageName: String? = null
    private var pollingJob: Job? = null
    private var settingsJob: Job? = null
    private var notificationManager: NotificationManager? = null
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground("Credits available")
        serviceScope.launch {
            settingsStore?.ensureDailyGrantApplied()
        }
        startSettingsCollection()
        startPolling()
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        settingsJob?.cancel()
        cancelForegroundNotification()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startSettingsCollection() {
        if (settingsJob?.isActive == true) return
        settingsJob = serviceScope.launch {
            settingsStore?.settingsFlow?.collect { state ->
                appRules = state.appRules
                currentCreditsSeconds = state.globalCreditsSeconds
                if (currentCreditsSeconds <= 0L) {
                    cancelForegroundNotification()
                    stopSelf()
                } else {
                    updateCreditsNotification()
                }
            }
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = serviceScope.launch {
            while (isActive) {
                if (!isUsageAccessGranted(this@UsageTrackingService)) {
                    delay(CREDIT_CONSUME_INTERVAL_MS)
                    continue
                }
                if (currentCreditsSeconds <= 0L) {
                    delay(CREDIT_CONSUME_INTERVAL_MS)
                    continue
                }
                val packageName = resolveForegroundPackage()
                lastPackageName = packageName
                val mode = packageName?.let { appRules[it] }
                val shouldConsume = mode == AppBlockMode.ALL
                if (shouldConsume) {
                    consumeCredits(1L)
                } else {
                    updateCreditsNotification()
                }
                delay(CREDIT_CONSUME_INTERVAL_MS)
            }
        }
    }

    private fun consumeCredits(seconds: Long) {
        if (seconds <= 0L) return
        val store = settingsStore ?: return
        serviceScope.launch {
            val remaining = store.consumeCreditsSeconds(seconds)
            currentCreditsSeconds = remaining
            if (remaining <= 0L) {
                cancelForegroundNotification()
                stopSelf()
            } else {
                updateCreditsNotification()
            }
        }
    }

    private fun resolveForegroundPackage(): String? {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10_000
        val events = usageStatsManager.queryEvents(startTime, endTime)
        var lastPackage: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPackage = event.packageName
            }
        }
        return lastPackage
    }

    private fun updateCreditsNotification() {
        val detail = if (lastPackageName != null) {
            "Using credits on ${lastPackageName}"
        } else {
            "Credits available"
        }
        ensureForeground(detail)
        notificationManager?.notify(NOTIFICATION_ID, buildNotification(detail))
    }

    private fun ensureForeground(detail: String) {
        if (!isForeground) {
            val notification = buildNotification(detail)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        }
    }

    private fun cancelForegroundNotification() {
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForeground = false
        }
        notificationManager?.cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(detail: String) = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
        .setContentTitle("Credits left: ${formatSeconds(currentCreditsSeconds)}")
        .setContentText(detail)
        .setOngoing(true)
        .setAutoCancel(false)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOnlyAlertOnce(true)
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Vision Fit credits",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows remaining time credits while using blocked apps."
        }
        notificationManager?.createNotificationChannel(channel)
    }

    private fun formatSeconds(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) {
            "${minutes}m ${seconds}s"
        } else {
            "${seconds}s"
        }
    }
}
