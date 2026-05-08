package com.example.visionfit.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.visionfit.MainActivity
import com.example.visionfit.model.AlarmScheduleMode
import com.example.visionfit.model.RepAlarm
import java.util.Calendar

object RepAlarmScheduler {
    const val EXTRA_ALARM_ID = "extra_alarm_id"

    fun schedule(context: Context, alarm: RepAlarm) {
        if (!alarm.enabled) {
            cancel(context, alarm.id)
            return
        }
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = createPendingIntent(context, alarm.id)
        val triggerAt = nextTriggerAtMillis(alarm) ?: run {
            cancel(context, alarm.id)
            return
        }

        // Information intent for the system's "Next Alarm" display
        val showIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val showPendingIntent = PendingIntent.getActivity(
            context,
            alarm.id.hashCode() + 1,
            showIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val info = AlarmManager.AlarmClockInfo(triggerAt, showPendingIntent)
        
        try {
            // Using setAlarmClock is the most reliable way to fire Alarms on Android.
            // It automatically treats the broadcast as high priority and works while idle.
            alarmManager.setAlarmClock(info, pending)
        } catch (e: SecurityException) {
            // For Android 12+ (API 31) exact alarms require SCHEDULE_EXACT_ALARM permission.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                } else {
                    // Do not launch Settings from background scheduling paths.
                    // Settings UI already exposes exact alarm permission status/action.
                    return
                }
            } else {
                // For older Android versions, fall back to exact alarm without extra permission.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                } else {
                    alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
                }
            }
        }
    }

    fun cancel(context: Context, alarmId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = existingPendingIntent(context, alarmId)
        if (pending != null) {
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    fun snooze(context: Context, alarmId: String, minutes: Int = 5) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = createPendingIntent(context, alarmId)
        val triggerAt = System.currentTimeMillis() + minutes.coerceAtLeast(1) * 60_000L
        
        val info = AlarmManager.AlarmClockInfo(triggerAt, null)
        try {
            alarmManager.setAlarmClock(info, pending)
        } catch (e: SecurityException) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        }
    }

    private fun createPendingIntent(context: Context, alarmId: String): PendingIntent {
        val intent = Intent(context, RepAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
            // Critical for waking up the process quickly from the background
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun existingPendingIntent(context: Context, alarmId: String): PendingIntent? {
        val intent = Intent(context, RepAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_ID, alarmId)
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun nextTriggerAtMillis(alarm: RepAlarm, nowMillis: Long = System.currentTimeMillis()): Long? {
        return when (alarm.scheduleMode) {
            AlarmScheduleMode.ONCE -> nextOneTimeTriggerAtMillis(alarm, nowMillis)
            AlarmScheduleMode.WEEKLY -> nextWeeklyTriggerAtMillis(alarm, nowMillis)
            AlarmScheduleMode.DAILY -> nextDailyTriggerAtMillis(alarm, nowMillis)
        }
    }

    private fun nextDailyTriggerAtMillis(alarm: RepAlarm, nowMillis: Long): Long {
        val next = alarmTimeOn(Calendar.getInstance().apply { timeInMillis = nowMillis }, alarm)
        if (next.timeInMillis <= nowMillis) {
            next.add(Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis
    }

    private fun nextWeeklyTriggerAtMillis(alarm: RepAlarm, nowMillis: Long): Long {
        val selectedDays = alarm.normalizedDaysOfWeek().ifEmpty {
            RepAlarm.WEEK_DAYS.map { it.value }.toSet()
        }
        val base = Calendar.getInstance().apply { timeInMillis = nowMillis }
        for (dayOffset in 0..7) {
            val candidateDate = base.clone() as Calendar
            candidateDate.add(Calendar.DAY_OF_YEAR, dayOffset)
            if (candidateDate.get(Calendar.DAY_OF_WEEK) !in selectedDays) continue
            val candidate = alarmTimeOn(candidateDate, alarm)
            if (candidate.timeInMillis > nowMillis) return candidate.timeInMillis
        }
        return nextDailyTriggerAtMillis(alarm, nowMillis)
    }

    private fun nextOneTimeTriggerAtMillis(alarm: RepAlarm, nowMillis: Long): Long? {
        val dateMillis = alarm.specificDateMillis ?: return nextDailyTriggerAtMillis(alarm, nowMillis)
        val date = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val candidate = alarmTimeOn(date, alarm)
        return candidate.timeInMillis.takeIf { it > nowMillis }
    }

    private fun alarmTimeOn(date: Calendar, alarm: RepAlarm): Calendar {
        return (date.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, alarm.hour24.coerceIn(0, 23))
            set(Calendar.MINUTE, alarm.minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
    }
}
