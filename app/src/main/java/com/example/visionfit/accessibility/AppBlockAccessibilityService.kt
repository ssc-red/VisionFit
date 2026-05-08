package com.example.visionfit.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.example.visionfit.data.SettingsStore
import com.example.visionfit.model.AppBlockMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay

private const val REELS_GRACE_PERIOD_MS = 1500L
private const val CREDIT_CONSUME_INTERVAL_MS = 1000L
private const val REELS_LOG_TAG = "VisionFitReels"

class AppBlockAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lastReelsSeen = mutableMapOf<String, Long>()
    private var appRules: Map<String, AppBlockMode> = emptyMap()
    private var blockedPackage: String? = null
    private var overlay: BlockingOverlay? = null
    private var currentCreditsSeconds: Long = 0L
    private var lastPackageName: String? = null
    private var lastReelsActive: Boolean = false
    private var settingsStore: SettingsStore? = null
    private var consumptionJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        settingsStore = SettingsStore(applicationContext)
        overlay = BlockingOverlay(this) { performGlobalAction(GLOBAL_ACTION_HOME) }
        serviceScope.launch {
            settingsStore?.ensureDailyGrantApplied()
            settingsStore?.settingsFlow?.collect { state ->
                appRules = state.appRules
                currentCreditsSeconds = state.globalCreditsSeconds
                val activePackage = lastPackageName
                val updatedMode = activePackage?.let { appRules[it] }
                applyBlockingState(activePackage, updatedMode, lastReelsActive)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName == this.packageName && overlay?.isShowing == true) return

        val mode = appRules[packageName]
        val reelsActive = mode == AppBlockMode.REELS_ONLY && isReelsActive(packageName, event)
        applyBlockingState(packageName, mode, reelsActive)
    }

    override fun onInterrupt() {
        overlay?.hide()
    }

    override fun onDestroy() {
        stopConsumption()
        overlay?.hide()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun isReelsActive(packageName: String, event: AccessibilityEvent): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (isReelsEvent(event)) {
            lastReelsSeen[packageName] = now
            return true
        }

        val lastSeen = lastReelsSeen[packageName] ?: return false
        return now - lastSeen <= REELS_GRACE_PERIOD_MS
    }

    private fun isReelsEvent(event: AccessibilityEvent): Boolean {
        val className = event.className?.toString()?.lowercase().orEmpty()
        val source = event.source
        val viewId = source?.viewIdResourceName?.lowercase().orEmpty()
        source?.recycle()

        val viewerClassTokens = listOf(
            "reelviewer",
            "reelsviewer",
            "clipsviewer",
            "shortswatchactivity",
            "shortsplayer",
            "reelfeed",
            "reels_viewer",
            "clips_viewer"
        )
        val viewerIdTokens = listOf(
            "reels_viewer",
            "reel_viewer",
            "clips_viewer",
            "reels_tray_viewer",
            "shorts_player",
            "shorts_container",
            "reels_tab_root"
        )

        val classMatches = viewerClassTokens.any { className.contains(it) }
        val idMatches = viewerIdTokens.any { viewId.contains(it) }

        Log.d(REELS_LOG_TAG, "class=$className viewId=$viewId classMatches=$classMatches idMatches=$idMatches")
        return classMatches || idMatches
    }

    private fun applyBlockingState(
        packageName: String?,
        mode: AppBlockMode?,
        reelsActive: Boolean
    ) {
        lastPackageName = packageName
        lastReelsActive = reelsActive

        val shouldConsume = mode != null
        val hasCredits = currentCreditsSeconds > 0L
        val shouldBlock = mode != null && !hasCredits && when (mode) {
            AppBlockMode.ALL -> true
            AppBlockMode.REELS_ONLY -> reelsActive
        }

        if (shouldBlock && packageName != null) {
            blockedPackage = packageName
            overlay?.show(resolveAppLabel(packageName), mode)
        } else if (blockedPackage != null) {
            overlay?.hide()
            blockedPackage = null
        }

        if (shouldConsume && hasCredits) {
            startConsumption()
        } else {
            stopConsumption()
        }
    }

    private fun startConsumption() {
        if (consumptionJob?.isActive == true) return
        consumptionJob = serviceScope.launch {
            while (isActive) {
                delay(CREDIT_CONSUME_INTERVAL_MS)
                consumeCredits(1L)
            }
        }
    }

    private fun stopConsumption() {
        consumptionJob?.cancel()
        consumptionJob = null
    }

    private fun consumeCredits(seconds: Long) {
        if (seconds <= 0L) return
        val store = settingsStore ?: return
        serviceScope.launch {
            val remaining = store.consumeCreditsSeconds(seconds)
            currentCreditsSeconds = remaining
            val activePackage = lastPackageName
            val updatedMode = activePackage?.let { appRules[it] }
            applyBlockingState(activePackage, updatedMode, lastReelsActive)
        }
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
}
