package com.example.visionfit

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.visionfit.accessibility.AppBlockAccessibilityService
import com.example.visionfit.alarm.RepAlarmRingingService
import com.example.visionfit.alarm.RepAlarmScheduler
import com.example.visionfit.data.SettingsStore
import com.example.visionfit.model.AlarmScheduleMode
import com.example.visionfit.model.AppBlockMode
import com.example.visionfit.model.ExerciseType
import com.example.visionfit.model.RepAlarm
import com.example.visionfit.model.SettingsState
import com.example.visionfit.model.GitHubRelease
import com.example.visionfit.ui.AlarmsScreen
import com.example.visionfit.ui.BlockedAppsScreen
import com.example.visionfit.ui.HomeScreen
import com.example.visionfit.ui.SettingsScreen
import com.example.visionfit.ui.StatsScreen
import com.example.visionfit.ui.UpdateDialog
import com.example.visionfit.ui.WorkoutScreen
import com.example.visionfit.ui.theme.VisionFitTheme
import com.example.visionfit.util.isAccessibilityServiceEnabled
import com.example.visionfit.util.isUsageAccessGranted
import com.example.visionfit.util.loadLaunchableApps
import com.example.visionfit.util.UpdateChecker
import com.example.visionfit.usage.UsageTrackingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Tab(
    val title: String,
    val outlined: ImageVector,
    val filled: ImageVector
) {
    HOME("Home", Icons.Outlined.Home, Icons.Rounded.Home),
    STATS("Stats", Icons.Outlined.BarChart, Icons.Rounded.BarChart),
    APPS("Apps", Icons.Outlined.Apps, Icons.Rounded.Apps),
    ALARMS("Alarms", Icons.Outlined.Alarm, Icons.Rounded.Alarm),
    SETTINGS("Settings", Icons.Outlined.Settings, Icons.Rounded.Settings)
}

class MainActivity : ComponentActivity() {
    private var pendingLaunchAlarm by mutableStateOf<RepAlarm?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val launchAlarm = intent.toLaunchAlarm()
        if (launchAlarm != null) {
            showOverLockscreen()
        }
        pendingLaunchAlarm = launchAlarm
        enableEdgeToEdge()
        setContent {
            VisionFitTheme {
                val context = LocalContext.current
                val settingsStore = remember { SettingsStore(context.applicationContext) }
                val settingsState by settingsStore.settingsFlow
                    .collectAsState(initial = SettingsState.defaults())
                val scope = rememberCoroutineScope()
                val currentLaunchAlarm = pendingLaunchAlarm
                var activeExercise by remember { mutableStateOf<ExerciseType?>(currentLaunchAlarm?.exercise) }
                var currentTab by remember { mutableStateOf(Tab.HOME) }
                
                // Permissions State
                var isCameraPermissionGranted by remember { mutableStateOf(false) }
                var isNotificationPermissionGranted by remember { mutableStateOf(true) }
                var isUsageAccessGrantedState by remember { mutableStateOf(false) }
                var isAccessibilityEnabled by remember { mutableStateOf(false) }
                var isExactAlarmGranted by remember { mutableStateOf(true) }
                var isBatteryOptIgnored by remember { mutableStateOf(true) }
                
                var activeAlarm by remember { mutableStateOf<RepAlarm?>(currentLaunchAlarm) }

                // Update check state
                var pendingUpdateRelease by remember { mutableStateOf<GitHubRelease?>(null) }
                var hasCheckedForUpdate by remember { mutableStateOf(false) }
                var updateStatus by remember { mutableStateOf<String?>(null) }

                val lifecycleOwner = LocalLifecycleOwner.current

                LaunchedEffect(settingsStore) {
                    settingsStore.ensureDailyGrantApplied()
                }

                // Check for updates once shortly after launch, but not during workout
                val isInWorkout = activeExercise != null
                LaunchedEffect(Unit) {
                    if (!hasCheckedForUpdate) {
                        delay(5000) // wait 5s after app start
                        if (!isInWorkout) {
                            val result = UpdateChecker.checkForUpdate(context)
                            if (result.release != null) {
                                pendingUpdateRelease = result.release
                            }
                            hasCheckedForUpdate = true
                        }
                    }
                }

                val apps by produceState(initialValue = emptyList()) {
                    value = withContext(Dispatchers.IO) { loadLaunchableApps(context) }
                }

                // Permission Launchers/Actions
                val openUsageAccessSettings = remember {
                    {
                        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
                val openAccessibilitySettings = remember {
                    {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }
                val openExactAlarmSettings = remember {
                    {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData(Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        }
                    }
                }
                val openBatteryOptimizationSettings = remember {
                    {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    }
                }

                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    isCameraPermissionGranted = granted
                }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    isNotificationPermissionGranted = granted
                }

                androidx.compose.runtime.LaunchedEffect(currentLaunchAlarm?.id) {
                    if (currentLaunchAlarm != null) {
                        activeAlarm = currentLaunchAlarm
                        activeExercise = currentLaunchAlarm.exercise
                        pendingLaunchAlarm = null
                    }
                }

                DisposableEffect(lifecycleOwner, context) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

                            isCameraPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                            isNotificationPermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                            } else {
                                true
                            }
                            isUsageAccessGrantedState = isUsageAccessGranted(context)
                            isAccessibilityEnabled = isAccessibilityServiceEnabled(context, AppBlockAccessibilityService::class.java)
                            
                            isExactAlarmGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                alarmManager.canScheduleExactAlarms()
                            } else {
                                true
                            }
                            isBatteryOptIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                powerManager.isIgnoringBatteryOptimizations(context.packageName)
                            } else {
                                true
                            }

                            val intent = Intent(context, UsageTrackingService::class.java)
                            ContextCompat.startForegroundService(context, intent)
                            
                            scope.launch {
                                settingsStore.ensureDailyGrantApplied()
                                settingsStore.snapshotRepAlarms().forEach { alarm ->
                                    if (alarm.enabled) {
                                        RepAlarmScheduler.schedule(context, alarm)
                                    } else {
                                        RepAlarmScheduler.cancel(context, alarm.id)
                                    }
                                }
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    observer.onStateChanged(lifecycleOwner, Lifecycle.Event.ON_RESUME)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val workoutExercise = activeExercise
                androidx.compose.runtime.LaunchedEffect(
                    settingsState.activeAlarmId,
                    settingsState.repAlarms
                ) {
                    val activeId = settingsState.activeAlarmId
                    val alarm = settingsState.repAlarms.firstOrNull { it.id == activeId }
                    activeAlarm = alarm
                    if (alarm != null) {
                        activeExercise = alarm.exercise
                    }
                }
                // Avoid stopping the ringing service during alarm->workout handoff:
                // activeAlarm can be temporarily null while DataStore state catches up.
                androidx.compose.runtime.LaunchedEffect(activeAlarm?.id, workoutExercise) {
                    if (activeAlarm == null && workoutExercise == null) {
                        RepAlarmRingingService.stop(context)
                    }
                }
                if (workoutExercise != null) {
                    val alarmTarget = activeAlarm?.takeIf { it.exercise == workoutExercise }?.targetReps
                    WorkoutScreen(
                        exercise = workoutExercise,
                        state = settingsState,
                        settingsStore = settingsStore,
                        onStopWorkout = { 
                            if (alarmTarget == null) {
                                activeExercise = null
                            } else {
                                clearOverLockscreen()
                                activeExercise = null
                            }
                        },
                        onSnoozeAlarm = if (alarmTarget != null && activeAlarm?.snoozeEnabled == true) {
                            {
                                activeAlarm?.let { alarm ->
                                    scope.launch { settingsStore.setActiveAlarmId(null) }
                                    RepAlarmScheduler.snooze(context, alarm.id, minutes = 5)
                                    activeAlarm = null
                                    activeExercise = null
                                    currentTab = Tab.ALARMS
                                    clearOverLockscreen()
                                }
                            }
                        } else {
                            null
                        },
                        stopLocked = alarmTarget != null,
                        targetReps = alarmTarget,
                        onProgressUpdate = { progress ->
                            if (alarmTarget != null && progress >= alarmTarget) {
                                val completedAlarm = activeAlarm
                                scope.launch {
                                    settingsStore.setActiveAlarmId(null)
                                    if (completedAlarm?.scheduleMode == AlarmScheduleMode.ONCE) {
                                        settingsStore.setRepAlarmEnabled(completedAlarm.id, false)
                                    }
                                }
                                RepAlarmRingingService.stop(context)
                                activeAlarm = null
                                activeExercise = null
                                currentTab = Tab.HOME
                                clearOverLockscreen()
                            }
                        }
                    )
                    return@VisionFitTheme
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        NavigationBar {
                            Tab.entries.forEach { tab ->
                                val selected = currentTab == tab
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { currentTab = tab },
                                    icon = {
                                        Icon(
                                            imageVector = if (selected) tab.filled else tab.outlined,
                                            contentDescription = tab.title
                                        )
                                    },
                                    label = { Text(tab.title) },
                                    colors = NavigationBarItemDefaults.colors()
                                )
                            }
                        }
                    }
                ) { innerPadding ->
                    when (currentTab) {
                        Tab.HOME -> HomeScreen(
                            state = settingsState,
                            onStartWorkout = { exercise -> activeExercise = exercise },
                            modifier = Modifier.padding(innerPadding)
                        )
                        Tab.STATS -> StatsScreen(
                            state = settingsState,
                            modifier = Modifier.padding(innerPadding)
                        )
                        Tab.APPS -> BlockedAppsScreen(
                            state = settingsState,
                            apps = apps,
                            onToggleApp = { packageName, enabled ->
                                scope.launch {
                                    val mode = settingsState.appRules[packageName] ?: AppBlockMode.ALL
                                    settingsStore.updateAppRule(packageName, enabled, mode)
                                }
                            },
                            onAppModeChange = { packageName, mode ->
                                scope.launch { settingsStore.updateAppMode(packageName, mode) }
                            },
                            modifier = Modifier.padding(innerPadding)
                        )
                        Tab.SETTINGS -> SettingsScreen(
                            state = settingsState,
                            onDailyGrantSecondsChange = { seconds ->
                                scope.launch {
                                    settingsStore.updateDailyGrantSeconds(seconds)
                                    settingsStore.ensureDailyGrantApplied()
                                }
                            },
                            onExerciseSecondsChange = { exercise, seconds ->
                                scope.launch { settingsStore.updateExerciseSeconds(exercise, seconds) }
                            },
                            onOpenAccessibilitySettings = openAccessibilitySettings,
                            onResetCredits = {
                                scope.launch { settingsStore.updateGlobalCreditsSeconds(0L) }
                            },
                            isAccessibilityServiceEnabled = isAccessibilityEnabled,
                            isCameraPermissionGranted = isCameraPermissionGranted,
                            isNotificationPermissionGranted = isNotificationPermissionGranted,
                            isUsageAccessGranted = isUsageAccessGrantedState,
                            isExactAlarmGranted = isExactAlarmGranted,
                            isBatteryOptIgnored = isBatteryOptIgnored,
                            onRequestCameraPermission = {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            },
                            onRequestNotificationPermission = {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            },
                            onOpenUsageAccessSettings = openUsageAccessSettings,
                            onOpenExactAlarmSettings = openExactAlarmSettings,
                            onOpenBatteryOptimizationSettings = openBatteryOptimizationSettings,
                            onCheckForUpdate = {
                                scope.launch {
                                    updateStatus = "Checking..."
                                    val result = UpdateChecker.checkForUpdate(context)
                                    if (result.release != null) {
                                        pendingUpdateRelease = result.release
                                        updateStatus = "Update available!"
                                    } else if (result.error != null) {
                                        updateStatus = "Could not check: ${result.error}"
                                    } else {
                                        val currentVersion = context.packageManager
                                            .getPackageInfo(context.packageName, 0).versionName ?: "Unknown"
                                        updateStatus = "Up to date (v$currentVersion)"
                                    }
                                }
                            },
                            updateStatus = updateStatus,
                            modifier = Modifier.padding(innerPadding)
                        )
                        Tab.ALARMS -> AlarmsScreen(
                            alarms = settingsState.repAlarms,
                            onAddAlarm = { alarm ->
                                scope.launch {
                                    settingsStore.upsertRepAlarm(alarm)
                                    if (alarm.enabled) RepAlarmScheduler.schedule(context, alarm)
                                }
                            },
                            onDeleteAlarm = { alarmId ->
                                scope.launch {
                                    settingsStore.deleteRepAlarm(alarmId)
                                    RepAlarmScheduler.cancel(context, alarmId)
                                }
                            },
                            onToggleAlarm = { alarmId, enabled ->
                                scope.launch {
                                    settingsStore.setRepAlarmEnabled(alarmId, enabled)
                                    val updated = settingsStore.getAlarmById(alarmId)
                                    if (enabled && updated != null) {
                                        RepAlarmScheduler.schedule(context, updated)
                                    } else {
                                        RepAlarmScheduler.cancel(context, alarmId)
                                    }
                                }
                            },
                            modifier = Modifier.padding(innerPadding)
                    )
                    }
                }

                // Show update dialog if an update was found
                pendingUpdateRelease?.let { release ->
                    UpdateDialog(
                        release = release,
                        onDismiss = {
                            pendingUpdateRelease = null
                            updateStatus = null
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val alarm = intent.toLaunchAlarm()
        if (alarm != null) {
            showOverLockscreen()
            pendingLaunchAlarm = alarm
        }
    }

    private fun showOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
    }

    private fun clearOverLockscreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
        } else {
            @Suppress("DEPRECATION")
            window.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
    }

    companion object {
        const val EXTRA_LAUNCH_ALARM_ID = "extra_launch_alarm_id"
        const val EXTRA_LAUNCH_EXERCISE = "extra_launch_exercise"
        const val EXTRA_LAUNCH_TARGET_REPS = "extra_launch_target_reps"
        const val EXTRA_LAUNCH_SCHEDULE_MODE = "extra_launch_schedule_mode"
    }
}

private fun Intent.toLaunchAlarm(): RepAlarm? {
    val alarmId = getStringExtra(MainActivity.EXTRA_LAUNCH_ALARM_ID) ?: return null
    val exerciseName = getStringExtra(MainActivity.EXTRA_LAUNCH_EXERCISE) ?: return null
    val exercise = runCatching { ExerciseType.valueOf(exerciseName) }.getOrNull() ?: return null
    val targetReps = getIntExtra(MainActivity.EXTRA_LAUNCH_TARGET_REPS, -1).takeIf { it > 0 } ?: return null
    val scheduleMode = getStringExtra(MainActivity.EXTRA_LAUNCH_SCHEDULE_MODE)
        ?.let { runCatching { AlarmScheduleMode.valueOf(it) }.getOrNull() }
        ?: AlarmScheduleMode.DAILY
    return RepAlarm(
        id = alarmId,
        hour24 = 0,
        minute = 0,
        exercise = exercise,
        targetReps = targetReps,
        enabled = true,
        scheduleMode = scheduleMode
    )
}
