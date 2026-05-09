package com.example.visionfit.ui

import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.visionfit.model.AppBlockMode
import com.example.visionfit.model.AppInfo
import com.example.visionfit.model.SettingsState
import com.example.visionfit.util.SuggestedSocialApps

@Composable
fun BlockedAppsScreen(
    state: SettingsState,
    apps: List<AppInfo>,
    onToggleApp: (String, Boolean) -> Unit,
    onAppModeChange: (String, AppBlockMode) -> Unit,
    isAccessibilityServiceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var reelsOnlyNeedsAccessibilityFor by remember { mutableStateOf<String?>(null) }
    val filteredApps = remember(apps, searchQuery, state.appRules) {
        val comparator =
            compareByDescending<AppInfo> { state.appRules.containsKey(it.packageName) }
                .thenBy { SuggestedSocialApps.rank(it.packageName) }
                .thenBy { it.label.lowercase() }
        if (searchQuery.isBlank()) {
            apps.sortedWith(comparator)
        } else {
            apps.filter {
                it.label.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
            }.sortedWith(comparator)
        }
    }
    val blockedCount = state.appRules.size

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        item {
            Column {
                Text(
                    text = "Blocked apps",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Lock the timewasters",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (blockedCount == 0) "No apps blocked yet" else "$blockedCount app${if (blockedCount == 1) "" else "s"} blocked",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search apps") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )
        }
        items(filteredApps, key = { it.packageName }) { app ->
            val isBlocked = state.appRules.containsKey(app.packageName)
            val currentMode = state.appRules[app.packageName] ?: AppBlockMode.ALL
            val supportsReelsOnly = app.packageName == "com.instagram.android"
            BlockedAppRow(
                app = app,
                isBlocked = isBlocked,
                currentMode = currentMode,
                supportsReelsOnly = supportsReelsOnly,
                onToggle = { onToggleApp(app.packageName, it) },
                onModeChange = { mode ->
                    if (
                        mode == AppBlockMode.REELS_ONLY &&
                        supportsReelsOnly &&
                        !isAccessibilityServiceEnabled
                    ) {
                        reelsOnlyNeedsAccessibilityFor = app.packageName
                    } else {
                        onAppModeChange(app.packageName, mode)
                    }
                }
            )
        }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        }

        reelsOnlyNeedsAccessibilityFor?.let { packageName ->
            val label = apps.firstOrNull { it.packageName == packageName }?.label ?: packageName
            AlertDialog(
                onDismissRequest = { reelsOnlyNeedsAccessibilityFor = null },
                title = { Text("Accessibility required") },
                text = {
                    Text(
                        "Reels-only uses Accessibility to detect Reels inside $label. " +
                            "Without it, this mode does not work. Open Accessibility settings to enable VisionFit?"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            onAppModeChange(packageName, AppBlockMode.REELS_ONLY)
                            onOpenAccessibilitySettings()
                            reelsOnlyNeedsAccessibilityFor = null
                        }
                    ) {
                        Text("Open settings")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { reelsOnlyNeedsAccessibilityFor = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
private fun BlockedAppRow(
    app: AppInfo,
    isBlocked: Boolean,
    currentMode: AppBlockMode,
    supportsReelsOnly: Boolean,
    onToggle: (Boolean) -> Unit,
    onModeChange: (AppBlockMode) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.icon)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = app.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = app.packageName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = isBlocked,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            }
            AnimatedVisibility(visible = isBlocked && supportsReelsOnly) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    AppModeSegmented(
                        selectedMode = currentMode,
                        onModeSelected = onModeChange
                    )
                }
            }
        }
    }
}

@Composable
private fun AppIcon(icon: Drawable?) {
    val painter = remember(icon) { icon?.toPainter() }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (painter != null) {
            Image(
                painter = painter,
                contentDescription = null,
                modifier = Modifier.size(28.dp)
            )
        } else {
            Icon(
                imageVector = Icons.Rounded.Block,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun Drawable.toPainter(): Painter {
    val width = if (intrinsicWidth > 0) intrinsicWidth else 48
    val height = if (intrinsicHeight > 0) intrinsicHeight else 48
    val bitmap = toBitmap(width = width, height = height)
    return BitmapPainter(bitmap.asImageBitmap())
}

@Composable
private fun AppModeSegmented(
    selectedMode: AppBlockMode,
    onModeSelected: (AppBlockMode) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(4.dp)) {
            SegmentChip(
                label = "Block all",
                selected = selectedMode == AppBlockMode.ALL,
                onClick = { onModeSelected(AppBlockMode.ALL) },
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(4.dp))
            SegmentChip(
                label = "Reels only",
                selected = selectedMode == AppBlockMode.REELS_ONLY,
                onClick = { onModeSelected(AppBlockMode.REELS_ONLY) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SegmentChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = bg,
        shape = RoundedCornerShape(10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = fg
            )
        }
    }
}
