package com.example.visionfit.alarm

import com.example.visionfit.model.AlarmScheduleMode
import com.example.visionfit.model.ExerciseType
import com.example.visionfit.model.RepAlarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class RepAlarmSchedulerTest {

    @Test
    fun dailyAlarmSchedulesNextDayWhenTimeAlreadyPassed() {
        val now = calendarMillis(2026, Calendar.JANUARY, 15, 10, 0)
        val alarm = baseAlarm(hour24 = 9, minute = 30, scheduleMode = AlarmScheduleMode.DAILY)

        val next = RepAlarmScheduler.nextTriggerAtMillis(alarm, now)

        val expected = calendarMillis(2026, Calendar.JANUARY, 16, 9, 30)
        assertEquals(expected, next)
    }

    @Test
    fun weeklyAlarmPicksNextSelectedDay() {
        val now = calendarMillis(2026, Calendar.JANUARY, 12, 10, 0) // Monday
        val alarm = baseAlarm(
            hour24 = 8,
            minute = 0,
            scheduleMode = AlarmScheduleMode.WEEKLY,
            daysOfWeek = setOf(Calendar.WEDNESDAY)
        )

        val next = RepAlarmScheduler.nextTriggerAtMillis(alarm, now)

        val expected = calendarMillis(2026, Calendar.JANUARY, 14, 8, 0) // Wednesday
        assertEquals(expected, next)
    }

    @Test
    fun oneTimeAlarmReturnsNullWhenDateTimeIsInPast() {
        val now = calendarMillis(2026, Calendar.JANUARY, 20, 10, 0)
        val date = calendarMillis(2026, Calendar.JANUARY, 19, 0, 0)
        val alarm = baseAlarm(
            hour24 = 8,
            minute = 15,
            scheduleMode = AlarmScheduleMode.ONCE,
            specificDateMillis = date
        )

        val next = RepAlarmScheduler.nextTriggerAtMillis(alarm, now)

        assertNull(next)
    }

    private fun baseAlarm(
        hour24: Int,
        minute: Int,
        scheduleMode: AlarmScheduleMode,
        daysOfWeek: Set<Int> = emptySet(),
        specificDateMillis: Long? = null
    ): RepAlarm {
        return RepAlarm(
            id = "test",
            hour24 = hour24,
            minute = minute,
            exercise = ExerciseType.PUSHUPS,
            targetReps = 20,
            enabled = true,
            scheduleMode = scheduleMode,
            daysOfWeek = daysOfWeek,
            specificDateMillis = specificDateMillis
        )
    }

    private fun calendarMillis(
        year: Int,
        month: Int,
        dayOfMonth: Int,
        hour24: Int,
        minute: Int
    ): Long {
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, dayOfMonth)
            set(Calendar.HOUR_OF_DAY, hour24)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}

