package com.example.visionfit.usage

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.visionfit.MainActivity
import com.example.visionfit.accessibility.AppBlockAccessibilityService
import com.example.visionfit.accessibility.BlockingOverlay
import com.example.visionfit.accessibility.BlockingOverlayWindowType
import com.example.visionfit.data.SettingsStore
import com.example.visionfit.model.AppBlockMode
import com.example.visionfit.util.isAccessibilityServiceEnabled
import com.example.visionfit.util.isDrawOverlaysGranted
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
/** Usage events older than this are ignored; [lastResolvedForegroundPackage] covers longer sessions. */
private const val USAGE_EVENTS_LOOKBACK_MS = 120_000L

/**
 * Runs when the Accessibility blocking service is **disabled**: consumes credits for ALL-mode
 * apps via Usage Stats, and shows a fallback block overlay (requires "Display over other apps")
 * when credits reach zero. When Accessibility is enabled, [MainActivity] does not start this
 * service — blocking and credit burn are handled entirely by [com.example.visionfit.accessibility.AppBlockAccessibilityService].
 */
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
    private var overlay: BlockingOverlay? = null
    /** Last known foreground from usage events — survives gaps with no events in [USAGE_EVENTS_LOOKBACK_MS]. */
    private var lastResolvedForegroundPackage: String? = null

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Required by Android: a service started with startForegroundService() must call
        // startForeground within a few seconds. We post a silent MIN-importance tracker
        // notification (no status-bar icon) — the *visible* "spending credits" notification
        // is posted separately and only while we're actually consuming credits.
        ensureTrackerForeground()
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
        overlay?.hide()
        overlay = null
        clearActiveCreditNotification()
        stopTrackerForeground()
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
                if (currentCreditsSeconds <= 0L && appRules.isEmpty()) {
                    overlay?.hide()
                    clearActiveCreditNotification()
                    stopTrackerForeground()
                    stopSelf()
                }
            }
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = serviceScope.launch {
            while (isActive) {
                delay(CREDIT_CONSUME_INTERVAL_MS)
                // If Accessibility is enabled, that service owns blocking + consumption.
                // Self-stop so we don't post a competing notification.
                if (isAccessibilityServiceEnabled(
                        this@UsageTrackingService,
                        AppBlockAccessibilityService::class.java
                    )
                ) {
                    overlay?.hide()
                    clearActiveCreditNotification()
                    stopTrackerForeground()
                    stopSelf()
                    return@launch
                }
                if (!isUsageAccessGranted(this@UsageTrackingService)) {
                    overlay?.hide()
                    clearActiveCreditNotification()
                    continue
                }

                val packageName = resolveForegroundPackage()
                lastPackageName = packageName
                val mode = packageName?.let { appRules[it] }

                val store = settingsStore
                var didConsume = false
                // Drain credits whenever the foreground is a tracked app, regardless of mode.
                if (mode != null && currentCreditsSeconds > 0 && store != null) {
                    currentCreditsSeconds = store.consumeCreditsSeconds(1L)
                    didConsume = true
                }

                applyFallbackBlocking(packageName, mode)
                // Visible "spending credits" notification matches the accessibility-mode
                // behavior: only posted while credits are actively being consumed in a
                // tracked app, cleared the moment that stops.
                if (didConsume && packageName != null) {
                    postActiveCreditNotification(packageName)
                } else {
                    clearActiveCreditNotification()
                }
            }
        }
    }

    private fun applyFallbackBlocking(packageName: String?, mode: AppBlockMode?) {
        if (packageName == this.packageName) {
            overlay?.hide()
            return
        }
        if (!isDrawOverlaysGranted(this)) {
            overlay?.hide()
            return
        }
        if (mode == null) {
            overlay?.hide()
            return
        }
        // Without Accessibility we cannot detect the Reels viewer specifically; fall back
        // to "block all screens" only for ALL-mode apps. REELS_ONLY drains credits but
        // does not block in usage-stats fallback mode.
        if (mode == AppBlockMode.REELS_ONLY) {
            overlay?.hide()
            return
        }

        val hasCredits = currentCreditsSeconds > 0L
        val shouldBlock = !hasCredits
        if (shouldBlock && packageName != null) {
            if (overlay == null) {
                overlay = BlockingOverlay(
                    this,
                    onExitApp = { navigateHome() },
                    onEarnCredits = { openVisionFitHome() },
                    BlockingOverlayWindowType.APPLICATION
                )
            }
            overlay?.show(resolveAppLabel(packageName), mode)
        } else {
            overlay?.hide()
        }
    }

    private fun navigateHome() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    private fun openVisionFitHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }

    private fun resolveAppLabel(packageName: String): String {
        val pm = packageManager
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info)?.toString().orEmpty().ifBlank { packageName }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun resolveForegroundPackage(): String? {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - USAGE_EVENTS_LOOKBACK_MS
        val events = usageStatsManager.queryEvents(startTime, endTime)
        var lastPackage: String? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastPackage = event.packageName
            }
        }
        if (lastPackage != null) {
            lastResolvedForegroundPackage = lastPackage
            return lastPackage
        }
        return lastResolvedForegroundPackage
    }

    /**
     * Posts the visible "spending credits" notification on the LOW-importance credits
     * channel. Matches the accessibility-mode behavior — appears only while the user is
     * actively burning credits in a tracked app, dismissed the moment that stops.
     */
    private fun postActiveCreditNotification(packageName: String) {
        val notif = NotificationCompat.Builder(this, CREDIT_USAGE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Credits left: ${formatSeconds(currentCreditsSeconds)}")
            .setContentText("Using credits on ${resolveAppLabel(packageName)}")
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
        notificationManager?.notify(CREDIT_USAGE_NOTIFICATION_ID, notif)
    }

    private fun clearActiveCreditNotification() {
        notificationManager?.cancel(CREDIT_USAGE_NOTIFICATION_ID)
    }

    /**
     * Posts the foreground-service tracker notification on the MIN-importance tracker
     * channel and promotes the service to foreground. The notification is required by
     * Android for the service to keep running but is invisible in the status bar (MIN
     * importance) — only the active credit notification creates a status-bar icon.
     */
    private fun ensureTrackerForeground() {
        if (isForeground) return
        val notification = buildTrackerNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(USAGE_TRACKER_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(USAGE_TRACKER_NOTIFICATION_ID, notification)
        }
        isForeground = true
    }

    private fun stopTrackerForeground() {
        if (isForeground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            isForeground = false
        }
        notificationManager?.cancel(USAGE_TRACKER_NOTIFICATION_ID)
    }

    private fun buildTrackerNotification() = NotificationCompat.Builder(this, USAGE_TRACKER_NOTIFICATION_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_menu_view)
        .setContentTitle("Vision Fit credit tracker")
        .setContentText("Watching for blocked apps")
        .setOngoing(true)
        .setAutoCancel(false)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setOnlyAlertOnce(true)
        .build()

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notificationManager ?: return
        val creditsChannel = NotificationChannel(
            CREDIT_USAGE_NOTIFICATION_CHANNEL_ID,
            "Vision Fit credits",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows remaining time credits while using blocked apps."
        }
        nm.createNotificationChannel(creditsChannel)
        val trackerChannel = NotificationChannel(
            USAGE_TRACKER_NOTIFICATION_CHANNEL_ID,
            "Vision Fit tracker",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Background credit tracker (no status-bar icon)."
        }
        nm.createNotificationChannel(trackerChannel)
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
