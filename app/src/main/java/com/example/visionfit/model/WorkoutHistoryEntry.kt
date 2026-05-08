package com.example.visionfit.model

data class WorkoutHistoryEntry(
    val dayStartMillis: Long,
    val sessions: Long = 0L,
    val units: Long = 0L,
    val creditsEarnedSeconds: Long = 0L
)
