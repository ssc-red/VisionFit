package com.example.visionfit.alarm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.visionfit.MainActivity
import com.example.visionfit.data.SettingsStore
import com.example.visionfit.model.AlarmScheduleMode
import com.example.visionfit.ui.theme.VisionFitTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmRingingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterImmersiveFullScreen()
        val alarmId = intent.getStringExtra(RepAlarmScheduler.EXTRA_ALARM_ID).orEmpty()
        setContent {
            VisionFitTheme {
                var snoozeEnabled by remember { mutableStateOf(true) }
                LaunchedEffect(alarmId) {
                    snoozeEnabled = withContext(Dispatchers.IO) {
                        SettingsStore(applicationContext).getAlarmById(alarmId)?.snoozeEnabled ?: true
                    }
                }
                AlarmRingingScreen(
                    snoozeEnabled = snoozeEnabled,
                    onSnooze = {
                        if (!snoozeEnabled) return@AlarmRingingScreen
                        RepAlarmRingingService.stop(this)
                        RepAlarmScheduler.snooze(this, alarmId, 5)
                        finish()
                    },
                    onStopAndStartWorkout = {
                        lifecycleScope.launch {
                            val latestAlarm = withContext(Dispatchers.IO) {
                                val store = SettingsStore(applicationContext)
                                store.setActiveAlarmId(alarmId)
                                store.getAlarmById(alarmId)
                            }
                            startActivity(
                                Intent(this@AlarmRingingActivity, MainActivity::class.java).apply {
                                    addFlags(
                                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                                    )
                                    putExtra(MainActivity.EXTRA_LAUNCH_ALARM_ID, alarmId)
                                    latestAlarm?.let {
                                        putExtra(MainActivity.EXTRA_LAUNCH_EXERCISE, it.exercise.name)
                                        putExtra(MainActivity.EXTRA_LAUNCH_TARGET_REPS, it.targetReps)
                                        putExtra(MainActivity.EXTRA_LAUNCH_SCHEDULE_MODE, it.scheduleMode.name)
                                    }
                                }
                            )
                            finish()
                        }
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        enterImmersiveFullScreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveFullScreen()
    }

    private fun enterImmersiveFullScreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        fun launchIntent(context: Context, alarmId: String): Intent {
            return Intent(context, AlarmRingingActivity::class.java).apply {
                putExtra(RepAlarmScheduler.EXTRA_ALARM_ID, alarmId)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
        }
    }
}

@Composable
private fun AlarmRingingScreen(
    snoozeEnabled: Boolean,
    onSnooze: () -> Unit,
    onStopAndStartWorkout: () -> Unit
) {
    var now by remember { mutableStateOf(Date()) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 180.dp.toPx() }
    val flashAlpha by rememberInfiniteTransition(label = "alarm-flash")
        .animateFloat(
            initialValue = 0.04f,
            targetValue = 0.16f,
            animationSpec = infiniteRepeatable(animation = tween(900), repeatMode = RepeatMode.Reverse),
            label = "flash-alpha"
        )
    val animatedOffsetY = dragOffsetY
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(1000L)
        }
    }
    val clockText = SimpleDateFormat("h:mm:ss", Locale.getDefault()).format(now)
    val amPmText = SimpleDateFormat("a", Locale.getDefault()).format(now)
    val dateText = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(snoozeEnabled) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dragAmount ->
                        dragOffsetY = (dragOffsetY + dragAmount).coerceAtLeast(0f)
                    },
                    onDragEnd = {
                        if (snoozeEnabled && dragOffsetY > dismissThresholdPx) {
                            onSnooze()
                        } else {
                            dragOffsetY = 0f
                        }
                    },
                    onDragCancel = {
                        dragOffsetY = 0f
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = flashAlpha))
        )

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, animatedOffsetY.toInt()) },
            color = Color.Transparent
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 22.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 24.dp)
                ) {
                    Text(
                        text = "REP ALARM",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF98E39A)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = dateText,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFFA0AAB8)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(40.dp),
                    color = Color(0xFF121417),
                    shadowElevation = 18.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = clockText,
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontSize = 68.sp,
                                lineHeight = 70.sp,
                                fontWeight = FontWeight.Black
                            ),
                            color = Color.White
                            ,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Clip,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = Color(0xFF98E39A).copy(alpha = 0.18f)
                        ) {
                            Text(
                                text = amPmText,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF98E39A),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                        Text(
                            text = "Wake up and earn it.",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (snoozeEnabled) {
                                "Swipe down to dismiss or tap a button below."
                            } else {
                                "Snooze is disabled for this alarm."
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color(0xFFA0AAB8)
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "Vibrating now",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF98E39A),
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (snoozeEnabled) {
                            Button(
                                onClick = onSnooze,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(60.dp),
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF2D3138),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("Snooze")
                            }
                        }
                        Button(
                            onClick = onStopAndStartWorkout,
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp),
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF98E39A),
                                contentColor = Color.Black
                            )
                        ) {
                            Text("Stop")
                        }
                    }
                }
            }
        }
    }
}
