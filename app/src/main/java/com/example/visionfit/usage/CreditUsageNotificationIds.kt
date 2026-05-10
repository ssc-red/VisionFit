package com.example.visionfit.usage

/**
 * Channel + id for the *active credit consumption* notification. Visible (status bar icon)
 * — used by both [UsageTrackingService] and
 * [com.example.visionfit.accessibility.AppBlockAccessibilityService] to tell the user we
 * are currently spending their credits in a tracked app.
 */
internal const val CREDIT_USAGE_NOTIFICATION_CHANNEL_ID = "visionfit_credits"
internal const val CREDIT_USAGE_NOTIFICATION_ID = 1001

/**
 * Channel + id for the foreground-service "tracker" notification used by
 * [UsageTrackingService]. Android requires a foreground service to show a notification while
 * running, but we don't want it visible when nothing is being tracked, so we put it on a
 * MIN-importance channel — it shows in minimized form in the shade and does NOT show a
 * status bar icon. The status-bar icon is only added by the separate
 * [CREDIT_USAGE_NOTIFICATION_ID] notification when we are actively consuming credits.
 */
internal const val USAGE_TRACKER_NOTIFICATION_CHANNEL_ID = "visionfit_tracker"
internal const val USAGE_TRACKER_NOTIFICATION_ID = 1002
