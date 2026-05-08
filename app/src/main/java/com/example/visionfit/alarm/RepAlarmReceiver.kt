package com.example.visionfit.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.visionfit.data.SettingsStore
import com.example.visionfit.model.AlarmScheduleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RepAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        receiverScope.launch {
            try {
                if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
                    val store = SettingsStore(appContext)
                    store.snapshotRepAlarms()
                        .filter { it.enabled }
                        .forEach { RepAlarmScheduler.schedule(appContext, it) }
                    return@launch
                }

                val alarmId = intent?.getStringExtra(RepAlarmScheduler.EXTRA_ALARM_ID) ?: return@launch
                val store = SettingsStore(appContext)
                val alarm = store.getAlarmById(alarmId) ?: return@launch
                if (!alarm.enabled) return@launch

                // Reschedule next occurrence before starting the ringing UI/service
                if (alarm.scheduleMode == AlarmScheduleMode.ONCE) {
                    // For one-time alarms, we might want to mark it disabled in the store
                    store.setRepAlarmEnabled(alarmId, false)
                } else {
                    RepAlarmScheduler.schedule(appContext, alarm)
                }

                // Start the ringing service and launch UI immediately.
                appContext.startActivity(
                    AlarmRingingActivity.launchIntent(appContext, alarm.id).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                )

                RepAlarmRingingService.start(
                    context = appContext,
                    alarmId = alarm.id,
                    soundUri = alarm.soundUri,
                    snoozeEnabled = alarm.snoozeEnabled,
                    title = "Rep alarm",
                    body = "Complete ${alarm.targetReps} reps of ${alarm.exercise.displayName} to stop this alarm."
                )
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
