package com.example.visionfit.util

import com.example.visionfit.model.WorkoutHistoryEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class WorkoutGraphTest {
    @Test
    fun weekGraphIncludesOnlyLastSevenDays() {
        withUtcTimezone {
            val now = utcMillis(Calendar.MAY, 7, 12)
            val history = listOf(
                entry(Calendar.MAY, 7, 30),
                entry(Calendar.MAY, 4, 20),
                entry(Calendar.MAY, 1, 50),
                entry(Calendar.APRIL, 30, 999)
            )

            val bars = buildWorkoutGraphBars(WorkoutGraphRange.WEEK, history, nowMillis = now)

            assertEquals(7, bars.size)
            assertEquals(100L, bars.sumOf { it.units })
        }
    }

    @Test
    fun monthGraphIncludesOnlyLastThirtyDays() {
        withUtcTimezone {
            val now = utcMillis(Calendar.MAY, 7, 12)
            val history = listOf(
                entry(Calendar.MAY, 7, 15),
                entry(Calendar.APRIL, 17, 25),
                entry(Calendar.APRIL, 6, 35),
                entry(Calendar.APRIL, 5, 999)
            )

            val bars = buildWorkoutGraphBars(WorkoutGraphRange.MONTH, history, nowMillis = now)

            assertEquals(30, bars.size)
            assertEquals(40L, bars.sumOf { it.units })
        }
    }

    @Test
    fun yearGraphIncludesOnlyLastTwelveMonths() {
        withUtcTimezone {
            val now = utcMillis(Calendar.MAY, 7, 12)
            val history = listOf(
                monthEntry(2026, Calendar.MAY, 40),
                monthEntry(2025, Calendar.DECEMBER, 60),
                monthEntry(2025, Calendar.APRIL, 999)
            )

            val bars = buildWorkoutGraphBars(WorkoutGraphRange.YEAR, history, nowMillis = now)

            assertEquals(12, bars.size)
            assertEquals(100L, bars.sumOf { it.units })
        }
    }

    private fun entry(
        month: Int,
        dayOfMonth: Int,
        units: Long
    ): WorkoutHistoryEntry {
        return WorkoutHistoryEntry(
            dayStartMillis = utcMillis(month, dayOfMonth, 0),
            units = units
        )
    }

    private fun monthEntry(
        year: Int,
        month: Int,
        units: Long
    ): WorkoutHistoryEntry {
        return WorkoutHistoryEntry(
            dayStartMillis = monthStartUtcMillis(year, month),
            units = units
        )
    }

    private fun utcMillis(month: Int, dayOfMonth: Int, hour: Int): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, dayOfMonth)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun monthStartUtcMillis(year: Int, month: Int): Long {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private inline fun withUtcTimezone(block: () -> Unit) {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            block()
        } finally {
            TimeZone.setDefault(original)
        }
    }
}

