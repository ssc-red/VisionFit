package com.example.visionfit.ui

import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.visionfit.alarm.RepAlarmScheduler
import com.example.visionfit.model.AlarmScheduleMode
import com.example.visionfit.model.ExerciseType
import com.example.visionfit.model.RepAlarm
import com.example.visionfit.ui.theme.Coral500
import com.example.visionfit.ui.theme.Ink100
import com.example.visionfit.ui.theme.Ink200
import com.example.visionfit.ui.theme.Ink300
import com.example.visionfit.ui.theme.Ink500
import com.example.visionfit.ui.theme.Ink600
import com.example.visionfit.ui.theme.Ink700
import com.example.visionfit.ui.theme.Ink800
import com.example.visionfit.ui.theme.Ink900
import com.example.visionfit.ui.theme.Lime300
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.UUID

private val AlarmBackground = Ink900
private val AlarmPanel = Ink800
private val AlarmPanelAlt = Ink700
private val AlarmPanelRaised = Ink600
private val AlarmText = Color.White
private val AlarmMuted = Ink200
private val AlarmFaint = Ink500
private val AlarmAccent = Lime300
private val AlarmAccentSoft = Ink700

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AlarmsScreen(
    alarms: List<RepAlarm>,
    onAddAlarm: (RepAlarm) -> Unit,
    onDeleteAlarm: (String) -> Unit,
    onToggleAlarm: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val alarmExercises = remember { ExerciseType.entries.filter { it != ExerciseType.PLANK } }
    val defaultExercise = alarmExercises.firstOrNull() ?: ExerciseType.entries.first()
    val defaultSoundUri = remember {
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.toString()
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)?.toString()
    }
    val enabledAlarms = alarms
        .filter { it.enabled }
        .sortedBy { secondsUntil(it) ?: Long.MAX_VALUE }
    val disabledAlarms = alarms
        .filterNot { it.enabled }
        .sortedWith(compareBy<RepAlarm> { it.hour24 }.thenBy { it.minute })
    val nextAlarm = enabledAlarms.firstOrNull { secondsUntil(it) != null }

    var editorAlarmId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorHour by rememberSaveable { mutableStateOf(7) }
    var editorMinute by rememberSaveable { mutableStateOf(0) }
    var editorReps by rememberSaveable { mutableStateOf(20) }
    var editorExerciseName by rememberSaveable { mutableStateOf(defaultExercise.name) }
    var editorSoundUri by rememberSaveable { mutableStateOf(defaultSoundUri) }
    var editorEnabled by rememberSaveable { mutableStateOf(true) }
    var editorSnoozeEnabled by rememberSaveable { mutableStateOf(true) }
    var editorScheduleName by rememberSaveable { mutableStateOf(AlarmScheduleMode.DAILY.name) }
    var editorDaysCsv by rememberSaveable { mutableStateOf(defaultWeekdaySet().toDayCsv()) }
    var editorDateMillis by rememberSaveable { mutableStateOf(0L) }

    val editingAlarm = editorAlarmId?.let { alarmId -> alarms.firstOrNull { it.id == alarmId } }

    fun openEditor(alarm: RepAlarm?) {
        if (alarm == null) {
            editorAlarmId = UUID.randomUUID().toString()
            editorHour = 7
            editorMinute = 0
            editorReps = 20
            editorExerciseName = defaultExercise.name
            editorSoundUri = defaultSoundUri
            editorEnabled = true
            editorSnoozeEnabled = true
            editorScheduleName = AlarmScheduleMode.DAILY.name
            editorDaysCsv = defaultWeekdaySet().toDayCsv()
            editorDateMillis = defaultDateFor(7, 0)
        } else {
            editorAlarmId = alarm.id
            editorHour = alarm.hour24
            editorMinute = alarm.minute
            editorReps = alarm.targetReps
            editorExerciseName = alarm.exercise.name
            editorSoundUri = alarm.soundUri ?: defaultSoundUri
            editorEnabled = alarm.enabled
            editorSnoozeEnabled = alarm.snoozeEnabled
            editorScheduleName = alarm.scheduleMode.name
            editorDaysCsv = alarm.normalizedDaysOfWeek().ifEmpty { defaultWeekdaySet() }.toDayCsv()
            editorDateMillis = alarm.specificDateMillis ?: defaultDateFor(alarm.hour24, alarm.minute)
        }
    }

    fun closeEditor() {
        editorAlarmId = null
    }

    fun saveEditor() {
        val exercise = alarmExercises.firstOrNull { it.name == editorExerciseName } ?: defaultExercise
        val mode = runCatching { AlarmScheduleMode.valueOf(editorScheduleName) }.getOrDefault(AlarmScheduleMode.DAILY)
        val selectedDays = editorDaysCsv.toDaySet()
        onAddAlarm(
            RepAlarm(
                id = editorAlarmId ?: UUID.randomUUID().toString(),
                hour24 = editorHour.coerceIn(0, 23),
                minute = editorMinute.coerceIn(0, 59),
                exercise = exercise,
                targetReps = editorReps.coerceIn(1, 500),
                enabled = editorEnabled,
                snoozeEnabled = editorSnoozeEnabled,
                soundUri = editorSoundUri,
                scheduleMode = if (mode == AlarmScheduleMode.ONCE) AlarmScheduleMode.ONCE else AlarmScheduleMode.WEEKLY,
                daysOfWeek = if (mode == AlarmScheduleMode.ONCE) emptySet() else selectedDays,
                specificDateMillis = if (mode == AlarmScheduleMode.ONCE) editorDateMillis.takeIf { it > 0L } else null
            )
        )
        closeEditor()
    }

    val soundPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val pickedUri = if (Build.VERSION.SDK_INT >= 33) {
            result.data?.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        editorSoundUri = pickedUri?.toString() ?: editorSoundUri
    }

    if (editorAlarmId != null) {
        val mode = runCatching { AlarmScheduleMode.valueOf(editorScheduleName) }.getOrDefault(AlarmScheduleMode.DAILY)
        val selectedDays = editorDaysCsv.toDaySet()
        AlarmEditorScreen(
            isEditing = editingAlarm != null,
            hour = editorHour,
            onHourChange = { editorHour = it },
            minute = editorMinute,
            onMinuteChange = { editorMinute = it },
            reps = editorReps,
            onRepsChange = { editorReps = it },
            exercise = alarmExercises.firstOrNull { it.name == editorExerciseName } ?: defaultExercise,
            onCycleExercise = {
                if (alarmExercises.isNotEmpty()) {
                    val currentIndex = alarmExercises.indexOfFirst { it.name == editorExerciseName }
                    val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % alarmExercises.size else 0
                    editorExerciseName = alarmExercises[nextIndex].name
                }
            },
            soundLabel = soundLabel(context, editorSoundUri),
            onPickSound = {
                val intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                    putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                    putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, editorSoundUri?.let(Uri::parse))
                }
                soundPicker.launch(intent)
            },
            enabled = editorEnabled,
            onEnabledChange = { editorEnabled = it },
            snoozeEnabled = editorSnoozeEnabled,
            onSnoozeEnabledChange = { editorSnoozeEnabled = it },
            scheduleMode = mode,
            onScheduleModeChange = {
                editorScheduleName = it.name
                if (it == AlarmScheduleMode.ONCE && editorDateMillis == 0L) {
                    editorDateMillis = defaultDateFor(editorHour, editorMinute)
                }
            },
            selectedDays = selectedDays,
            onToggleDay = { day ->
                val updated = selectedDays.toMutableSet().apply {
                    if (!add(day)) remove(day)
                }
                editorDaysCsv = updated.toDayCsv()
            },
            dateMillis = editorDateMillis.takeIf { it > 0L } ?: defaultDateFor(editorHour, editorMinute),
            onDateChange = { editorDateMillis = it },
            onCancel = { closeEditor() },
            onDelete = editingAlarm?.let {
                {
                    onDeleteAlarm(it.id)
                    closeEditor()
                }
            },
            onSave = { saveEditor() },
            modifier = modifier
        )
        return
    }

    androidx.compose.material3.Scaffold(
        modifier = modifier.fillMaxSize(),
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openEditor(null) },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("Add alarm") }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                AlarmSummary(
                    nextAlarm = nextAlarm,
                    enabledCount = enabledAlarms.size,
                    disabledCount = disabledAlarms.size,
                    onAddAlarm = { openEditor(null) }
                )
            }

            if (alarms.isEmpty()) {
                item {
                    EmptyAlarmState(onAddAlarm = { openEditor(null) })
                }
            } else {
                if (enabledAlarms.isNotEmpty()) {
                    stickyHeader {
                        SectionHeader(title = "Active", count = enabledAlarms.size)
                    }
                    items(enabledAlarms, key = { it.id }) { alarm ->
                        AlarmCard(
                            alarm = alarm,
                            context = context,
                            onEdit = { openEditor(alarm) },
                            onDelete = { onDeleteAlarm(alarm.id) },
                            onToggle = { onToggleAlarm(alarm.id, it) }
                        )
                    }
                }

                if (disabledAlarms.isNotEmpty()) {
                    stickyHeader {
                        SectionHeader(title = "Off", count = disabledAlarms.size)
                    }
                    items(disabledAlarms, key = { it.id }) { alarm ->
                        AlarmCard(
                            alarm = alarm,
                            context = context,
                            onEdit = { openEditor(alarm) },
                            onDelete = { onDeleteAlarm(alarm.id) },
                            onToggle = { onToggleAlarm(alarm.id, it) }
                        )
                    }
                }
            }
        }
    }

}

@Composable
private fun AlarmSummary(
    nextAlarm: RepAlarm?,
    enabledCount: Int,
    disabledCount: Int,
    onAddAlarm: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = AlarmPanel,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Rounded.Alarm,
                    contentDescription = null,
                    tint = AlarmAccent
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Workout alarms",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                    Text(
                        text = "$enabledCount active, $disabledCount off",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFD9D9E2)
                    )
                }
                SummaryActionButton(onClick = onAddAlarm)
            }

            if (nextAlarm != null) {
                Text(
                    text = "Next alarm in ${formatDuration(secondsUntil(nextAlarm) ?: 0L)}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color(0xFFD9D9E2)
                )
                Text(
                    text = nextAlarm.timeLabel(),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
                Text(
                    text = "${nextAlarm.scheduleLabel()} - ${nextAlarm.exercise.displayName}, ${nextAlarm.targetReps} reps",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD9D9E2)
                )
            } else {
                Text(
                    text = "No active alarms are scheduled.",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    text = "Add a time, workout target, repeat days, or a one-time date.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD9D9E2)
                )
            }
        }
    }
}

@Composable
private fun SummaryActionButton(onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = AlarmAccent,
        modifier = Modifier
            .height(44.dp)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = Ink900)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "New",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Ink900
            )
        }
    }
}

@Composable
private fun EmptyAlarmState(onAddAlarm: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = AlarmPanelAlt,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Icon(Icons.Rounded.Alarm, contentDescription = null, tint = AlarmAccent)
            Text(
                text = "No alarms yet",
                style = MaterialTheme.typography.titleLarge,
                color = AlarmText
            )
            Text(
                text = "Create a rep alarm that rings daily, on selected days, or on a specific date.",
                style = MaterialTheme.typography.bodyMedium,
                color = AlarmMuted,
                textAlign = TextAlign.Center
            )
            FilledTonalButton(onClick = onAddAlarm) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add alarm")
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = AlarmAccent
            )
        }
    }
}

@Composable
private fun AlarmCard(
    alarm: RepAlarm,
    context: android.content.Context,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    val enabled = alarm.enabled
    val next = secondsUntil(alarm)
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (enabled) AlarmAccentSoft else AlarmPanelAlt,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = alarm.timeLabel(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Black,
                        color = AlarmText
                    )
                    Text(
                        text = alarm.scheduleLabel(),
                        style = MaterialTheme.typography.titleMedium,
                        color = AlarmText
                    )
                    Text(
                        text = if (enabled && next != null) {
                            "Rings in ${formatDuration(next)}"
                        } else if (enabled) {
                            "Pick a future date to schedule this alarm"
                        } else {
                            "Turn on to schedule"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = AlarmMuted
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Ink900,
                        checkedTrackColor = AlarmAccent,
                        uncheckedThumbColor = Ink100,
                        uncheckedTrackColor = Ink500
                    )
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Pill(text = alarm.exercise.displayName, modifier = Modifier.weight(1f))
                    Pill(text = "${alarm.targetReps} reps")
                }
                Pill(text = soundLabel(context, alarm.soundUri), modifier = Modifier.fillMaxWidth())
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) {
                    Text("Edit")
                }
                TextButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(50),
        color = AlarmPanel,
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = AlarmMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun AlarmEditorScreen(
    isEditing: Boolean,
    hour: Int,
    onHourChange: (Int) -> Unit,
    minute: Int,
    onMinuteChange: (Int) -> Unit,
    reps: Int,
    onRepsChange: (Int) -> Unit,
    exercise: ExerciseType,
    onCycleExercise: () -> Unit,
    soundLabel: String,
    onPickSound: () -> Unit,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    snoozeEnabled: Boolean,
    onSnoozeEnabledChange: (Boolean) -> Unit,
    scheduleMode: AlarmScheduleMode,
    onScheduleModeChange: (AlarmScheduleMode) -> Unit,
    selectedDays: Set<Int>,
    onToggleDay: (Int) -> Unit,
    dateMillis: Long,
    onDateChange: (Long) -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hour12 = hour.toHour12()
    val isPm = hour >= 12
    val isDateSchedule = scheduleMode == AlarmScheduleMode.ONCE
    val oneTimeIsPast = scheduleMode == AlarmScheduleMode.ONCE && oneTimeTriggerMillis(dateMillis, hour, minute) <= System.currentTimeMillis()
    val weeklyNeedsDay = !isDateSchedule && selectedDays.isEmpty()
    val canSave = !oneTimeIsPast && !weeklyNeedsDay

    Surface(
        modifier = modifier.fillMaxSize(),
        color = AlarmBackground
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCancel) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close")
                }
                Text(
                    text = if (isEditing) "Edit alarm" else "Add alarm",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = AlarmText,
                    modifier = Modifier.weight(1f)
                )
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete")
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                NumberWheel(
                    value = hour,
                    values = (0..23).toList(),
                    label = { "%02d".format(it.toHour12()) },
                    onValueChange = { onHourChange(it.coerceIn(0, 23)) },
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = ":",
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Black,
                    color = AlarmText,
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
                NumberWheel(
                    value = minute,
                    values = (0..59).toList(),
                    label = { "%02d".format(it) },
                    onValueChange = { onMinuteChange(it) },
                    modifier = Modifier.weight(1f)
                )
                Column(
                    modifier = Modifier
                        .width(72.dp)
                        .padding(start = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    PeriodChip(text = "AM", selected = !isPm) {
                        if (isPm) onHourChange(hour - 12)
                    }
                    PeriodChip(text = "PM", selected = isPm) {
                        if (!isPm) onHourChange(hour + 12)
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp),
                color = AlarmPanel,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ScheduleTab("Repeat", !isDateSchedule, Modifier.weight(1f)) {
                            onScheduleModeChange(AlarmScheduleMode.WEEKLY)
                        }
                        ScheduleTab("Specific date", isDateSchedule, Modifier.weight(1f)) {
                            onScheduleModeChange(AlarmScheduleMode.ONCE)
                        }
                    }

                    if (isDateSchedule) {
                        DateRow(dateMillis = dateMillis, onDateChange = onDateChange)
                    } else {
                        WeekdaySelector(
                            selectedDays = selectedDays,
                            onToggleDay = onToggleDay
                        )
                    }

                    if (weeklyNeedsDay) {
                        Text(
                            text = "Choose at least one day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (oneTimeIsPast) {
                        Text(
                            text = "Choose a future date and time.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    HorizontalDivider(color = AlarmFaint)

                    EditorListRow(
                        title = "Workout target",
                        subtitle = "${exercise.displayName} - ${reps.coerceIn(1, 500)} reps",
                        action = "Change",
                        onClick = onCycleExercise
                    )

                    OutlinedTextField(
                        value = reps.toString(),
                        onValueChange = { onRepsChange(it.filter(Char::isDigit).take(3).toIntOrNull() ?: reps) },
                        label = { Text("Required reps") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AlarmText,
                            unfocusedTextColor = AlarmText,
                            focusedLabelColor = AlarmAccent,
                            unfocusedLabelColor = AlarmMuted,
                            cursorColor = AlarmAccent,
                            focusedBorderColor = AlarmAccent,
                            unfocusedBorderColor = AlarmFaint,
                            focusedContainerColor = AlarmPanelAlt,
                            unfocusedContainerColor = AlarmPanelAlt
                        )
                    )

                    HorizontalDivider(color = AlarmFaint)

                    EditorListRow(
                        title = "Alarm sound",
                        subtitle = soundLabel,
                        action = "Pick",
                        onClick = onPickSound
                    )

                    EditorSwitchRow(
                        title = "Alarm enabled",
                        subtitle = if (enabled) "This alarm will ring when scheduled" else "Saved off until you switch it on",
                        checked = enabled,
                        onCheckedChange = onEnabledChange,
                        onClick = { onEnabledChange(!enabled) }
                    )

                    EditorSwitchRow(
                        title = "Allow snooze",
                        subtitle = if (snoozeEnabled) "Snooze button is available while ringing" else "Snooze is disabled and alarm must be completed",
                        checked = snoozeEnabled,
                        onCheckedChange = onSnoozeEnabledChange,
                        onClick = { onSnoozeEnabledChange(!snoozeEnabled) }
                    )

                    Spacer(modifier = Modifier.height(74.dp))
                }
            }

            Surface(color = AlarmPanel, modifier = Modifier.fillMaxWidth()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 22.dp)
                ) {
                    BottomActionButton(
                        text = "Cancel",
                        containerColor = AlarmPanelAlt,
                        contentColor = AlarmText,
                        onClick = onCancel,
                        modifier = Modifier.weight(1f)
                    )
                    BottomActionButton(
                        text = if (isEditing) "Apply changes" else "Add alarm",
                        containerColor = if (canSave) AlarmAccent else Ink500,
                        contentColor = if (canSave) Ink900 else Ink200,
                        enabled = canSave,
                        onClick = onSave,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomActionButton(
    text: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = containerColor,
        modifier = modifier
            .height(56.dp)
            .clickable(enabled = enabled, onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun NumberWheel(
    value: Int,
    values: List<Int>,
    label: (Int) -> String,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    rowHeight: Dp = 86.dp
) {
    val repeatedCount = values.size * 80
    val middleCycle = repeatedCount / 2 - (repeatedCount / 2 % values.size)
    val selectedOffset = values.indexOf(value).coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = middleCycle + selectedOffset)

    LaunchedEffect(value) {
        val current = listState.layoutInfo.visibleItemsInfo
            .minByOrNull { kotlin.math.abs((it.offset + it.size / 2) - (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2) }
            ?.index
        val currentValue = current?.let { values[it.floorMod(values.size)] }
        if (currentValue != value) {
            listState.scrollToItem(middleCycle + selectedOffset)
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val viewportCenter = (listState.layoutInfo.viewportStartOffset + listState.layoutInfo.viewportEndOffset) / 2
            val centered = listState.layoutInfo.visibleItemsInfo.minByOrNull {
                kotlin.math.abs((it.offset + it.size / 2) - viewportCenter)
            } ?: return@LaunchedEffect
            val centeredValue = values[centered.index.floorMod(values.size)]
            if (centeredValue != value) onValueChange(centeredValue)
            listState.animateScrollToItem(centered.index)
        }
    }

    Box(
        modifier = modifier
            .height(rowHeight * 3)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = AlarmPanelRaised,
            modifier = Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .padding(horizontal = 2.dp)
        ) {}

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = rowHeight),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(repeatedCount) { index ->
                val itemValue = values[index.floorMod(values.size)]
                val selected = itemValue == value
                Box(
                    modifier = Modifier
                        .height(rowHeight)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label(itemValue),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = if (selected) FontWeight.Black else FontWeight.Normal,
                        color = AlarmText,
                        modifier = Modifier.alpha(if (selected) 1f else 0.24f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = if (selected) AlarmAccent else AlarmPanelAlt,
        modifier = Modifier
            .size(width = 58.dp, height = 48.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (selected) Ink900 else AlarmMuted
            )
        }
    }
}

@Composable
private fun ScheduleTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = if (selected) AlarmAccent else AlarmPanelAlt,
        modifier = modifier
            .height(48.dp)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) Ink900 else AlarmMuted
            )
        }
    }
}

@Composable
private fun WeekdaySelector(
    selectedDays: Set<Int>,
    onToggleDay: (Int) -> Unit
) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        RepAlarm.WEEK_DAYS.forEach { day ->
            val selected = day.value in selectedDays
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selected) AlarmAccent else AlarmPanelAlt,
                modifier = Modifier
                    .size(42.dp)
                    .clickable { onToggleDay(day.value) }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    val isSunday = day.value == Calendar.SUNDAY
                    val unselectedColor = if (isSunday) Coral500 else AlarmText
                    Text(
                        text = day.chipLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Ink900 else unselectedColor
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorListRow(title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = AlarmText)
            Text(
                subtitle,
                style = MaterialTheme.typography.titleMedium,
                color = AlarmAccent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(action, style = MaterialTheme.typography.titleMedium, color = AlarmAccent)
    }
}

@Composable
private fun EditorSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = AlarmText)
            Text(
                subtitle,
                style = MaterialTheme.typography.titleMedium,
                color = AlarmMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Ink900,
                checkedTrackColor = AlarmAccent,
                uncheckedThumbColor = Ink100,
                uncheckedTrackColor = Ink500
            )
        )
    }
}

@Composable
private fun DateRow(dateMillis: Long, onDateChange: (Long) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = AlarmPanelAlt, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { onDateChange(addDays(dateMillis, -1)) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Previous day", tint = AlarmMuted)
            }
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Specific date", style = MaterialTheme.typography.labelMedium, color = AlarmMuted)
                Text(formatDate(dateMillis), style = MaterialTheme.typography.titleMedium, color = AlarmText, textAlign = TextAlign.Center)
            }
            IconButton(onClick = { onDateChange(addDays(dateMillis, 1)) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Next day", tint = AlarmMuted)
            }
        }
    }
}

private fun secondsUntil(alarm: RepAlarm): Long? {
    val next = RepAlarmScheduler.nextTriggerAtMillis(alarm) ?: return null
    return ((next - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
}

private fun formatDuration(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun soundLabel(context: android.content.Context, uriString: String?): String {
    val uri = uriString?.let(Uri::parse) ?: return "Default"
    return runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }
        .getOrNull()
        ?: "Default"
}

private fun Int.toHour12(): Int {
    return when (val value = this % 12) {
        0 -> 12
        else -> value
    }
}

private fun Int.toHour24(isPm: Boolean): Int {
    val normalized = this.coerceIn(1, 12)
    return when {
        isPm && normalized == 12 -> 12
        isPm -> normalized + 12
        normalized == 12 -> 0
        else -> normalized
    }
}

private fun Int.floorMod(divisor: Int): Int {
    return ((this % divisor) + divisor) % divisor
}

private fun defaultWeekdaySet(): Set<Int> = RepAlarm.WEEK_DAYS.map { it.value }.toSet()

private fun Set<Int>.toDayCsv(): String {
    return filter { it in Calendar.SUNDAY..Calendar.SATURDAY }.sorted().joinToString(",")
}

private fun String.toDaySet(): Set<Int> {
    return split(',')
        .mapNotNull { it.toIntOrNull() }
        .filter { it in Calendar.SUNDAY..Calendar.SATURDAY }
        .toSet()
}

private fun defaultDateFor(hour: Int, minute: Int): Long {
    val candidate = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        set(Calendar.MINUTE, minute.coerceIn(0, 59))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    if (candidate.timeInMillis <= System.currentTimeMillis()) {
        candidate.add(Calendar.DAY_OF_YEAR, 1)
    }
    return startOfDay(candidate.timeInMillis)
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

private fun addDays(millis: Long, days: Int): Long {
    return Calendar.getInstance().apply {
        timeInMillis = millis
        add(Calendar.DAY_OF_YEAR, days)
    }.timeInMillis
}

private fun oneTimeTriggerMillis(dateMillis: Long, hour: Int, minute: Int): Long {
    return Calendar.getInstance().apply {
        timeInMillis = dateMillis
        set(Calendar.HOUR_OF_DAY, hour.coerceIn(0, 23))
        set(Calendar.MINUTE, minute.coerceIn(0, 59))
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}

private fun formatDate(millis: Long): String {
    return SimpleDateFormat("EEE, MMM d, yyyy", Locale.getDefault()).format(millis)
}
