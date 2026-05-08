package com.example.visionfit.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.visionfit.model.AlarmScheduleMode
import com.example.visionfit.model.AppBlockMode
import com.example.visionfit.model.ExerciseStats
import com.example.visionfit.model.ExerciseType
import com.example.visionfit.model.RepAlarm
import com.example.visionfit.model.SettingsState
import com.example.visionfit.model.WorkoutHistoryEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

private const val DATASTORE_NAME = "visionfit_settings"
private const val MAX_WORKOUT_HISTORY_DAYS = 400
private const val MAX_DAILY_GRANT_SECONDS = 86400

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(DATASTORE_NAME)

class SettingsStore(private val context: Context) {
    private object Keys {
        val globalCreditsSeconds = longPreferencesKey("global_credits_seconds")
        val appRules = stringPreferencesKey("app_rules")
        val repAlarms = stringPreferencesKey("rep_alarms")
        val exerciseStats = stringPreferencesKey("exercise_stats")
        val workoutHistory = stringPreferencesKey("workout_history")
        val activeAlarmId = stringPreferencesKey("active_alarm_id")
        val dailyGrantSeconds = intPreferencesKey("daily_grant_seconds")
        val lastDailyGrantDayStart = longPreferencesKey("last_daily_grant_day_start")
        val poseProfiles = stringPreferencesKey("pose_profiles")
        fun exerciseSecondsKey(exercise: ExerciseType) =
            intPreferencesKey("exercise_seconds_${exercise.name.lowercase()}")
    }

    val settingsFlow: Flow<SettingsState> = context.dataStore.data.map { prefs ->
        val exerciseSeconds = ExerciseType.entries.associateWith { exercise ->
            prefs[Keys.exerciseSecondsKey(exercise)] ?: exercise.defaultSecondsPerRep
        }
        val rules = parseAppRules(prefs[Keys.appRules].orEmpty())
        val repAlarms = parseRepAlarms(prefs[Keys.repAlarms].orEmpty())
        val exerciseStats = parseExerciseStats(prefs[Keys.exerciseStats].orEmpty())
        val workoutHistory = parseWorkoutHistory(prefs[Keys.workoutHistory].orEmpty())
        val activeAlarmId = prefs[Keys.activeAlarmId]
        SettingsState(
            globalCreditsSeconds = prefs[Keys.globalCreditsSeconds] ?: 0L,
            dailyGrantSeconds = prefs[Keys.dailyGrantSeconds] ?: 0,
            exerciseSeconds = exerciseSeconds,
            exerciseStats = exerciseStats,
            workoutHistory = workoutHistory,
            appRules = rules,
            repAlarms = repAlarms,
            activeAlarmId = activeAlarmId
        )
    }

    suspend fun updateExerciseSeconds(exercise: ExerciseType, seconds: Int) {
        val sanitized = seconds.coerceIn(1, 3600)
        context.dataStore.edit { prefs ->
            prefs[Keys.exerciseSecondsKey(exercise)] = sanitized
        }
    }

    suspend fun updateDailyGrantSeconds(seconds: Int) {
        val sanitized = seconds.coerceIn(0, MAX_DAILY_GRANT_SECONDS)
        context.dataStore.edit { prefs ->
            prefs[Keys.dailyGrantSeconds] = sanitized
        }
    }

    suspend fun ensureDailyGrantApplied() {
        context.dataStore.edit { prefs ->
            val todayStart = dayStartMillis()
            val lastStart = prefs[Keys.lastDailyGrantDayStart] ?: 0L
            if (todayStart == lastStart) return@edit
            
            val grant = prefs[Keys.dailyGrantSeconds] ?: 0
            val history = parseWorkoutHistory(prefs[Keys.workoutHistory].orEmpty())
            val earnedToday = history.find { it.dayStartMillis == todayStart }?.creditsEarnedSeconds ?: 0L
            
            prefs[Keys.globalCreditsSeconds] = grant.toLong() + earnedToday
            prefs[Keys.lastDailyGrantDayStart] = todayStart
        }
    }

    suspend fun updateAppRule(packageName: String, enabled: Boolean, mode: AppBlockMode) {
        context.dataStore.edit { prefs ->
            val current = parseAppRules(prefs[Keys.appRules].orEmpty()).toMutableMap()
            if (enabled) {
                current[packageName] = normalizeMode(packageName, mode)
            } else {
                current.remove(packageName)
            }
            prefs[Keys.appRules] = serializeAppRules(current)
        }
    }

    suspend fun updateAppMode(packageName: String, mode: AppBlockMode) {
        context.dataStore.edit { prefs ->
            val current = parseAppRules(prefs[Keys.appRules].orEmpty()).toMutableMap()
            if (current.containsKey(packageName)) {
                current[packageName] = normalizeMode(packageName, mode)
                prefs[Keys.appRules] = serializeAppRules(current)
            }
        }
    }

    suspend fun updateGlobalCreditsSeconds(seconds: Long) {
        val sanitized = seconds.coerceAtLeast(0L)
        context.dataStore.edit { prefs ->
            prefs[Keys.globalCreditsSeconds] = sanitized
        }
    }

    suspend fun addCreditsSeconds(seconds: Long): Long {
        val sanitized = seconds.coerceAtLeast(0L)
        var updated = 0L
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.globalCreditsSeconds] ?: 0L
            updated = current + sanitized
            prefs[Keys.globalCreditsSeconds] = updated
        }
        return updated
    }

    suspend fun recordWorkoutStarted(exercise: ExerciseType) {
        context.dataStore.edit { prefs ->
            val current = parseExerciseStats(prefs[Keys.exerciseStats].orEmpty()).toMutableMap()
            val stats = current[exercise] ?: ExerciseStats()
            // Planks count as 1 "Movement Unit" per session
            val unitsDelta = if (exercise == ExerciseType.PLANK) 1L else 0L
            current[exercise] = stats.copy(
                sessions = stats.sessions + 1L,
                units = stats.units + unitsDelta
            )
            prefs[Keys.exerciseStats] = serializeExerciseStats(current)

            val dayStart = dayStartMillis()
            val history = upsertWorkoutHistory(
                current = parseWorkoutHistory(prefs[Keys.workoutHistory].orEmpty()),
                dayStartMillis = dayStart,
                sessionsDelta = 1L,
                unitsDelta = unitsDelta,
                creditsDelta = 0L
            )
            prefs[Keys.workoutHistory] = serializeWorkoutHistory(history)
        }
    }

    suspend fun addWorkoutCredits(exercise: ExerciseType, units: Long, creditsSeconds: Long): Long {
        val sanitizedUnits = units.coerceAtLeast(0L)
        val sanitizedCredits = creditsSeconds.coerceAtLeast(0L)
        var updatedCredits = 0L
        context.dataStore.edit { prefs ->
            val currentCredits = prefs[Keys.globalCreditsSeconds] ?: 0L
            updatedCredits = currentCredits + sanitizedCredits
            prefs[Keys.globalCreditsSeconds] = updatedCredits

            val currentStats = parseExerciseStats(prefs[Keys.exerciseStats].orEmpty()).toMutableMap()
            val stats = currentStats[exercise] ?: ExerciseStats()
            currentStats[exercise] = stats.copy(
                units = stats.units + sanitizedUnits,
                creditsEarnedSeconds = stats.creditsEarnedSeconds + sanitizedCredits
            )
            prefs[Keys.exerciseStats] = serializeExerciseStats(currentStats)

            val history = upsertWorkoutHistory(
                current = parseWorkoutHistory(prefs[Keys.workoutHistory].orEmpty()),
                dayStartMillis = dayStartMillis(),
                sessionsDelta = 0L,
                unitsDelta = sanitizedUnits,
                creditsDelta = sanitizedCredits
            )
            prefs[Keys.workoutHistory] = serializeWorkoutHistory(history)
        }
        return updatedCredits
    }

    suspend fun consumeCreditsSeconds(seconds: Long): Long {
        val sanitized = seconds.coerceAtLeast(0L)
        var remaining = 0L
        context.dataStore.edit { prefs ->
            val todayStart = dayStartMillis()
            val lastStart = prefs[Keys.lastDailyGrantDayStart] ?: 0L
            if (todayStart != lastStart) {
                val grant = prefs[Keys.dailyGrantSeconds] ?: 0
                val history = parseWorkoutHistory(prefs[Keys.workoutHistory].orEmpty())
                val earnedToday = history.find { it.dayStartMillis == todayStart }?.creditsEarnedSeconds ?: 0L
                prefs[Keys.globalCreditsSeconds] = grant.toLong() + earnedToday
                prefs[Keys.lastDailyGrantDayStart] = todayStart
            }

            val current = prefs[Keys.globalCreditsSeconds] ?: 0L
            val updated = (current - sanitized).coerceAtLeast(0L)
            prefs[Keys.globalCreditsSeconds] = updated
            remaining = updated
        }
        return remaining
    }

    suspend fun upsertRepAlarm(alarm: RepAlarm) {
        context.dataStore.edit { prefs ->
            val current = parseRepAlarms(prefs[Keys.repAlarms].orEmpty()).toMutableList()
            val index = current.indexOfFirst { it.id == alarm.id }
            if (index >= 0) current[index] = alarm else current.add(alarm)
            prefs[Keys.repAlarms] = serializeRepAlarms(current)
        }
    }

    suspend fun deleteRepAlarm(alarmId: String) {
        context.dataStore.edit { prefs ->
            val current = parseRepAlarms(prefs[Keys.repAlarms].orEmpty())
                .filterNot { it.id == alarmId }
            prefs[Keys.repAlarms] = serializeRepAlarms(current)
            if (prefs[Keys.activeAlarmId] == alarmId) {
                prefs.remove(Keys.activeAlarmId)
            }
        }
    }

    suspend fun setRepAlarmEnabled(alarmId: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = parseRepAlarms(prefs[Keys.repAlarms].orEmpty()).toMutableList()
            val index = current.indexOfFirst { it.id == alarmId }
            if (index >= 0) {
                current[index] = current[index].copy(enabled = enabled)
                prefs[Keys.repAlarms] = serializeRepAlarms(current)
            }
        }
    }

    suspend fun setActiveAlarmId(alarmId: String?) {
        context.dataStore.edit { prefs ->
            if (alarmId.isNullOrBlank()) {
                prefs.remove(Keys.activeAlarmId)
            } else {
                prefs[Keys.activeAlarmId] = alarmId
            }
        }
    }

    suspend fun getAlarmById(alarmId: String): RepAlarm? {
        return settingsFlow.first().repAlarms.firstOrNull { it.id == alarmId }
    }

    suspend fun snapshotRepAlarms(): List<RepAlarm> {
        return settingsFlow.first().repAlarms
    }

    suspend fun getPoseProfile(exercise: ExerciseType): String? {
        val prefs = context.dataStore.data.first()
        return parsePoseProfiles(prefs[Keys.poseProfiles].orEmpty())[exercise]
    }

    suspend fun updatePoseProfile(exercise: ExerciseType, encodedProfile: String) {
        context.dataStore.edit { prefs ->
            val current = parsePoseProfiles(prefs[Keys.poseProfiles].orEmpty()).toMutableMap()
            current[exercise] = encodedProfile
            prefs[Keys.poseProfiles] = serializePoseProfiles(current)
        }
    }

    private fun parseAppRules(raw: String): Map<String, AppBlockMode> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split('|', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val packageName = parts[0]
            val mode = runCatching { AppBlockMode.valueOf(parts[1]) }.getOrNull()
            if (packageName.isBlank() || mode == null) null else packageName to mode
        }.toMap()
    }

    private fun parsePoseProfiles(raw: String): Map<ExerciseType, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split('|', limit = 2)
            if (parts.size != 2) return@mapNotNull null
            val exercise = runCatching { ExerciseType.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
            val payload = parts[1].takeIf { it.isNotBlank() } ?: return@mapNotNull null
            exercise to payload
        }.toMap()
    }

    private fun serializePoseProfiles(map: Map<ExerciseType, String>): String {
        if (map.isEmpty()) return ""
        return map.entries
            .sortedBy { it.key.name }
            .joinToString(";") { (exercise, payload) -> "${exercise.name}|$payload" }
    }

    private fun serializeAppRules(rules: Map<String, AppBlockMode>): String {
        if (rules.isEmpty()) return ""
        return rules.toSortedMap().entries.joinToString(";") { (pkg, mode) ->
            "$pkg|${mode.name}"
        }
    }

    private fun parseExerciseStats(raw: String): Map<ExerciseType, ExerciseStats> {
        val stats = ExerciseType.entries.associateWith { ExerciseStats() }.toMutableMap()
        if (raw.isBlank()) return stats
        raw.split(";").forEach { entry ->
            val parts = entry.split('|')
            if (parts.size != 4) return@forEach
            val exercise = runCatching { ExerciseType.valueOf(parts[0]) }.getOrNull() ?: return@forEach
            val sessions = parts[1].toLongOrNull() ?: return@forEach
            val units = parts[2].toLongOrNull() ?: return@forEach
            val credits = parts[3].toLongOrNull() ?: return@forEach
            stats[exercise] = ExerciseStats(
                sessions = sessions.coerceAtLeast(0L),
                units = units.coerceAtLeast(0L),
                creditsEarnedSeconds = credits.coerceAtLeast(0L)
            )
        }
        return stats
    }

    private fun parseWorkoutHistory(raw: String): List<WorkoutHistoryEntry> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size != 4) return@mapNotNull null
            val dayStartMillis = parts[0].toLongOrNull() ?: return@mapNotNull null
            val sessions = parts[1].toLongOrNull()?.coerceAtLeast(0L) ?: return@mapNotNull null
            val units = parts[2].toLongOrNull()?.coerceAtLeast(0L) ?: return@mapNotNull null
            val credits = parts[3].toLongOrNull()?.coerceAtLeast(0L) ?: return@mapNotNull null
            WorkoutHistoryEntry(
                dayStartMillis = dayStartMillis,
                sessions = sessions,
                units = units,
                creditsEarnedSeconds = credits
            )
        }.sortedBy { it.dayStartMillis }
    }

    private fun serializeWorkoutHistory(history: List<WorkoutHistoryEntry>): String {
        if (history.isEmpty()) return ""
        return history
            .sortedBy { it.dayStartMillis }
            .takeLast(MAX_WORKOUT_HISTORY_DAYS)
            .joinToString(";") { entry ->
                listOf(
                    entry.dayStartMillis.toString(),
                    entry.sessions.toString(),
                    entry.units.toString(),
                    entry.creditsEarnedSeconds.toString()
                ).joinToString("|")
            }
    }

    private fun upsertWorkoutHistory(
        current: List<WorkoutHistoryEntry>,
        dayStartMillis: Long,
        sessionsDelta: Long,
        unitsDelta: Long,
        creditsDelta: Long
    ): List<WorkoutHistoryEntry> {
        val entries = current.toMutableList()
        val index = entries.indexOfFirst { it.dayStartMillis == dayStartMillis }
        if (index >= 0) {
            val existing = entries[index]
            entries[index] = existing.copy(
                sessions = existing.sessions + sessionsDelta.coerceAtLeast(0L),
                units = existing.units + unitsDelta.coerceAtLeast(0L),
                creditsEarnedSeconds = existing.creditsEarnedSeconds + creditsDelta.coerceAtLeast(0L)
            )
        } else if (sessionsDelta > 0L || unitsDelta > 0L || creditsDelta > 0L) {
            entries.add(
                WorkoutHistoryEntry(
                    dayStartMillis = dayStartMillis,
                    sessions = sessionsDelta.coerceAtLeast(0L),
                    units = unitsDelta.coerceAtLeast(0L),
                    creditsEarnedSeconds = creditsDelta.coerceAtLeast(0L)
                )
            )
        }
        return entries
            .sortedBy { it.dayStartMillis }
            .takeLast(MAX_WORKOUT_HISTORY_DAYS)
    }

    private fun dayStartMillis(nowMillis: Long = System.currentTimeMillis()): Long {
        return Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    private fun serializeExerciseStats(stats: Map<ExerciseType, ExerciseStats>): String {
        return ExerciseType.entries.joinToString(";") { exercise ->
            val value = stats[exercise] ?: ExerciseStats()
            listOf(
                exercise.name,
                value.sessions.toString(),
                value.units.toString(),
                value.creditsEarnedSeconds.toString()
            ).joinToString("|")
        }
    }

    private fun parseRepAlarms(raw: String): List<RepAlarm> {
        if (raw.isBlank()) return emptyList()
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            return runCatching {
                val array = JSONArray(trimmed)
                buildList {
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val id = obj.optString("id")
                        val hour = obj.optInt("hour24", -1)
                        val minute = obj.optInt("minute", -1)
                        val exercise = runCatching { ExerciseType.valueOf(obj.optString("exercise")) }.getOrNull()
                        val reps = obj.optInt("targetReps", -1)
                        val enabled = obj.optBoolean("enabled", false)
                        val snoozeEnabled = obj.optBoolean("snoozeEnabled", true)
                        val soundUri = obj.optString("soundUri").takeIf { it.isNotBlank() }
                        val scheduleMode = obj.optString("scheduleMode").takeIf { it.isNotBlank() }
                            ?.let { runCatching { AlarmScheduleMode.valueOf(it) }.getOrNull() }
                            ?: AlarmScheduleMode.DAILY
                        val daysArray = obj.optJSONArray("daysOfWeek")
                        val daysOfWeek = buildSet {
                            if (daysArray != null) {
                                for (j in 0 until daysArray.length()) {
                                    val day = daysArray.optInt(j, -1)
                                    if (day in Calendar.SUNDAY..Calendar.SATURDAY) add(day)
                                }
                            }
                        }
                        val specificDateMillis = obj.optLong("specificDateMillis", Long.MIN_VALUE)
                            .takeIf { it != Long.MIN_VALUE }
                        if (id.isBlank() || hour !in 0..23 || minute !in 0..59 || exercise == null || reps < 1) {
                            continue
                        }
                        add(
                            RepAlarm(
                                id = id,
                                hour24 = hour,
                                minute = minute,
                                exercise = exercise,
                                targetReps = reps.coerceIn(1, 500),
                                enabled = enabled,
                                snoozeEnabled = snoozeEnabled,
                                soundUri = soundUri,
                                scheduleMode = scheduleMode,
                                daysOfWeek = daysOfWeek,
                                specificDateMillis = specificDateMillis
                            )
                        )
                    }
                }.sortedWith(compareBy<RepAlarm> { it.hour24 }.thenBy { it.minute })
            }.getOrElse {
                parseLegacyRepAlarms(trimmed)
            }
        }
        return parseLegacyRepAlarms(trimmed)
    }

    private fun parseLegacyRepAlarms(raw: String): List<RepAlarm> {
        return raw.split(";").mapNotNull { entry ->
            val parts = entry.split('|')
            if (parts.size < 6) return@mapNotNull null
            val id = parts[0]
            val hour = parts[1].toIntOrNull()
            val minute = parts[2].toIntOrNull()
            val exercise = runCatching { ExerciseType.valueOf(parts[3]) }.getOrNull()
            val reps = parts[4].toIntOrNull()
            val enabled = parts[5] == "1"
            val hasSnoozeField = parts.getOrNull(6) == "0" || parts.getOrNull(6) == "1"
            val snoozeEnabled = if (hasSnoozeField) {
                parts.getOrNull(6) == "1"
            } else {
                true
            }
            val soundUriIndex = if (hasSnoozeField) 7 else 6
            val scheduleModeIndex = if (hasSnoozeField) 8 else 7
            val daysOfWeekIndex = if (hasSnoozeField) 9 else 8
            val specificDateMillisIndex = if (hasSnoozeField) 10 else 9
            val soundUri = parts.getOrNull(soundUriIndex)?.takeIf { it.isNotBlank() }
            val scheduleMode = parts.getOrNull(scheduleModeIndex)
                ?.let { runCatching { AlarmScheduleMode.valueOf(it) }.getOrNull() }
                ?: AlarmScheduleMode.DAILY
            val daysOfWeek = parts.getOrNull(daysOfWeekIndex)
                ?.split(',')
                ?.mapNotNull { it.toIntOrNull() }
                ?.filter { it in java.util.Calendar.SUNDAY..java.util.Calendar.SATURDAY }
                ?.toSet()
                ?: emptySet()
            val specificDateMillis = parts.getOrNull(specificDateMillisIndex)?.toLongOrNull()
            if (id.isBlank() || hour == null || minute == null || exercise == null || reps == null) {
                null
            } else {
                RepAlarm(
                    id = id,
                    hour24 = hour.coerceIn(0, 23),
                    minute = minute.coerceIn(0, 59),
                    exercise = exercise,
                    targetReps = reps.coerceIn(1, 500),
                    enabled = enabled,
                    snoozeEnabled = snoozeEnabled,
                    soundUri = soundUri,
                    scheduleMode = scheduleMode,
                    daysOfWeek = daysOfWeek,
                    specificDateMillis = specificDateMillis
                )
            }
        }.sortedWith(compareBy<RepAlarm> { it.hour24 }.thenBy { it.minute })
    }

    private fun serializeRepAlarms(alarms: List<RepAlarm>): String {
        if (alarms.isEmpty()) return ""
        val sorted = alarms.sortedWith(compareBy<RepAlarm> { it.hour24 }.thenBy { it.minute })
        return JSONArray().apply {
            sorted.forEach { alarm ->
                put(
                    JSONObject().apply {
                        put("id", alarm.id)
                        put("hour24", alarm.hour24)
                        put("minute", alarm.minute)
                        put("exercise", alarm.exercise.name)
                        put("targetReps", alarm.targetReps)
                        put("enabled", alarm.enabled)
                        put("snoozeEnabled", alarm.snoozeEnabled)
                        put("soundUri", alarm.soundUri ?: "")
                        put("scheduleMode", alarm.scheduleMode.name)
                        put("daysOfWeek", JSONArray(alarm.normalizedDaysOfWeek().sorted()))
                        if (alarm.specificDateMillis != null) {
                            put("specificDateMillis", alarm.specificDateMillis)
                        }
                    }
                )
            }
        }.toString()
    }

    private fun normalizeMode(packageName: String, mode: AppBlockMode): AppBlockMode {
        return if (packageName == "com.instagram.android") mode else AppBlockMode.ALL
    }
}
