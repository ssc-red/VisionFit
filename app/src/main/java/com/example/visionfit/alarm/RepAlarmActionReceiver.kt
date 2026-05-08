package com.example.visionfit.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.visionfit.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class RepAlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val alarmId = intent?.getStringExtra(RepAlarmScheduler.EXTRA_ALARM_ID) ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        receiverScope.launch {
            try {
                when (intent.action) {
                    RepAlarmRingingService.ACTION_SNOOZE -> {
                        val store = SettingsStore(appContext)
                        val alarm = store.getAlarmById(alarmId) ?: return@launch
                        if (!alarm.snoozeEnabled) return@launch
                        store.setActiveAlarmId(null)
                        RepAlarmRingingService.stop(appContext)
                        RepAlarmScheduler.snooze(appContext, alarmId, minutes = 5)
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
