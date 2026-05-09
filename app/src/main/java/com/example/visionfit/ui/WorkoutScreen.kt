package com.example.visionfit.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.graphics.PointF
import android.view.Surface
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Camera
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.visionfit.data.SettingsStore
import com.example.visionfit.model.ExerciseType
import com.example.visionfit.model.SettingsState
import com.example.visionfit.util.PoseRepCounter
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.accurate.AccuratePoseDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

@Composable
fun WorkoutScreen(
    exercise: ExerciseType,
    state: SettingsState,
    settingsStore: SettingsStore,
    onStopWorkout: () -> Unit,
    onSnoozeAlarm: (() -> Unit)? = null,
    stopLocked: Boolean = false,
    targetReps: Int? = null,
    onProgressUpdate: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val perRepSeconds = state.exerciseSeconds[exercise] ?: exercise.defaultSecondsPerRep
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var persistedPoseProfile by remember(exercise) { mutableStateOf<String?>(null) }
    val repCounter = remember(exercise, persistedPoseProfile) { PoseRepCounter(exercise, persistedPoseProfile) }
    val repCountRef = remember { AtomicInteger(0) }
    var repCount by remember { mutableIntStateOf(0) }
    var plankHoldSeconds by remember { mutableIntStateOf(0) }
    var plankHoldActive by remember { mutableStateOf(false) }
    var poseLandmarks by remember { mutableStateOf<Map<Int, PointF>>(emptyMap()) }
    var poseDetected by remember { mutableStateOf(false) }
    var formPrompt by remember { mutableStateOf<String?>(null) }
    var cameraSessionActive by remember { mutableStateOf(true) }
    var lastSavedPoseProfile by remember(exercise) { mutableStateOf<String?>(null) }

    val detector = remember {
        val options = AccuratePoseDetectorOptions.Builder()
            .setDetectorMode(AccuratePoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraProviderState = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var isFrontCamera by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            cameraSessionActive = false
            cameraProviderState.value?.unbindAll()
            analysisExecutor.shutdown()
            detector.close()
        }
    }

    LaunchedEffect(exercise) {
        persistedPoseProfile = settingsStore.getPoseProfile(exercise)
        cameraSessionActive = true
        repCount = 0
        plankHoldSeconds = 0
        plankHoldActive = false
        repCountRef.set(0)
        poseLandmarks = emptyMap()
        poseDetected = false
        formPrompt = null
        settingsStore.recordWorkoutStarted(exercise)
    }

    LaunchedEffect(plankHoldActive, exercise, perRepSeconds) {
        if (exercise != ExerciseType.PLANK || !plankHoldActive) return@LaunchedEffect
        while (plankHoldActive) {
            delay(1000L)
            settingsStore.addWorkoutCredits(
                exercise = exercise,
                units = 0L, // Already counted 1 unit session in recordWorkoutStarted
                creditsSeconds = perRepSeconds.toLong()
            )
            plankHoldSeconds += 1
            onProgressUpdate(plankHoldSeconds)
        }
    }

    LaunchedEffect(hasPermission, exercise) {
        if (!hasPermission) return@LaunchedEffect
        val cameraProvider = awaitCameraProvider(context)
        cameraProviderState.value = cameraProvider

        val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
            isFrontCamera = true
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            isFrontCamera = false
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
            .apply { setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(
            analysisExecutor,
            object : ImageAnalysis.Analyzer {
                @ExperimentalGetImage
                override fun analyze(imageProxy: ImageProxy) {
                    if (!cameraSessionActive) {
                        imageProxy.close()
                        return
                    }
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        imageProxy.close()
                        return
                    }
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val image = InputImage.fromMediaImage(mediaImage, rotation)

                    detector.process(image)
                        .addOnSuccessListener { pose ->
                            val update = repCounter.onPose(pose)
                            val landmarks = normalizeLandmarks(
                                pose.allPoseLandmarks,
                                imageProxy.width,
                                imageProxy.height,
                                rotation,
                                isFrontCamera
                            )
                            scope.launch(Dispatchers.Main) {
                                poseLandmarks = landmarks
                                poseDetected = update.poseConfident
                                plankHoldActive = update.holdActive
                                formPrompt = update.formPrompt
                                onProgressUpdate(if (exercise == ExerciseType.PLANK) plankHoldSeconds else update.reps)
                                val profile = update.profileSnapshot
                                if (!profile.isNullOrBlank() && profile != lastSavedPoseProfile) {
                                    lastSavedPoseProfile = profile
                                    scope.launch(Dispatchers.IO) {
                                        settingsStore.updatePoseProfile(exercise, profile)
                                    }
                                }
                            }

                            if (exercise != ExerciseType.PLANK) {
                                val newCount = update.reps
                                val current = repCountRef.get()
                                if (newCount != current) {
                                    repCountRef.set(newCount)
                                    val delta = newCount - current
                                    if (delta > 0) {
                                        scope.launch(Dispatchers.Main) {
                                            settingsStore.addWorkoutCredits(
                                                exercise = exercise,
                                                units = delta.toLong(),
                                                creditsSeconds = delta.toLong() * perRepSeconds.toLong()
                                            )
                                        }
                                    }
                                    scope.launch(Dispatchers.Main) {
                                        repCount = newCount
                                    }
                                }
                            }
                        }
                        .addOnFailureListener {
                            scope.launch(Dispatchers.Main) {
                                poseDetected = false
                                plankHoldActive = false
                                poseLandmarks = emptyMap()
                                formPrompt = "Keep your full body in frame for form feedback."
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
            }
        )

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                analysis
            )
        } catch (_: Exception) {
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize()
            )
            PoseOverlay(
                landmarks = poseLandmarks,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            CameraPermissionRequest(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.fillMaxSize()
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0xCC000000), Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, Color(0xEE000000))
                    )
                )
        )

        TopHud(
            exercise = exercise,
            credits = state.globalCreditsSeconds,
            poseDetected = poseDetected,
            onStop = onStopWorkout,
            onSnoozeAlarm = onSnoozeAlarm,
            stopLocked = stopLocked,
            targetReps = targetReps,
            progress = if (exercise == ExerciseType.PLANK) plankHoldSeconds else repCount,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Top))
                .padding(horizontal = 20.dp, vertical = 8.dp)
        )

        BottomHud(
            exercise = exercise,
            repCount = repCount,
            plankHoldSeconds = plankHoldSeconds,
            plankHoldActive = plankHoldActive,
            perRepSeconds = perRepSeconds,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.systemBars)
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .fillMaxWidth()
        )

        if (!formPrompt.isNullOrBlank()) {
            FormPromptHud(
                text = formPrompt!!,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(horizontal = 20.dp, vertical = 180.dp)
                    .fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TopHud(
    exercise: ExerciseType,
    credits: Long,
    poseDetected: Boolean,
    onStop: () -> Unit,
    onSnoozeAlarm: (() -> Unit)?,
    stopLocked: Boolean,
    targetReps: Int?,
    progress: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GlassPill {
            Icon(
                imageVector = if (exercise == ExerciseType.PLANK) Icons.Rounded.Timer else Icons.Rounded.FitnessCenter,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = exercise.displayName,
                style = MaterialTheme.typography.labelLarge,
                color = Color.White
            )
        }
        if (!stopLocked) {
            Spacer(modifier = Modifier.width(4.dp))
            GlassPill {
                Icon(
                    imageVector = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(2.dp))
                Text(
                    text = formatSeconds(credits),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            BodyStatusPill(detected = poseDetected)
        }
        if (stopLocked && targetReps != null) {
            Spacer(modifier = Modifier.width(8.dp))
            GlassPill {
                Text(
                    text = "${progress.coerceAtMost(targetReps)}/$targetReps",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip
                )
            }
            if (onSnoozeAlarm != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.tertiary,
                    onClick = onSnoozeAlarm
                ) {
                    Text(
                        text = "Snooze 5m",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onTertiary
                    )
                }
            }
        }
        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.width(10.dp))
        if (!stopLocked) {
            StopFab(onStop)
        }
    }
}

@Composable
private fun BodyStatusPill(detected: Boolean) {
    val tint = if (detected) MaterialTheme.colorScheme.primary else Color(0xFFFF8A8A)
    GlassPill {
        Icon(
            imageVector = Icons.Rounded.Sensors,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = if (detected) "Tracking" else "Scanning",
            style = MaterialTheme.typography.labelLarge,
            color = Color.White
        )
    }
}

@Composable
private fun GlassPill(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0x66000000)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
private fun StopFab(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.error,
        onClick = onClick
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = "Stop",
                tint = Color.White,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun BottomHud(
    exercise: ExerciseType,
    repCount: Int,
    plankHoldSeconds: Int,
    plankHoldActive: Boolean,
    perRepSeconds: Int,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = Color(0xCC0C0F14)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (exercise == ExerciseType.PLANK) "Hold time" else "Reps",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFFA0AAB8)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (exercise == ExerciseType.PLANK) {
                        formatHold(plankHoldSeconds)
                    } else {
                        repCount.toString()
                    },
                    style = MaterialTheme.typography.displayLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Black
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (exercise == ExerciseType.PLANK) "+${perRepSeconds}s/sec" else "+${perRepSeconds}s/rep",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
                if (exercise == ExerciseType.PLANK) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (plankHoldActive) "Holding" else "Get into plank",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (plankHoldActive) MaterialTheme.colorScheme.primary else Color(0xFFA0AAB8)
                    )
                }
            }
        }
    }
}

@Composable
private fun FormPromptHud(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = Color(0xE6331D1D)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = Color(0xFFFFB4AB),
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun CameraPermissionRequest(
    onGrant: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Camera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Camera access needed",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "We use the camera locally to count your reps. Footage never leaves your device.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFA0AAB8)
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onGrant,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Text(text = "Grant camera permission")
        }
    }
}

@Composable
private fun PoseOverlay(
    landmarks: Map<Int, PointF>,
    modifier: Modifier = Modifier
) {
    if (landmarks.isEmpty()) return
    val connections = remember {
        listOf(
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
            PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
            PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
            PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
            PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
            PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE
        )
    }

    Canvas(modifier = modifier) {
        val strokeWidth = 8f
        val lineColor = Color(0xFFC6FF3D)
        val jointColor = Color.White

        connections.forEach { (startId, endId) ->
            val start = landmarks[startId]
            val end = landmarks[endId]
            if (start != null && end != null) {
                drawLine(
                    color = lineColor,
                    start = Offset(start.x * size.width, start.y * size.height),
                    end = Offset(end.x * size.width, end.y * size.height),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
            }
        }

        landmarks.values.forEach { point ->
            drawCircle(
                color = jointColor,
                radius = 10f,
                center = Offset(point.x * size.width, point.y * size.height)
            )
            drawCircle(
                color = lineColor,
                radius = 6f,
                center = Offset(point.x * size.width, point.y * size.height)
            )
        }
    }
}

private suspend fun awaitCameraProvider(context: Context): ProcessCameraProvider {
    return suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { cont.resume(future.get()) },
            ContextCompat.getMainExecutor(context)
        )
    }
}

private fun formatSeconds(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return when {
        hours > 0 -> "%d:%02d:%02d".format(hours, minutes, seconds)
        else -> "%d:%02d".format(minutes, seconds)
    }
}

private fun formatHold(seconds: Int): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%d:%02d".format(mins, secs)
}

private fun normalizeLandmarks(
    landmarks: List<PoseLandmark>,
    imageWidth: Int,
    imageHeight: Int,
    rotation: Int,
    isFrontCamera: Boolean
): Map<Int, PointF> {
    if (imageWidth == 0 || imageHeight == 0) return emptyMap()
    val (normWidth, normHeight) = if (rotation == 90 || rotation == 270) {
        imageHeight.toFloat() to imageWidth.toFloat()
    } else {
        imageWidth.toFloat() to imageHeight.toFloat()
    }

    return landmarks.associate { landmark ->
        val p = landmark.position
        var x = p.x / normWidth
        val y = p.y / normHeight
        if (isFrontCamera) x = 1.0f - x
        landmark.landmarkType to PointF(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
    }
}
