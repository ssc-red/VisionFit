package com.example.visionfit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.visionfit.model.ExerciseStats
import com.example.visionfit.model.ExerciseType
import com.example.visionfit.model.SettingsState
import com.example.visionfit.util.WorkoutGraphBar
import com.example.visionfit.util.WorkoutGraphRange
import com.example.visionfit.util.buildWorkoutGraphBars

@Composable
fun StatsScreen(
    state: SettingsState,
    modifier: Modifier = Modifier
) {
    val stats = state.exerciseStats
    val totalSessions = stats.values.fold(0L) { acc, s -> acc + s.sessions }
    // Treat 1 plank session as 1 unit of movement, same as 1 rep of other exercises
    val totalUnits = stats.entries.fold(0L) { acc, entry -> 
        acc + (if (entry.key == ExerciseType.PLANK) entry.value.sessions else entry.value.units)
    }
    val totalEarned = stats.values.fold(0L) { acc, s -> acc + s.creditsEarnedSeconds }
    val bestExercise = stats.entries
        .maxByOrNull { (ex, s) -> if (ex == ExerciseType.PLANK) s.sessions else s.units }
        ?.takeIf { (ex, s) -> (if (ex == ExerciseType.PLANK) s.sessions else s.units) > 0L }
        ?.key
    var selectedRangeName by rememberSaveable { mutableStateOf(WorkoutGraphRange.WEEK.name) }
    val selectedRange = runCatching { WorkoutGraphRange.valueOf(selectedRangeName) }.getOrDefault(WorkoutGraphRange.WEEK)
    val chartBars = buildWorkoutGraphBars(selectedRange, state.workoutHistory)

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Stats",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Your all-time VisionFit work.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            StatsHero(
                totalUnits = totalUnits,
                totalSessions = totalSessions,
                totalEarned = totalEarned,
                bestExercise = bestExercise
            )
        }

        item {
            TrendGraphCard(
                range = selectedRange,
                bars = chartBars,
                onRangeChange = { selectedRangeName = it.name }
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                StatTile(
                    label = "Available",
                    value = formatDuration(state.globalCreditsSeconds),
                    modifier = Modifier.weight(1f)
                )
                StatTile(
                    label = "Earned",
                    value = formatDuration(totalEarned),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Text(
                text = "Exercise breakdown",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        items(ExerciseType.entries) { exercise ->
            ExerciseStatRow(
                exercise = exercise,
                stats = stats[exercise] ?: ExerciseStats(),
                maxUnits = if (totalUnits > 0L) totalUnits else 1L
            )
        }
    }
}

@Composable
private fun TrendGraphCard(
    range: WorkoutGraphRange,
    bars: List<WorkoutGraphBar>,
    onRangeChange: (WorkoutGraphRange) -> Unit
) {
    val maxUnitsVal = bars.maxByOrNull { it.units }?.units ?: 1L
    val totalUnitsCount = bars.fold(0L) { acc, bar -> acc + bar.units }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.TrendingUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Workout graph",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Movement units over the last ${range.label.lowercase()}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = totalUnitsCount.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkoutGraphRange.entries.forEach { entry ->
                    RangeChip(
                        label = entry.label,
                        selected = entry == range,
                        onClick = { onRangeChange(entry) }
                    )
                }
            }

            GraphBarsRow(bars = bars, maxUnits = maxUnitsVal)
        }
    }
}

@Composable
private fun GraphBarsRow(
    bars: List<WorkoutGraphBar>,
    maxUnits: Long
) {
    val density = LocalDensity.current
    val barMaxHeight = 122.dp
    val scrollState = rememberScrollState()
    LaunchedEffect(bars) {
        scrollState.scrollTo(scrollState.maxValue)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        bars.forEach { bar ->
            val progress = (bar.units.toFloat() / maxUnits.toFloat()).coerceIn(0f, 1f)
            val barHeight = with(density) { (barMaxHeight.toPx() * progress).toDp() }
            Column(
                modifier = Modifier.width(36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                Text(
                    text = bar.units.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .height(barMaxHeight)
                        .width(24.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .width(18.dp)
                            .height(barHeight.coerceAtLeast(4.dp))
                            .clip(RoundedCornerShape(999.dp))
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = bar.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun RangeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun StatsHero(
    totalUnits: Long,
    totalSessions: Long,
    totalEarned: Long,
    bestExercise: ExerciseType?
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primaryContainer
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.BarChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = "Total movement",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Text(
                    text = totalUnits.toString(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Black,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Text(
                    text = if (bestExercise != null) {
                        "$totalSessions workouts - ${formatDuration(totalEarned)} earned - strongest: ${bestExercise.displayName}"
                    } else {
                        "Start a workout to build your stats."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.78f)
                )
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ExerciseStatRow(
    exercise: ExerciseType,
    stats: ExerciseStats,
    maxUnits: Long
) {
    val displayUnits = if (exercise == ExerciseType.PLANK) stats.sessions else stats.units
    val progress = (displayUnits.toFloat() / maxUnits.toFloat()).coerceIn(0f, 1f)
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (exercise == ExerciseType.PLANK) Icons.Rounded.Timer else Icons.Rounded.FitnessCenter,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = exercise.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${stats.sessions} workouts - ${formatUnits(exercise, stats.units)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Icon(
                        imageVector = Icons.Rounded.Bolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = formatDuration(stats.creditsEarnedSeconds),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(50)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val total = seconds.coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${secs}s"
        else -> "${secs}s"
    }
}

private fun formatUnits(exercise: ExerciseType, units: Long): String {
    return if (exercise == ExerciseType.PLANK) {
        "${units}s held"
    } else {
        "$units reps"
    }
}
