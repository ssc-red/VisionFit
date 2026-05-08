package com.example.visionfit.util

import com.example.visionfit.model.WorkoutHistoryEntry
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

enum class WorkoutGraphRange(val label: String) {
    WEEK("Week"),
    MONTH("Month"),
    YEAR("Year")
}

data class WorkoutGraphBar(
    val label: String,
    val units: Long
)

fun buildWorkoutGraphBars(
    range: WorkoutGraphRange,
    history: List<WorkoutHistoryEntry>,
    nowMillis: Long = System.currentTimeMillis()
): List<WorkoutGraphBar> {
    return when (range) {
        WorkoutGraphRange.WEEK -> buildDailyBars(history, nowMillis, days = 7, labelFormat = "EEE")
        WorkoutGraphRange.MONTH -> buildDailyBars(history, nowMillis, days = 30, labelFormat = "d")
        WorkoutGraphRange.YEAR -> buildMonthlyBars(history, nowMillis)
    }
}

private fun buildDailyBars(
    history: List<WorkoutHistoryEntry>,
    nowMillis: Long,
    days: Int,
    labelFormat: String
): List<WorkoutGraphBar> {
    val endOfToday = startOfDay(nowMillis)
    val dayFormat = SimpleDateFormat(labelFormat, Locale.getDefault())
    return (days - 1 downTo 0).map { offset ->
        val start = addDays(endOfToday, -offset)
        val end = addDays(start, 1)
        WorkoutGraphBar(
            label = dayFormat.format(Date(start)),
            units = history
                .filter { it.dayStartMillis in start until end }
                .sumOf { it.units }
        )
    }
}

private fun buildMonthlyBars(
    history: List<WorkoutHistoryEntry>,
    nowMillis: Long
): List<WorkoutGraphBar> {
    val startOfThisMonth = startOfMonth(nowMillis)
    val monthFormat = SimpleDateFormat("MMM", Locale.getDefault())
    return (11 downTo 0).map { offset ->
        val start = addMonths(startOfThisMonth, -offset)
        val end = addMonths(start, 1)
        WorkoutGraphBar(
            label = monthFormat.format(Date(start)),
            units = history
                .filter { it.dayStartMillis in start until end }
                .sumOf { it.units }
        )
    }
}

private fun startOfDay(millis: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun startOfMonth(millis: Long): Long {
    return Calendar.getInstance().apply {
        timeInMillis = millis
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun addDays(millis: Long, days: Int): Long {
    return Calendar.getInstance().apply {
        timeInMillis = millis
        add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis
}

private fun addMonths(millis: Long, months: Int): Long {
    return Calendar.getInstance().apply {
        timeInMillis = millis
        add(Calendar.MONTH, months)
    }.timeInMillis
}
