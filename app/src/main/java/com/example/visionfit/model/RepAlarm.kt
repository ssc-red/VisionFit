package com.example.visionfit.model

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

enum class AlarmScheduleMode {
    DAILY,
    WEEKLY,
    ONCE
}

data class RepAlarm(
    val id: String,
    val hour24: Int,
    val minute: Int,
    val exercise: ExerciseType,
    val targetReps: Int,
    val enabled: Boolean,
    val snoozeEnabled: Boolean = true,
    val soundUri: String? = null,
    val scheduleMode: AlarmScheduleMode = AlarmScheduleMode.DAILY,
    val daysOfWeek: Set<Int> = emptySet(),
    val specificDateMillis: Long? = null
) {
    fun timeLabel(): String {
        val hour12 = when (val value = hour24 % 12) {
            0 -> 12
            else -> value
        }
        val period = if (hour24 < 12) "AM" else "PM"
        return "%d:%02d %s".format(hour12, minute, period)
    }

    fun normalizedDaysOfWeek(): Set<Int> {
        return daysOfWeek.filter { it in Calendar.SUNDAY..Calendar.SATURDAY }.toSet()
    }

    fun scheduleLabel(): String {
        return when (scheduleMode) {
            AlarmScheduleMode.DAILY -> "Every day"
            AlarmScheduleMode.WEEKLY -> {
                val days = normalizedDaysOfWeek()
                if (days.isEmpty() || days.size == WEEK_DAYS.size) {
                    "Every day"
                } else {
                    WEEK_DAYS.filter { it.value in days }.joinToString(", ") { it.shortLabel }
                }
            }
            AlarmScheduleMode.ONCE -> {
                val millis = specificDateMillis ?: return "Pick a date"
                val formatter = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
                formatter.format(millis)
            }
        }
    }

    companion object {
        val WEEK_DAYS: List<WeekDay> = listOf(
            WeekDay(Calendar.SUNDAY, "S", "Sun"),
            WeekDay(Calendar.MONDAY, "M", "Mon"),
            WeekDay(Calendar.TUESDAY, "T", "Tue"),
            WeekDay(Calendar.WEDNESDAY, "W", "Wed"),
            WeekDay(Calendar.THURSDAY, "T", "Thu"),
            WeekDay(Calendar.FRIDAY, "F", "Fri"),
            WeekDay(Calendar.SATURDAY, "S", "Sat")
        )
    }
}

data class WeekDay(
    val value: Int,
    val chipLabel: String,
    val shortLabel: String
)
