package com.example.visionfit.util

import com.example.visionfit.model.ExerciseType
import com.google.mlkit.vision.common.PointF3D
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.atan2

class PoseRepCounter(
    private val exerciseType: ExerciseType,
    persistedProfile: String? = null
) {
    private var stage: Stage? = null
    private var reps: Int = 0
    private val minConfidence = 0.4f
    private var smoothedAngle: Double? = null
    private val smoothingFactor = 0.25
    private var framesInState = 0
    private val requiredFrames = 3
    private val recentRaw = ArrayDeque<Double>(3)

    private var lastRepTime = Long.MIN_VALUE / 2
    private val repCooldownMs = 350L
    private var stageEnteredAtMs = 0L
    private var stageEntryAngle: Double? = null
    private var stageExtremeAngle: Double? = null
    private var stageExtremeRaw: Double? = null
    private val hysteresisMargin = 8.0
    private val crunchHysteresisMargin = 4.0
    private val plankAngleMin = 160.0
    private val plankAngleExit = 150.0
    private val plankHoldMinMs = 3000L
    private var plankHoldStartMs: Long? = null
    private var plankBelowSinceMs: Long? = null
    private val plankBelowGraceMs = 600L
    private var lastAngleMs: Long? = null
    private var lastAngleForVelocity: Double? = null
    private var latestAngularVelocity = 0.0
    private var latestPoseWasSynthetic = false
    private var profileDirty = false

    private val calibrationAngles = ArrayDeque<Double>(90)
    private var calibratedThresholds: Thresholds? = null
    private var profile = CalibrationProfile.fromEncoded(persistedProfile)
    private val oneEuroMinCutoff = 1.0
    private val oneEuroBeta = 0.035
    private val oneEuroDerivativeCutoff = 1.0
    private var derivativeEma: Double? = null

    private var persistedFormPrompt: String? = null
    private var pendingPrompt: String? = null
    private var pendingPromptFrames = 0

    private fun resetFormPromptState() {
        persistedFormPrompt = null
        pendingPrompt = null
        pendingPromptFrames = 0
    }

    fun onPose(pose: Pose): PoseUpdate {
        if (exerciseType == ExerciseType.PLANK) {
            return onPlankPose(pose)
        }

        val rawAngle = when (exerciseType) {
            ExerciseType.PUSHUPS -> averageAngle(
                angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST),
                angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
            )
            ExerciseType.SQUATS -> squatAngle(pose)
            ExerciseType.PULL_UPS -> averageAngle(
                angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST),
                angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
            )
            ExerciseType.CRUNCHES -> averageAngle(
                angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE),
                angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE)
            )
            ExerciseType.PLANK -> null
        }

        return onAngleSample(rawAngle, poseConfident = rawAngle != null, pose = pose)
    }

    fun onAngleSample(
        rawAngle: Double?,
        poseConfident: Boolean,
        pose: Pose? = null,
        nowMs: Long = System.currentTimeMillis()
    ): PoseUpdate {
        if (!poseConfident || rawAngle == null) {
            latestPoseWasSynthetic = pose == null
            smoothedAngle = null
            recentRaw.clear()
            resetFormPromptState()
            return PoseUpdate(
                reps = reps,
                poseConfident = false,
                holdActive = false,
                formPrompt = "Step back—keep head, torso, and legs in the camera preview.",
                profileSnapshot = encodedProfile()
            )
        }

        if (recentRaw.size == 3) recentRaw.removeFirst()
        recentRaw.addLast(rawAngle)
        val despiked = medianOf(recentRaw)

        updateVelocity(despiked, nowMs)
        val angle = adaptiveSmoothedAngle(despiked, nowMs)
        val thresholds = effectiveThresholds()
        smoothedAngle = angle
        latestPoseWasSynthetic = pose == null
        maybeCollectCalibrationSample(despiked)

        if (stage == null) {
            maybeEnterInitialStage(angle, despiked, thresholds, nowMs)
            return PoseUpdate(
                reps = reps,
                poseConfident = true,
                holdActive = false,
                formPrompt = resolveFormPrompt(
                    formPromptSmart(pose, exerciseType, angle, stage = null)
                ),
                profileSnapshot = encodedProfile()
            )
        }

        framesInState += 1
        when (stage!!) {
            Stage.DOWN -> {
                updateStageExtreme(angle, despiked, thresholds)
                if (isRepComplete(angle, thresholds, nowMs)) {
                    reps++
                    lastRepTime = nowMs
                    enterStage(Stage.UP, angle, despiked, nowMs)
                }
            }

            Stage.UP -> {
                updateStageExtreme(angle, despiked, thresholds)
                if (thresholds.isDownFirst) {
                    if (angle <= thresholds.down - thresholds.hysteresis && framesInState >= requiredFrames) {
                        enterStage(Stage.DOWN, angle, despiked, nowMs)
                    }
                } else {
                    if (angle >= thresholds.down + thresholds.hysteresis && framesInState >= requiredFrames) {
                        enterStage(Stage.DOWN, angle, despiked, nowMs)
                    }
                }
            }
        }

        return PoseUpdate(
            reps = reps,
            poseConfident = true,
            holdActive = false,
            formPrompt = resolveFormPrompt(
                formPromptSmart(pose, exerciseType, angle, stage = stage)
            ),
            profileSnapshot = encodedProfile()
        )
    }

    private fun onPlankPose(pose: Pose): PoseUpdate {
        val confident = plankPoseConfident(pose)
        val now = System.currentTimeMillis()
        if (!confident) {
            plankHoldStartMs = null
            plankBelowSinceMs = null
            resetFormPromptState()
            return PoseUpdate(
                reps = reps,
                poseConfident = false,
                holdActive = false,
                formPrompt = "Turn sideways—show shoulder, hip, and feet in one view.",
                profileSnapshot = encodedProfile()
            )
        }

        val rawAngle = plankAngle(pose)
        if (rawAngle != null) {
            val angle = if (smoothedAngle == null) rawAngle else smoothedAngle!! + smoothingFactor * (rawAngle - smoothedAngle!!)
            smoothedAngle = angle

            val isAbove = angle >= plankAngleMin
            val isBelow = angle < plankAngleExit

            if (isAbove) {
                plankBelowSinceMs = null
                if (plankHoldStartMs == null) plankHoldStartMs = now
            } else if (isBelow) {
                val belowStart = plankBelowSinceMs ?: now.also { plankBelowSinceMs = it }
                if (now - belowStart >= plankBelowGraceMs) {
                    plankHoldStartMs = null
                    return PoseUpdate(
                        reps = reps,
                        poseConfident = true,
                        holdActive = false,
                        formPrompt = resolveFormPrompt(
                            formPromptSmart(pose, ExerciseType.PLANK, angle, stage = null)
                        ),
                        profileSnapshot = encodedProfile()
                    )
                }
            } else {
                plankBelowSinceMs = null
            }
        }

        val start = plankHoldStartMs ?: return PoseUpdate(
            reps = reps,
            poseConfident = true,
            holdActive = false,
            formPrompt = resolveFormPrompt(
                formPromptSmart(pose, ExerciseType.PLANK, smoothedAngle, stage = null)
            ),
            profileSnapshot = encodedProfile()
        )
        val holdActive = now - start >= plankHoldMinMs
        return PoseUpdate(
            reps = reps,
            poseConfident = true,
            holdActive = holdActive,
            formPrompt = resolveFormPrompt(
                formPromptSmart(pose, ExerciseType.PLANK, smoothedAngle, stage = null)
            ),
            profileSnapshot = encodedProfile()
        )
    }

    private fun resolveFormPrompt(instant: String?): String? {
        if (persistedFormPrompt == null && instant != null) {
            persistedFormPrompt = instant
            pendingPrompt = instant
            pendingPromptFrames = FORM_PROMPT_STABLE_FRAMES
            return persistedFormPrompt
        }
        when {
            instant == persistedFormPrompt -> {
                pendingPrompt = instant
                pendingPromptFrames = 0
                return persistedFormPrompt
            }
            instant != pendingPrompt -> {
                pendingPrompt = instant
                pendingPromptFrames = 1
            }
            else -> pendingPromptFrames++
        }
        if (pendingPromptFrames >= FORM_PROMPT_STABLE_FRAMES) {
            persistedFormPrompt = pendingPrompt
            pendingPromptFrames = FORM_PROMPT_STABLE_FRAMES
        }
        return persistedFormPrompt
    }

    private fun formPromptSmart(
        pose: Pose?,
        exercise: ExerciseType,
        angle: Double?,
        stage: Stage?
    ): String? {
        when (exercise) {
            ExerciseType.PLANK -> {
                plankTorsoAsymmetry(pose)?.let { return it }
                return plankLineCue(angle)
            }
            else -> {
                val resolvedAngle = angle ?: return null
                frontViewCoverageCue(pose, exercise)?.let { return it }
                postureAsymmetry(pose, exercise)?.let { return it }
                val nuanced = stageRefinedCue(exercise, resolvedAngle, stage)
                if (nuanced != null) return nuanced
                return baselineAngleCue(exercise, resolvedAngle)
            }
        }
    }

    private fun frontViewCoverageCue(pose: Pose?, exercise: ExerciseType): String? {
        if (pose == null) return null
        val ids = when (exercise) {
            ExerciseType.PUSHUPS, ExerciseType.PULL_UPS ->
                listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST)
            ExerciseType.SQUATS ->
                listOf(PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE)
            ExerciseType.CRUNCHES ->
                listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE)
            ExerciseType.PLANK -> emptyList()
        }
        if (ids.isEmpty()) return null
        val coverage = landmarkCoverage(pose, ids)
        return if (coverage < 0.48f) {
            "Phone is too front-facing for full tracking—step back and keep both sides visible."
        } else null
    }

    private fun postureAsymmetry(pose: Pose?, exercise: ExerciseType): String? {
        if (pose == null) return null
        return when (exercise) {
            ExerciseType.PUSHUPS, ExerciseType.PULL_UPS -> elbowAsymmetryCue(pose)
            ExerciseType.SQUATS -> kneeAsymmetryCue(pose)
            ExerciseType.CRUNCHES -> crunchTorsoAsymmetryCue(pose)
            ExerciseType.PLANK -> null
        }
    }

    private fun elbowAsymmetryCue(pose: Pose): String? {
        val left = angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST)
        val right = angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
        if (left == null || right == null) return null
        val spread = abs(left - right)
        if (spread < ELBOW_ASYMMETRY_DEG) return null
        return when {
            left <= right - 4.0 -> "Left elbow bends more—even out chest height sides-to-side."
            right <= left - 4.0 -> "Right elbow bends more—even out chest height sides-to-side."
            else -> "Elbows drifting apart—steady your shoulder-width press."
        }
    }

    private fun kneeAsymmetryCue(pose: Pose): String? {
        val lk = angleForSide(pose, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE)
        val rk = angleForSide(pose, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
        if (lk == null || rk == null) return null
        if (abs(lk - rk) < LEG_ASYMMETRY_DEG) return null
        return when {
            lk <= rk - 5.0 -> "Left knee is deeper—spread weight evenly foot-to-foot."
            rk <= lk - 5.0 -> "Right knee is deeper—spread weight evenly foot-to-foot."
            else -> "Hips drifting—squat symmetrically facing the camera."
        }
    }

    private fun crunchTorsoAsymmetryCue(pose: Pose): String? {
        val lt = angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE)
        val rt = angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE)
        if (lt == null || rt == null) return null
        if (abs(lt - rt) < CRUNCH_ASYMMETRY_DEG) return null
        return when {
            lt <= rt - 5.0 -> "Left shoulder leads—curl both sides evenly toward midline."
            rt <= lt - 5.0 -> "Right shoulder leads—curl both sides evenly toward midline."
            else -> "Twisting slightly—glue ribs down symmetrically."
        }
    }

    private fun plankTorsoAsymmetry(pose: Pose?): String? {
        if (pose == null) return null
        val left = angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE)
        val right = angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE)
        if (left == null || right == null) return null
        return if (abs(left - right) >= PLANK_SIDE_ASYMMETRY_DEG) {
            "Rotate slightly—square hips so both sides mirror in frame."
        } else null
    }

    private fun stageRefinedCue(exercise: ExerciseType, angle: Double, stage: Stage?): String? {
        if (stage == null) return null
        return when (exercise) {
            ExerciseType.PUSHUPS -> when (stage) {
                Stage.DOWN -> when {
                    angle > 158.0 -> "Descending—bring chest closer with elbows tracking back."
                    angle < 70.0 -> "Bottom braced ribs down—don't let hips spike up."
                    else -> null
                }
                Stage.UP -> when {
                    angle in 90.0..110.0 -> "Driving up—glutes tight, wrists stacked under shoulders."
                    else -> null
                }
            }
            ExerciseType.SQUATS -> when (stage) {
                Stage.DOWN -> when {
                    angle > 152.0 -> "Still sinking—sit hips back evenly over both heels."
                    else -> null
                }
                Stage.UP -> when {
                    angle < 154.0 -> "Finishing the rep—squeeze glutes tall at the top."
                    else -> null
                }
            }
            ExerciseType.PULL_UPS -> when (stage) {
                Stage.DOWN -> when {
                    angle > 140.0 -> "Hang with shoulders engaged—ribs soft so lats initiate."
                    else -> null
                }
                Stage.UP -> when {
                    angle in 60.0..90.0 -> "Peak pull—bring elbows beside ribs without kicking legs."
                    else -> null
                }
            }
            ExerciseType.CRUNCHES -> when (stage) {
                Stage.DOWN -> when {
                    angle > 135.0 -> "Long torso on the floor—engage abs before curling."
                    else -> null
                }
                Stage.UP -> when {
                    angle < 115.0 && angle > 90.0 -> "Peak curl—exhale ribs down; chin floats off chest."
                    else -> null
                }
            }
            ExerciseType.PLANK -> null
        }
    }

    private fun baselineAngleCue(exercise: ExerciseType, angle: Double): String? = when (exercise) {
        ExerciseType.PUSHUPS -> when {
            angle > 165.0 -> "Sink until elbows reach about 90°—ribs and glutes braced."
            angle < 65.0 -> "Press palms away; stack wrists under shoulders, torso rigid."
            else -> null
        }
        ExerciseType.SQUATS -> when {
            angle > 160.0 -> "Sit deeper—hips back, knees over toes, chest tall."
            angle < 65.0 -> "Drive floor away—heels heavy, knees track over feet."
            else -> null
        }
        ExerciseType.PULL_UPS -> when {
            angle > 140.0 -> "Pull elbows beside ribs—sternum rises toward hands."
            angle < 60.0 -> "Lower with long arms—no swing, traps stay settled."
            else -> null
        }
        ExerciseType.CRUNCHES -> when {
            angle > 140.0 -> "Lift shoulder blades slightly—breath out as you shorten."
            angle < 80.0 -> "Ease torso down ribs-to-hips relaxed; chin soft."
            else -> null
        }
        ExerciseType.PLANK -> null
    }

    private fun plankLineCue(angle: Double?): String? {
        if (angle == null) return null
        return when {
            angle < 145.0 -> "Brace abs—lift hips to one line shoulders through heels."
            angle > 182.0 -> "Soft dip of hips—not a pike; glutes squeeze gently."
            else -> null
        }
    }

    private fun medianOf(values: ArrayDeque<Double>): Double {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun angleForSide(pose: Pose, first: Int, mid: Int, last: Int): Double? {
        val f = pose.getPoseLandmark(first) ?: return null
        val m = pose.getPoseLandmark(mid) ?: return null
        val l = pose.getPoseLandmark(last) ?: return null
        if (f.inFrameLikelihood < minConfidence || m.inFrameLikelihood < minConfidence || l.inFrameLikelihood < minConfidence) return null
        return angleDegrees2D(f.position3D, m.position3D, l.position3D)
    }

    private fun angleDegrees2D(a: PointF3D, b: PointF3D, c: PointF3D): Double {
        val radians = atan2((c.y - b.y).toDouble(), (c.x - b.x).toDouble()) - atan2((a.y - b.y).toDouble(), (a.x - b.x).toDouble())
        var angle = abs(radians * 180.0 / Math.PI)
        if (angle > 180.0) angle = 360.0 - angle
        return angle
    }

    private fun averageAngle(left: Double?, right: Double?) = when {
        left != null && right != null -> (left + right) / 2.0
        else -> left ?: right
    }

    private fun thresholdsFor(exercise: ExerciseType) = when (exercise) {
        ExerciseType.PUSHUPS -> Thresholds(down = 95.0, up = 145.0, isDownFirst = true, minRepSpan = 35.0, minStageDurationMs = 200L, hysteresis = hysteresisMargin)
        ExerciseType.SQUATS -> Thresholds(down = 105.0, up = 155.0, isDownFirst = true, minRepSpan = 35.0, minStageDurationMs = 200L, hysteresis = hysteresisMargin)
        ExerciseType.PULL_UPS -> Thresholds(down = 150.0, up = 90.0, isDownFirst = false, minRepSpan = 35.0, minStageDurationMs = 200L, hysteresis = hysteresisMargin)
        ExerciseType.CRUNCHES -> Thresholds(down = 140.0, up = 110.0, isDownFirst = false, minRepSpan = 20.0, minStageDurationMs = 150L, hysteresis = crunchHysteresisMargin)
        ExerciseType.PLANK -> Thresholds(down = plankAngleMin, up = plankAngleMin, isDownFirst = true, minRepSpan = 0.0, minStageDurationMs = 0L, hysteresis = 0.0)
    }

    private fun maybeEnterInitialStage(angle: Double, despiked: Double, thresholds: Thresholds, nowMs: Long) {
        if (thresholds.isDownFirst) {
            when {
                angle <= thresholds.down -> enterStage(Stage.DOWN, angle, despiked, nowMs)
                angle >= thresholds.up -> enterStage(Stage.UP, angle, despiked, nowMs)
            }
        } else {
            when {
                angle >= thresholds.down -> enterStage(Stage.DOWN, angle, despiked, nowMs)
                angle <= thresholds.up -> enterStage(Stage.UP, angle, despiked, nowMs)
            }
        }
    }

    private fun updateStageExtreme(angle: Double, despiked: Double, thresholds: Thresholds) {
        val current = stageExtremeAngle ?: angle
        stageExtremeAngle = when {
            stage == Stage.DOWN && thresholds.isDownFirst -> maxOf(current, angle)
            stage == Stage.DOWN && !thresholds.isDownFirst -> minOf(current, angle)
            stage == Stage.UP && thresholds.isDownFirst -> minOf(current, angle)
            stage == Stage.UP && !thresholds.isDownFirst -> maxOf(current, angle)
            else -> angle
        }
        val currentRaw = stageExtremeRaw ?: despiked
        stageExtremeRaw = when {
            stage == Stage.DOWN && thresholds.isDownFirst -> maxOf(currentRaw, despiked)
            stage == Stage.DOWN && !thresholds.isDownFirst -> minOf(currentRaw, despiked)
            stage == Stage.UP && thresholds.isDownFirst -> minOf(currentRaw, despiked)
            stage == Stage.UP && !thresholds.isDownFirst -> maxOf(currentRaw, despiked)
            else -> despiked
        }
    }

    private fun isRepComplete(angle: Double, thresholds: Thresholds, nowMs: Long): Boolean {
        if (nowMs - lastRepTime <= repCooldownMs) return false
        if (framesInState < requiredFrames) return false
        if (nowMs - stageEnteredAtMs < thresholds.minStageDurationMs) return false
        if (!latestPoseWasSynthetic && abs(latestAngularVelocity) < MIN_DIRECTION_REVERSAL_VELOCITY) return false

        val entry = stageEntryAngle ?: return false
        val extremeRaw = stageExtremeRaw ?: return false
        val span = if (thresholds.isDownFirst) {
            extremeRaw - entry
        } else {
            entry - extremeRaw
        }

        return span >= thresholds.minRepSpan && when {
            thresholds.isDownFirst -> angle >= thresholds.up
            else -> angle <= thresholds.up
        }
    }

    private fun enterStage(newStage: Stage, angle: Double, despiked: Double, nowMs: Long) {
        stage = newStage
        framesInState = 1
        stageEnteredAtMs = nowMs
        stageEntryAngle = angle
        stageExtremeAngle = angle
        stageExtremeRaw = despiked
    }

    private fun squatAngle(pose: Pose): Double? {
        val kneeAngle = averageAngle(
            angleForSide(pose, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE),
            angleForSide(pose, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE, PoseLandmark.RIGHT_ANKLE)
        )
        if (kneeAngle != null) return kneeAngle
        return averageAngle(
            angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE),
            angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE)
        )
    }

    private fun plankPoseConfident(pose: Pose): Boolean {
        val leftTorso = hasLandmark(pose, PoseLandmark.LEFT_SHOULDER) && hasLandmark(pose, PoseLandmark.LEFT_HIP)
        val rightTorso = hasLandmark(pose, PoseLandmark.RIGHT_SHOULDER) && hasLandmark(pose, PoseLandmark.RIGHT_HIP)
        val leftArm = hasLandmark(pose, PoseLandmark.LEFT_ELBOW) || hasLandmark(pose, PoseLandmark.LEFT_WRIST)
        val rightArm = hasLandmark(pose, PoseLandmark.RIGHT_ELBOW) || hasLandmark(pose, PoseLandmark.RIGHT_WRIST)
        return (leftTorso && leftArm) || (rightTorso && rightArm)
    }

    private fun hasLandmark(pose: Pose, landmarkType: Int): Boolean {
        val landmark = pose.getPoseLandmark(landmarkType) ?: return false
        return landmark.inFrameLikelihood >= minConfidence
    }

    private fun plankAngle(pose: Pose): Double? {
        val left = angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE)
        val right = angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE)
        val fromKnee = averageAngle(left, right)
        if (fromKnee != null) return fromKnee
        val fromAnkle = averageAngle(
            angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_ANKLE),
            angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_ANKLE)
        )
        if (fromAnkle != null) return fromAnkle
        return torsoHorizontalProxyAngle(pose)
    }

    private fun torsoHorizontalProxyAngle(pose: Pose): Double? {
        val left = sideTorsoProxy(
            pose,
            PoseLandmark.LEFT_SHOULDER,
            PoseLandmark.LEFT_HIP,
            PoseLandmark.LEFT_ELBOW,
            PoseLandmark.LEFT_WRIST
        )
        val right = sideTorsoProxy(
            pose,
            PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.RIGHT_HIP,
            PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.RIGHT_WRIST
        )
        return averageAngle(left, right)
    }

    private fun sideTorsoProxy(
        pose: Pose,
        shoulderId: Int,
        hipId: Int,
        elbowId: Int,
        wristId: Int
    ): Double? {
        val shoulder = pose.getPoseLandmark(shoulderId) ?: return null
        val hip = pose.getPoseLandmark(hipId) ?: return null
        if (shoulder.inFrameLikelihood < minConfidence || hip.inFrameLikelihood < minConfidence) return null

        val support = pose.getPoseLandmark(elbowId).takeIf { it != null && it.inFrameLikelihood >= minConfidence }
            ?: pose.getPoseLandmark(wristId).takeIf { it != null && it.inFrameLikelihood >= minConfidence }
            ?: return null

        val supportBelowShoulder = support.position.y > shoulder.position.y
        if (!supportBelowShoulder) return null

        val dx = (shoulder.position.x - hip.position.x).toDouble()
        val dy = (shoulder.position.y - hip.position.y).toDouble()
        val tiltDegrees = abs(atan2(dy, dx) * 180.0 / Math.PI)
        val horizontalDeviation = abs(tiltDegrees - 90.0).coerceIn(0.0, 90.0)
        val flatness = (90.0 - horizontalDeviation).coerceIn(0.0, 90.0)
        return 90.0 + flatness
    }

    data class PoseUpdate(
        val reps: Int,
        val poseConfident: Boolean,
        val holdActive: Boolean,
        val formPrompt: String?,
        val profileSnapshot: String?
    )

    private enum class Stage { DOWN, UP }
    private data class Thresholds(
        val down: Double,
        val up: Double,
        val isDownFirst: Boolean,
        val minRepSpan: Double,
        val minStageDurationMs: Long,
        val hysteresis: Double
    )

    companion object {
        private const val FORM_PROMPT_STABLE_FRAMES = 32
        private const val ELBOW_ASYMMETRY_DEG = 35.0
        private const val LEG_ASYMMETRY_DEG = 35.0
        private const val CRUNCH_ASYMMETRY_DEG = 30.0
        private const val PLANK_SIDE_ASYMMETRY_DEG = 18.0
        private const val MIN_DIRECTION_REVERSAL_VELOCITY = 6.0
    }

    private fun updateVelocity(angle: Double, nowMs: Long) {
        val prevAngle = lastAngleForVelocity
        val prevMs = lastAngleMs
        if (prevAngle != null && prevMs != null) {
            val dt = (nowMs - prevMs).coerceAtLeast(1L).toDouble() / 1000.0
            latestAngularVelocity = (angle - prevAngle) / dt
        }
        lastAngleForVelocity = angle
        lastAngleMs = nowMs
    }

    private fun adaptiveSmoothedAngle(raw: Double, nowMs: Long): Double {
        val prev = smoothedAngle ?: return raw
        val dt = ((nowMs - (lastAngleMs ?: nowMs)).coerceAtLeast(1L)).toDouble() / 1000.0
        val dx = (raw - prev) / dt
        derivativeEma = ema(derivativeEma, dx, alpha(dt, oneEuroDerivativeCutoff))
        val cutoff = oneEuroMinCutoff + oneEuroBeta * abs(derivativeEma ?: 0.0)
        val a = alpha(dt, cutoff)
        return prev + a * (raw - prev) * (1.0 - smoothingFactor) + (raw - prev) * smoothingFactor * 0.2
    }

    private fun alpha(dt: Double, cutoff: Double): Double {
        val tau = 1.0 / (2.0 * Math.PI * cutoff.coerceAtLeast(0.01))
        return 1.0 / (1.0 + tau / dt.coerceAtLeast(0.001))
    }

    private fun ema(prev: Double?, value: Double, a: Double): Double = if (prev == null) value else prev + a * (value - prev)

    private fun requiredLandmarks(exercise: ExerciseType): List<Int> = when (exercise) {
        ExerciseType.PUSHUPS, ExerciseType.PULL_UPS -> listOf(
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW,
            PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST
        )
        ExerciseType.SQUATS -> listOf(
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE,
            PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE
        )
        ExerciseType.CRUNCHES -> listOf(
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
            PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE
        )
        ExerciseType.PLANK -> listOf(
            PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER,
            PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP
        )
    }

    private fun landmarkCoverage(pose: Pose, ids: List<Int>): Float {
        if (ids.isEmpty()) return 1f
        val values = ids.mapNotNull { pose.getPoseLandmark(it)?.inFrameLikelihood }
        if (values.isEmpty()) return 0f
        return values.average().toFloat().coerceIn(0f, 1f)
    }

    private fun maybeCollectCalibrationSample(angle: Double) {
        if (calibrationAngles.size == 90) calibrationAngles.removeFirst()
        calibrationAngles.addLast(angle)
        if (calibrationAngles.size < 45) return
        val min = calibrationAngles.minOrNull() ?: return
        val max = calibrationAngles.maxOrNull() ?: return
        if (max - min < 18.0) return
        val base = thresholdsFor(exerciseType)
        val adapted = when {
            base.isDownFirst -> base.copy(
                down = ((min + max) / 2.0 - 14.0).coerceIn(base.down - 20.0, base.down + 20.0),
                up = (max - 6.0).coerceIn(base.up - 25.0, base.up + 15.0)
            )
            else -> base.copy(
                down = (max - 6.0).coerceIn(base.down - 20.0, base.down + 20.0),
                up = ((min + max) / 2.0 + 10.0).coerceIn(base.up - 15.0, base.up + 25.0)
            )
        }
        calibratedThresholds = adapted
        profile = CalibrationProfile(minAngle = min, maxAngle = max, updatedAtMs = System.currentTimeMillis())
        profileDirty = true
    }

    private fun effectiveThresholds(): Thresholds {
        val base = calibratedThresholds ?: thresholdsFor(exerciseType)
        val persisted = profile ?: return base
        return if (calibratedThresholds == null) {
            val local = when {
                base.isDownFirst -> base.copy(
                    down = ((persisted.minAngle + persisted.maxAngle) / 2.0 - 14.0).coerceIn(base.down - 24.0, base.down + 24.0),
                    up = (persisted.maxAngle - 6.0).coerceIn(base.up - 30.0, base.up + 18.0)
                )
                else -> base.copy(
                    down = (persisted.maxAngle - 6.0).coerceIn(base.down - 24.0, base.down + 24.0),
                    up = ((persisted.minAngle + persisted.maxAngle) / 2.0 + 10.0).coerceIn(base.up - 18.0, base.up + 30.0)
                )
            }
            calibratedThresholds = local
            local
        } else base
    }

    private fun encodedProfile(): String? {
        val p = profile ?: return null
        if (!profileDirty && calibratedThresholds == null) return null
        return p.encode()
    }

    data class CalibrationProfile(val minAngle: Double, val maxAngle: Double, val updatedAtMs: Long) {
        fun encode(): String = "%.2f,%.2f,%d".format(minAngle, maxAngle, updatedAtMs)

        companion object {
            fun fromEncoded(encoded: String?): CalibrationProfile? {
                if (encoded.isNullOrBlank()) return null
                val parts = encoded.split(",")
                if (parts.size < 3) return null
                val min = parts[0].toDoubleOrNull() ?: return null
                val max = parts[1].toDoubleOrNull() ?: return null
                val ts = parts[2].toLongOrNull() ?: return null
                if (max <= min) return null
                return CalibrationProfile(min, max, ts)
            }
        }
    }
}
