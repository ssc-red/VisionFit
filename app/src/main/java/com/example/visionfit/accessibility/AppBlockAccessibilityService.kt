package com.example.visionfit.accessibility

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.visionfit.MainActivity
import com.example.visionfit.data.SettingsStore
import com.example.visionfit.model.AppBlockMode
import com.example.visionfit.usage.CREDIT_USAGE_NOTIFICATION_CHANNEL_ID
import com.example.visionfit.usage.CREDIT_USAGE_NOTIFICATION_ID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job

private const val CREDIT_CONSUME_INTERVAL_MS = 1000L
/**
 * Tiny grace window for activity transitions where the windows API briefly returns no
 * application window. The launcher case is NOT covered here — that is handled in the
 * resolver itself by treating the launcher as "user is not in a tracked app".
 */
private const val FOREGROUND_STICKY_MS = 2_000L
private const val REELS_LOG_TAG = "VisionFitReels"
private const val SERVICE_LOG_TAG = "VisionFitBlock"

/** Packages that are never the "real" foreground for credit purposes. */
private val SYSTEM_FOREGROUND_PREFIXES = listOf(
    "com.android.systemui",
    "com.android.launcher",
    "com.google.android.apps.nexuslauncher",
    "com.sec.android.app.launcher",
    "com.miui.home",
    "com.oneplus.launcher",
    "com.huawei.android.launcher",
    "com.oppo.launcher",
    "com.coloros.launcher",
)

/**
 * View id resource name suffixes (the part after the `:id/`) that uniquely identify the
 * **immersive Reels/Shorts viewer**. Looked up at tick time inside the foreground window's
 * view hierarchy. These are exact suffix matches on the part after the `:id/`.
 */
private val REELS_VIEWER_VIEW_ID_SUFFIXES = setOf(
    // Instagram Reels (older naming)
    "reel_viewer_root",
    "reels_viewer_root",
    "reel_viewer_pager",
    "reel_viewer_view_pager",
    // Instagram Clips (current Reels viewer)
    "clips_viewer_root",
    "clips_viewer_pager",
    "clips_viewer_view_pager",
    "clips_video_layout",
    "clips_video_container",
    "clips_video_player",
    // YouTube Shorts
    "shorts_player_root",
    "shorts_container_root",
    "reel_player_underlay",
    // Facebook Reels
    "reels_root_view",
    "reels_viewer_root_view",
    "reel_viewer_layout",
    "reels_player_container"
)

/**
 * Reels detection also accepts a view id whose tail contains any of these unique substrings.
 * Each substring must be specific enough that it won't appear in normal feed UI.
 */
private val REELS_VIEWER_VIEW_ID_CONTAINS = listOf(
    "reel_viewer",
    "clips_viewer",
    "shorts_player",
    "shorts_container"
)

/** AccessibilityNodeInfo.className tokens that identify the Reels/Shorts viewer fragment. */
private val REELS_VIEWER_CLASS_NAME_TOKENS = listOf(
    "reelsviewerfragment",
    "clipsviewerfragment",
    "reelviewerfragment",
    "shortswatchactivity",
    "shortsplayerfragment"
)

/** Bound the recursive view scan so a misbehaving foreground app cannot pin Main. */
private const val REELS_SCAN_MAX_DEPTH = 30
private const val REELS_SCAN_MAX_NODES = 3000

/**
 * Once the Reels viewer has been confirmed visible in the foreground app, keep treating it
 * as visible for this long even if a subsequent scan can't confirm. Prevents the blocking
 * overlay from flickering during reel transitions, fragment re-creation, or any tick where
 * the view tree is briefly unreachable. Reset on foreground package change.
 */
private const val REELS_VISIBLE_HYSTERESIS_MS = 2_500L
private fun String.looksLikeSystemForeground(): Boolean {
    if (this == "android") return true
    if (contains("inputmethod", ignoreCase = true)) return true
    return SYSTEM_FOREGROUND_PREFIXES.any { startsWith(it) }
}

/**
 * Drives blocking + credit consumption with a 1-second ticker rather than relying on
 * AccessibilityEvent timing. Events still feed into reels-only detection (we record when the
 * Reels viewer is on screen for the active package), but the consumption loop and overlay
 * decisions are made from the active window list every tick, which is the only reliable way
 * to know what the user is actually looking at.
 */
class AppBlockAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var appRules: Map<String, AppBlockMode> = emptyMap()
    private var blockedPackage: String? = null
    private var overlay: BlockingOverlay? = null
    private var currentCreditsSeconds: Long = 0L
    private var settingsStore: SettingsStore? = null
    private var tickerJob: Job? = null
    private var settingsJob: Job? = null
    private var notificationManager: NotificationManager? = null

    /** Last known foreground from a fresh window resolution; persisted briefly for sticky behavior. */
    private var lastResolvedPackage: String? = null
    private var lastResolvedAtMs: Long = 0L
    /** When we last consumed a credit. Kept for diagnostics. */
    private var lastConsumedAtMs: Long = 0L
    /** When we last *positively* detected a Reels viewer in the foreground package. */
    private var reelsLastSeenAtMs: Long = 0L
    /** Package the reels-visible state was last associated with — used to invalidate on app switch. */
    private var reelsLastSeenPackage: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(SERVICE_LOG_TAG, "onServiceConnected")
        settingsStore = SettingsStore(applicationContext)
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureCreditUsageNotificationChannel()
        overlay = BlockingOverlay(
            this,
            onExitApp = { performGlobalAction(GLOBAL_ACTION_HOME) },
            onEarnCredits = { openVisionFitHome() }
        )
        startSettingsCollection()
        startTicker()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // Tick-time scan is the source of truth for "Reels viewer visible" now.
        // We only need accessibility events to keep the service alive.
        event.source?.let {
            @Suppress("DEPRECATION")
            it.recycle()
        }
    }

    override fun onInterrupt() {
        overlay?.hide()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Fires when the user disables this accessibility service in Android settings.
        // onDestroy may be delayed; clean up state here too so the notification disappears immediately.
        Log.d(SERVICE_LOG_TAG, "onUnbind (accessibility disabled)")
        tickerJob?.cancel()
        tickerJob = null
        settingsJob?.cancel()
        settingsJob = null
        clearCreditUsageNotification()
        overlay?.hide()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Log.d(SERVICE_LOG_TAG, "onDestroy")
        tickerJob?.cancel()
        tickerJob = null
        settingsJob?.cancel()
        settingsJob = null
        clearCreditUsageNotification()
        overlay?.hide()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startSettingsCollection() {
        if (settingsJob?.isActive == true) return
        settingsJob = serviceScope.launch {
            settingsStore?.ensureDailyGrantApplied()
            settingsStore?.settingsFlow?.collect { state ->
                appRules = state.appRules
                currentCreditsSeconds = state.globalCreditsSeconds
            }
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = serviceScope.launch {
            while (isActive) {
                runTickSafely()
                delay(CREDIT_CONSUME_INTERVAL_MS)
            }
        }
    }

    private suspend fun runTickSafely() {
        try {
            tick()
        } catch (t: Throwable) {
            Log.w(SERVICE_LOG_TAG, "tick failed", t)
        }
    }

    private suspend fun tick() {
        val pkg = resolveForegroundPackage()
        val mode = pkg?.let { appRules[it] }
        val hasCredits = currentCreditsSeconds > 0L

        // Consume credits whenever the foreground is a tracked app, regardless of mode.
        // (User spec: REELS_ONLY drains credits across the whole app; only the BLOCK is
        // reels-specific.)
        val shouldConsume = pkg != null && mode != null && hasCredits

        // Reels visibility uses a hysteresis window so the overlay doesn't flicker when
        // the scan momentarily fails (which it will: as soon as our overlay is shown,
        // `rootInActiveWindow` points at the overlay, not Instagram). We scan the target
        // app's window via the windows API to bypass that.
        val reelsViewerVisible = pkg != null && mode == AppBlockMode.REELS_ONLY && !hasCredits &&
            isReelsViewerCurrentlyVisible(pkg)

        val shouldBlock = pkg != null && mode != null && !hasCredits && when (mode) {
            AppBlockMode.ALL -> true
            AppBlockMode.REELS_ONLY -> reelsViewerVisible
        }
        applyBlockingOverlay(pkg, mode, shouldBlock)

        var didConsume = false
        if (shouldConsume) {
            val store = settingsStore ?: return
            val remaining = store.consumeCreditsSeconds(1L)
            currentCreditsSeconds = remaining
            lastConsumedAtMs = SystemClock.elapsedRealtime()
            didConsume = true
            // If we just hit zero, re-apply blocking immediately for ALL-mode, or scan for
            // Reels viewer if REELS_ONLY (next tick will catch it; cheap to recompute now).
            if (remaining <= 0L) {
                val justBlock = mode == AppBlockMode.ALL ||
                    (mode == AppBlockMode.REELS_ONLY && isReelsViewerCurrentlyVisible(pkg))
                applyBlockingOverlay(pkg, mode, justBlock)
            }
        }

        // Notification only while actively consuming credits in a restricted app.
        if (didConsume && pkg != null) {
            postCreditUsageNotification(pkg)
        } else {
            clearCreditUsageNotification()
        }

        Log.d(
            SERVICE_LOG_TAG,
            "tick pkg=$pkg mode=$mode reelsViewerVisible=$reelsViewerVisible credits=$currentCreditsSeconds didConsume=$didConsume shouldBlock=$shouldBlock"
        )
    }

    /**
     * Returns whether the Reels viewer should currently be considered visible in [pkg].
     * Implements:
     *   1. Scan only the application window that belongs to [pkg] (so our own overlay
     *      window doesn't hide the answer).
     *   2. If positively detected, remember "reels seen" for [REELS_VISIBLE_HYSTERESIS_MS]
     *      so a single failing scan does NOT cause the overlay to flicker off.
     *   3. Reset the hysteresis state if the foreground package has changed.
     */
    private fun isReelsViewerCurrentlyVisible(pkg: String): Boolean {
        if (reelsLastSeenPackage != pkg) {
            reelsLastSeenPackage = pkg
            reelsLastSeenAtMs = 0L
        }
        val raw = scanReelsViewerInPackageWindow(pkg)
        val now = SystemClock.elapsedRealtime()
        if (raw == true) {
            reelsLastSeenAtMs = now
            return true
        }
        // raw is false (definitive: we read the window and the viewer wasn't there) or
        // null (couldn't read the window this tick). Either way, hold the previous state
        // for a short window so we don't flicker.
        return reelsLastSeenAtMs > 0L && (now - reelsLastSeenAtMs) <= REELS_VISIBLE_HYSTERESIS_MS
    }

    private fun applyBlockingOverlay(packageName: String?, mode: AppBlockMode?, shouldBlock: Boolean) {
        if (shouldBlock && packageName != null && mode != null) {
            blockedPackage = packageName
            overlay?.show(resolveAppLabel(packageName), mode)
        } else if (blockedPackage != null) {
            overlay?.hide()
            blockedPackage = null
        }
    }

    /**
     * Scans the application window that belongs to [targetPackage] for an immersive Reels
     * viewer. Goes through the `windows` list rather than `rootInActiveWindow` because the
     * latter returns *our* blocking overlay's root once it's shown, which would make the
     * scan return false and cause the overlay to flicker off → on → off.
     *
     * @return `true` if found, `false` if we could read the window but it wasn't there,
     *         `null` if we couldn't read the target window at all this tick.
     */
    private fun scanReelsViewerInPackageWindow(targetPackage: String): Boolean? {
        val list = try {
            windows
        } catch (t: Throwable) {
            null
        } ?: return null
        if (list.isEmpty()) return null
        var found: Boolean? = null
        try {
            for (w in list) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: continue
                try {
                    val pkg = root.packageName?.toString()
                    if (pkg != targetPackage) continue
                    val counter = intArrayOf(0)
                    val hit = nodeContainsReelsViewer(root, depth = 0, counter = counter)
                    Log.d(
                        REELS_LOG_TAG,
                        "scan target=$targetPackage layer=${w.layer} nodes=${counter[0]} found=$hit"
                    )
                    found = hit
                    if (hit) break
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
            }
        } finally {
            @Suppress("DEPRECATION")
            for (w in list) w.recycle()
        }
        return found
    }

    private fun nodeContainsReelsViewer(node: AccessibilityNodeInfo?, depth: Int, counter: IntArray): Boolean {
        if (node == null) return false
        if (depth > REELS_SCAN_MAX_DEPTH) return false
        counter[0]++
        if (counter[0] > REELS_SCAN_MAX_NODES) return false

        if (nodeMatchesReelsViewer(node)) return true

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (nodeContainsReelsViewer(child, depth + 1, counter)) return true
            } finally {
                @Suppress("DEPRECATION")
                child.recycle()
            }
        }
        return false
    }

    private fun nodeMatchesReelsViewer(node: AccessibilityNodeInfo): Boolean {
        // 1. View id check (strict suffix or unique-substring).
        val viewId = node.viewIdResourceName?.lowercase()
        if (viewId != null) {
            val suffix = viewId.substringAfterLast('/')
            val suffixHit = suffix in REELS_VIEWER_VIEW_ID_SUFFIXES
            val containsHit = !suffixHit && REELS_VIEWER_VIEW_ID_CONTAINS.any { suffix.contains(it) }
            if (suffixHit || containsHit) {
                val visible = nodeIsActuallyVisible(node)
                Log.d(
                    REELS_LOG_TAG,
                    "matched viewId=$viewId suffixHit=$suffixHit containsHit=$containsHit visible=$visible"
                )
                if (visible) return true
            }
        }
        // 2. Fragment/Activity className check.
        val className = node.className?.toString()?.lowercase()
        if (className != null && REELS_VIEWER_CLASS_NAME_TOKENS.any { className.contains(it) }) {
            val visible = nodeIsActuallyVisible(node)
            Log.d(REELS_LOG_TAG, "matched className=$className visible=$visible")
            if (visible) return true
        }
        return false
    }

    /**
     * Visibility check: prefer `isVisibleToUser` but accept any node with reasonable on-screen
     * bounds, since fragment-container nodes sometimes report false even when on screen.
     */
    private fun nodeIsActuallyVisible(node: AccessibilityNodeInfo): Boolean {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        // A Reels/Shorts viewer is virtually always at least 1/3 of screen height.
        val displayHeight = resources.displayMetrics.heightPixels
        if (displayHeight > 0 && bounds.height() < displayHeight / 3) return false
        return node.isVisibleToUser || bounds.height() >= displayHeight / 2
    }

    /**
     * Returns the package the user is currently looking at, or null if the user is on the
     * launcher, on our own app, or otherwise not in a tracked app.
     *
     * Behavior:
     *  - Notification shade open: shade is a SYSTEM window, so the frontmost APPLICATION
     *    window is still the user's app (Instagram). We resolve to Instagram → consume +
     *    notification keep going.
     *  - Home button / app close: the launcher is the frontmost APPLICATION window. We
     *    resolve to null immediately → next tick stops consuming and clears the notification.
     *  - Transient (no application window at all for one tick): use a short sticky window
     *    so we don't lose state mid-activity-transition.
     */
    private fun resolveForegroundPackage(): String? {
        val frontmostAppPkg = frontmostApplicationPackage()
        if (frontmostAppPkg.foundAppWindow) {
            // We have a definitive answer about what app the user is looking at. The
            // package may be null when it's the launcher / our own app / a system UI app.
            val pkg = frontmostAppPkg.userPackage
            if (pkg != null) {
                recordResolved(pkg)
            }
            return pkg
        }
        // No application window resolvable this tick (briefly between activities). Try
        // active window root, then sticky as a last resort.
        val root = rootInActiveWindow
        if (root != null) {
            val pkg = try {
                root.packageName?.toString()
            } finally {
                @Suppress("DEPRECATION")
                root.recycle()
            }
            if (!pkg.isNullOrEmpty() && pkg != this.packageName && !pkg.looksLikeSystemForeground()) {
                recordResolved(pkg)
                return pkg
            }
        }
        val sticky = lastResolvedPackage ?: return null
        return if (SystemClock.elapsedRealtime() - lastResolvedAtMs <= FOREGROUND_STICKY_MS) sticky else null
    }

    private data class ForegroundResolution(
        /** True if we got a definitive answer from the windows API. */
        val foundAppWindow: Boolean,
        /** The user's tracked-app package, or null when the front app is launcher / our app / system. */
        val userPackage: String?
    )

    /**
     * Looks at the FRONTMOST application window only (highest layer). This matches what
     * "the app I'm currently in" means to the user.
     */
    private fun frontmostApplicationPackage(): ForegroundResolution {
        val list = try {
            windows
        } catch (t: Throwable) {
            null
        } ?: return ForegroundResolution(foundAppWindow = false, userPackage = null)
        if (list.isEmpty()) return ForegroundResolution(foundAppWindow = false, userPackage = null)
        try {
            val sorted = list.sortedByDescending { it.layer }
            for (w in sorted) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val root = w.root ?: continue
                val pkg = try {
                    root.packageName?.toString()
                } finally {
                    @Suppress("DEPRECATION")
                    root.recycle()
                }
                if (pkg.isNullOrEmpty()) {
                    return ForegroundResolution(foundAppWindow = true, userPackage = null)
                }
                // We hit the frontmost app window. Decide once and stop — do NOT keep
                // looking past it. If the launcher is on top, the user is at home, even
                // if Instagram still exists as a backgrounded application window.
                if (pkg == this.packageName || pkg.looksLikeSystemForeground()) {
                    return ForegroundResolution(foundAppWindow = true, userPackage = null)
                }
                return ForegroundResolution(foundAppWindow = true, userPackage = pkg)
            }
            return ForegroundResolution(foundAppWindow = false, userPackage = null)
        } finally {
            @Suppress("DEPRECATION")
            for (w in list) w.recycle()
        }
    }

    private fun recordResolved(pkg: String) {
        lastResolvedPackage = pkg
        lastResolvedAtMs = SystemClock.elapsedRealtime()
    }

    private fun canPostCreditUsageNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun ensureCreditUsageNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = notificationManager ?: return
        val channel = NotificationChannel(
            CREDIT_USAGE_NOTIFICATION_CHANNEL_ID,
            "Vision Fit credits",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows remaining time credits while using blocked apps."
        }
        nm.createNotificationChannel(channel)
    }

    private fun postCreditUsageNotification(pkg: String?) {
        if (!canPostCreditUsageNotifications()) return
        val nm = notificationManager ?: return
        val detail = if (pkg != null) "Using credits on ${resolveAppLabel(pkg)}" else "Vision Fit credits"
        val notification = NotificationCompat.Builder(this, CREDIT_USAGE_NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Credits left: ${formatCreditsTitle(currentCreditsSeconds)}")
            .setContentText(detail)
            .setOngoing(true)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(CREDIT_USAGE_NOTIFICATION_ID, notification)
    }

    private fun clearCreditUsageNotification() {
        notificationManager?.cancel(CREDIT_USAGE_NOTIFICATION_ID)
    }

    private fun formatCreditsTitle(totalSeconds: Long): String {
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    private fun resolveAppLabel(packageName: String): String {
        val pm = packageManager
        return try {
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info)?.toString().orEmpty().ifBlank { packageName }
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    private fun openVisionFitHome() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(intent)
    }
}
