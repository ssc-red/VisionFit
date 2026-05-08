package com.example.visionfit.model

data class SettingsState(
    val globalCreditsSeconds: Long,
    val dailyGrantSeconds: Int,
    val exerciseSeconds: Map<ExerciseType, Int>,
    val exerciseStats: Map<ExerciseType, ExerciseStats>,
    val workoutHistory: List<WorkoutHistoryEntry>,
    val appRules: Map<String, AppBlockMode>,
    val repAlarms: List<RepAlarm>,
    val activeAlarmId: String?
) {
    companion object {
        fun defaults(): SettingsState {
            val exerciseDefaults = ExerciseType.entries.associateWith { it.defaultSecondsPerRep }
            return SettingsState(
                globalCreditsSeconds = 0L,
                dailyGrantSeconds = 0,
                exerciseSeconds = exerciseDefaults,
                exerciseStats = ExerciseType.entries.associateWith { ExerciseStats() },
                workoutHistory = emptyList(),
                appRules = emptyMap(),
                repAlarms = emptyList(),
                activeAlarmId = null
            )
        }
    }
}
