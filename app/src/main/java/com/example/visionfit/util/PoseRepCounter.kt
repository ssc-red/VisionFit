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
    private var smoothedAngle: Double? = null
    private val smoothingFactor = 0.25
    private var framesInState = 0
    private val requiredFrames = 4
    private val recentRaw = ArrayDeque<Double>(3)

    private var lastRepTime = Long.MIN_VALUE / 2
    private val defaultRepCooldownMs = 450L
    private var stageEnteredAtMs = 0L
    private var stageEntryAngle: Double? = null
    private var stageEntryRaw: Double? = null
    private var stageExtremeAngle: Double? = null
    private var stageExtremeRaw: Double? = null
    private val hysteresisMargin = 8.0
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

    private var previousRepCompletionMs: Long? = null
    private var rapidRepCueUntilMs = 0L
    private var rapidVelocityCueUntilMs = 0L
    private var consecutiveHighVelocityFrames = 0
    /** Push-up completion needs the joint near full extension briefly (blocks one-frame despike counts). */
    private var consecutivePushupNearTopFrames = 0

    private fun resetFormPromptState() {
        persistedFormPrompt = null
        pendingPrompt = null
        pendingPromptFrames = 0
    }

    /** Looser on upper body / trunk so front camera and partial framing still track. */
    private fun landmarkCutoff(): Float = when (exerciseType) {
        ExerciseType.PUSHUPS, ExerciseType.PULL_UPS -> 0.32f
        ExerciseType.SQUATS -> 0.38f
        ExerciseType.PLANK -> 0.34f
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
            ExerciseType.PULL_UPS -> run {
                val left = angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST)
                val right = angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST)
                if (left == null || right == null) null else (left + right) / 2.0
            }
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
            consecutiveHighVelocityFrames = 0
            consecutivePushupNearTopFrames = 0
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
        trackHighVelocityMotion(nowMs)
        val angle = adaptiveSmoothedAngle(despiked, nowMs)
        val thresholds = effectiveThresholds()
        smoothedAngle = angle
        latestPoseWasSynthetic = pose == null
        maybeCollectCalibrationSample(despiked)

        if (stage == null) {
            maybeEnterInitialStage(angle, despiked, thresholds, nowMs, rawAngle)
            return PoseUpdate(
                reps = reps,
                poseConfident = true,
                holdActive = false,
                formPrompt = resolveFormPromptLayered(pose, angle, stage = null, nowMs),
                profileSnapshot = encodedProfile()
            )
        }

        framesInState += 1
        when (stage!!) {
            Stage.DOWN -> {
                updateStageExtreme(angle, despiked, thresholds)
                if (thresholds.isDownFirst && exerciseType == ExerciseType.PUSHUPS) {
                    val clearlyExtended =
                        extendTowardUpFirst(angle, despiked) > thresholds.up + PUSHUP_NEAR_TOP_CLEARANCE_SLOP_DEG
                    if (clearlyExtended) consecutivePushupNearTopFrames++ else consecutivePushupNearTopFrames = 0
                }
                if (isRepComplete(angle, despiked, thresholds, nowMs)) {
                    registerRepPacing(nowMs)
                    reps++
                    lastRepTime = nowMs
                    enterStage(Stage.UP, angle, despiked, nowMs)
                }
            }

            Stage.UP -> {
                updateStageExtreme(angle, despiked, thresholds)
                if (thresholds.isDownFirst) {
                    if (flexTowardDownFirst(angle, despiked) <= thresholds.down - thresholds.hysteresis &&
                        framesInState >= effectiveRequiredFrames()
                    ) {
                        enterStage(Stage.DOWN, angle, despiked, nowMs)
                    }
                } else {
                    if (extendTowardUpFirst(angle, despiked) >= thresholds.down + thresholds.hysteresis &&
                        framesInState >= effectiveRequiredFrames()
                    ) {
                        enterStage(Stage.DOWN, angle, despiked, nowMs)
                    }
                }
            }
        }

        return PoseUpdate(
            reps = reps,
            poseConfident = true,
            holdActive = false,
            formPrompt = resolveFormPromptLayered(pose, angle, stage, nowMs),
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
                formPrompt = "Step back—keep shoulders, hips, and feet or knees in frame (side or front).",
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

    /** Coverage/asymmetry stay ahead of pacing cues; rapid cues skip the long stability debounce. */
    private fun resolveFormPromptLayered(pose: Pose?, angle: Double?, stage: Stage?, nowMs: Long): String? {
        val resolvedAngle = angle ?: return resolveFormPrompt(formPromptSmart(pose, exerciseType, angle, stage))
        frontViewCoverageCue(pose, exerciseType)?.let { return resolveFormPrompt(it) }
        postureAsymmetry(pose, exerciseType)?.let { return resolveFormPrompt(it) }
        val rapid = rapidMovementFormCue(nowMs)
        val coaching = rapid ?: formPromptCoachingCue(exerciseType, resolvedAngle, stage)
        return resolveFormPrompt(coaching, immediateSwitch = rapid != null)
    }

    private fun formPromptCoachingCue(exercise: ExerciseType, resolvedAngle: Double, stage: Stage?): String? {
        val nuanced = stageRefinedCue(exercise, resolvedAngle, stage)
        return nuanced ?: baselineAngleCue(exercise, resolvedAngle)
    }

    private fun resolveFormPrompt(instant: String?, immediateSwitch: Boolean = false): String? {
        if (immediateSwitch && instant != null) {
            persistedFormPrompt = instant
            pendingPrompt = instant
            pendingPromptFrames = FORM_PROMPT_STABLE_FRAMES
            return instant
        }
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
                return formPromptCoachingCue(exercise, resolvedAngle, stage)
            }
        }
    }

    private fun registerRepPacing(nowMs: Long) {
        if (exerciseType == ExerciseType.PLANK) return
        val interval = previousRepCompletionMs?.let { prev -> nowMs - prev }
        previousRepCompletionMs = nowMs
        if (interval != null && interval < rapidRepIntervalThresholdMs()) {
            rapidRepCueUntilMs = nowMs + RAPID_REP_CUE_DURATION_MS
        }
    }

    private fun rapidRepIntervalThresholdMs(): Long = when (exerciseType) {
        ExerciseType.PUSHUPS -> 360L
        ExerciseType.PULL_UPS -> 440L
        ExerciseType.SQUATS -> 400L
        ExerciseType.PLANK -> Long.MAX_VALUE
    }

    private fun trackHighVelocityMotion(nowMs: Long) {
        if (exerciseType == ExerciseType.PLANK) return
        val threshold = rapidAngularVelocityThresholdDegPerSec()
        if (abs(latestAngularVelocity) >= threshold) {
            consecutiveHighVelocityFrames++
            if (consecutiveHighVelocityFrames >= HIGH_VELOCITY_MIN_FRAMES) {
                rapidVelocityCueUntilMs = nowMs + RAPID_VELOCITY_CUE_DURATION_MS
            }
        } else {
            consecutiveHighVelocityFrames = 0
        }
    }

    private fun rapidAngularVelocityThresholdDegPerSec(): Double = when (exerciseType) {
        ExerciseType.PUSHUPS, ExerciseType.PULL_UPS -> 820.0
        ExerciseType.SQUATS -> 720.0
        ExerciseType.PLANK -> Double.MAX_VALUE
    }

    private fun rapidMovementFormCue(nowMs: Long): String? {
        if (exerciseType == ExerciseType.PLANK) return null
        if (nowMs <= rapidRepCueUntilMs) return rapidRepPacingMessage()
        if (nowMs <= rapidVelocityCueUntilMs) return rapidVelocityMessage()
        return null
    }

    private fun rapidRepPacingMessage(): String = when (exerciseType) {
        ExerciseType.PUSHUPS ->
            "That pace is extremely fast—slow reps so ribs stay braced and wrists stay under shoulders."
        ExerciseType.PULL_UPS ->
            "You're rushing reps—pause briefly at the hang and pull without kicking or kipping."
        ExerciseType.SQUATS ->
            "Ease the tempo—sit evenly into your hips before standing each rep."
        ExerciseType.PLANK -> ""
    }

    private fun rapidVelocityMessage(): String = when (exerciseType) {
        ExerciseType.PUSHUPS, ExerciseType.PULL_UPS ->
            "Control the movement—smooth elbows through the range instead of snapping."
        ExerciseType.SQUATS ->
            "Dial back speed—track knees over toes through the whole squat."
        ExerciseType.PLANK -> ""
    }

    private fun frontViewCoverageCue(pose: Pose?, exercise: ExerciseType): String? {
        if (pose == null) return null
        val ids = when (exercise) {
            ExerciseType.PUSHUPS, ExerciseType.PULL_UPS ->
                listOf(PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.RIGHT_ELBOW, PoseLandmark.LEFT_WRIST, PoseLandmark.RIGHT_WRIST)
            ExerciseType.SQUATS ->
                listOf(PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE)
            ExerciseType.PLANK -> emptyList()
        }
        if (ids.isEmpty()) return null
        val coverage = landmarkCoverage(pose, ids)
        val minCoverage = when (exercise) {
            ExerciseType.PUSHUPS, ExerciseType.PULL_UPS -> 0.36f
            ExerciseType.SQUATS -> 0.42f
            ExerciseType.PLANK -> 0.48f
        }
        return if (coverage < minCoverage) {
            when (exercise) {
                ExerciseType.PUSHUPS, ExerciseType.PULL_UPS ->
                    "Show shoulders and arms—step back; legs can be off-screen."
                else ->
                    "Phone is too front-facing for full tracking—step back and keep both sides visible."
            }
        } else null
    }

    private fun postureAsymmetry(pose: Pose?, exercise: ExerciseType): String? {
        if (pose == null) return null
        return when (exercise) {
            ExerciseType.PUSHUPS, ExerciseType.PULL_UPS -> elbowAsymmetryCue(pose)
            ExerciseType.SQUATS -> kneeAsymmetryCue(pose)
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

    private fun angleForSide(
        pose: Pose,
        first: Int,
        mid: Int,
        last: Int,
        cutoff: Float = landmarkCutoff()
    ): Double? {
        val f = pose.getPoseLandmark(first) ?: return null
        val m = pose.getPoseLandmark(mid) ?: return null
        val l = pose.getPoseLandmark(last) ?: return null
        if (f.inFrameLikelihood < cutoff || m.inFrameLikelihood < cutoff || l.inFrameLikelihood < cutoff) return null
        return angleDegrees2D(f.position3D, m.position3D, l.position3D)
    }

    private fun midpointLandmarkXY(pose: Pose, leftId: Int, rightId: Int, cutoff: Float): Triple<Float, Float, Float>? {
        val l = pose.getPoseLandmark(leftId) ?: return null
        val r = pose.getPoseLandmark(rightId) ?: return null
        if (l.inFrameLikelihood < cutoff || r.inFrameLikelihood < cutoff) return null
        val lp = l.position3D
        val rp = r.position3D
        return Triple(
            (lp.x + rp.x) / 2f,
            (lp.y + rp.y) / 2f,
            (lp.z + rp.z) / 2f
        )
    }

    private fun isLikelyFrontPlankOrientation(pose: Pose): Boolean {
        val ls = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER) ?: return false
        val rs = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER) ?: return false
        val lh = pose.getPoseLandmark(PoseLandmark.LEFT_HIP) ?: return false
        val rh = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP) ?: return false
        val c = landmarkCutoff()
        if (ls.inFrameLikelihood < c || rs.inFrameLikelihood < c || lh.inFrameLikelihood < c || rh.inFrameLikelihood < c) {
            return false
        }
        val shoulderSpan = abs(ls.position.x - rs.position.x)
        val hipSpan = abs(lh.position.x - rh.position.x)
        val torsoH = abs((ls.position.y + rs.position.y) / 2f - (lh.position.y + rh.position.y) / 2f).coerceAtLeast(1f)
        return shoulderSpan > torsoH * 0.72f && hipSpan > torsoH * 0.45f
    }

    private fun plankMidlineHipAngle(pose: Pose): Double? {
        val c = landmarkCutoff()
        val s = midpointLandmarkXY(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER, c) ?: return null
        val h = midpointLandmarkXY(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP, c) ?: return null
        val foot = midpointLandmarkXY(pose, PoseLandmark.LEFT_ANKLE, PoseLandmark.RIGHT_ANKLE, c)
            ?: midpointLandmarkXY(pose, PoseLandmark.LEFT_KNEE, PoseLandmark.RIGHT_KNEE, c) ?: return null
        return angleAtPoints(
            s.first.toDouble(), s.second.toDouble(),
            h.first.toDouble(), h.second.toDouble(),
            foot.first.toDouble(), foot.second.toDouble()
        )
    }

    private fun minSignedCompletionVelocity(): Double = when (exerciseType) {
        ExerciseType.PUSHUPS -> 2.0
        ExerciseType.PULL_UPS -> 4.2
        ExerciseType.SQUATS -> 4.0
        else -> MIN_SIGNED_COMPLETION_VELOCITY
    }

    private fun slowRepSpanSlop(): Double = when (exerciseType) {
        ExerciseType.PUSHUPS -> 6.0
        ExerciseType.PULL_UPS -> 7.0
        ExerciseType.SQUATS -> 8.0
        else -> SLOW_REP_SPAN_SLOP
    }

    private fun slowRepMinFrames(): Int = when (exerciseType) {
        ExerciseType.PUSHUPS, ExerciseType.PULL_UPS -> 4
        else -> SLOW_REP_MIN_FRAMES
    }

    private fun angleDegrees2D(a: PointF3D, b: PointF3D, c: PointF3D): Double =
        angleAtPoints(
            a.x.toDouble(), a.y.toDouble(),
            b.x.toDouble(), b.y.toDouble(),
            c.x.toDouble(), c.y.toDouble()
        )

    private fun angleAtPoints(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double {
        val radians = atan2(cy - by, cx - bx) - atan2(ay - by, ax - bx)
        var angle = abs(radians * 180.0 / Math.PI)
        if (angle > 180.0) angle = 360.0 - angle
        return angle
    }

    private fun averageAngle(left: Double?, right: Double?) = when {
        left != null && right != null -> (left + right) / 2.0
        else -> left ?: right
    }

    private fun thresholdsFor(exercise: ExerciseType) = when (exercise) {
        ExerciseType.PUSHUPS -> Thresholds(down = 100.0, up = 130.0, isDownFirst = true, minRepSpan = 22.0, minStageDurationMs = 40L, hysteresis = hysteresisMargin)
        // Shallower "bottom" (larger knee angle) so parallel depth is not required.
        ExerciseType.SQUATS -> Thresholds(down = 118.0, up = 152.0, isDownFirst = true, minRepSpan = 26.0, minStageDurationMs = 180L, hysteresis = hysteresisMargin)
        // Pull-ups can jitter a lot when one arm is occluded; require more ROM and time-in-stage.
        // Note: "straight arms" on camera often reads ~125-140°, so keep [down] forgiving.
        ExerciseType.PULL_UPS -> Thresholds(down = 128.0, up = 100.0, isDownFirst = false, minRepSpan = 26.0, minStageDurationMs = 180L, hysteresis = 8.0)
        ExerciseType.PLANK -> Thresholds(down = plankAngleMin, up = plankAngleMin, isDownFirst = true, minRepSpan = 0.0, minStageDurationMs = 0L, hysteresis = 0.0)
    }

    /** Uses raw estimate when smoothed hasn't caught up yet (continuous reps). */
    private fun maybeEnterInitialStage(angle: Double, despiked: Double, thresholds: Thresholds, nowMs: Long, rawEstimate: Double?) {
        val rawish = rawEstimate ?: despiked
        val flex = minOf(angle, despiked, rawish)
        val ext = maxOf(angle, despiked, rawish)
        if (thresholds.isDownFirst) {
            when {
                flex <= thresholds.down -> enterStage(Stage.DOWN, angle, despiked, nowMs)
                ext >= thresholds.up -> enterStage(Stage.UP, angle, despiked, nowMs)
            }
        } else {
            when {
                ext >= thresholds.down -> enterStage(Stage.DOWN, angle, despiked, nowMs)
                flex <= thresholds.up -> enterStage(Stage.UP, angle, despiked, nowMs)
            }
        }
    }

    private fun flexTowardDownFirst(smoothed: Double, raw: Double) = minOf(smoothed, raw)
    private fun flexTowardUpFirst(smoothed: Double, raw: Double) = minOf(smoothed, raw)
    private fun extendTowardUpFirst(smoothed: Double, raw: Double) = maxOf(smoothed, raw)

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

    private fun isRepComplete(angle: Double, despiked: Double, thresholds: Thresholds, nowMs: Long): Boolean {
        if (nowMs - lastRepTime <= effectiveRepCooldownMs()) return false
        if (framesInState < effectiveRequiredFrames()) return false

        val entryRaw = stageEntryRaw ?: return false
        val extremeRaw = stageExtremeRaw ?: return false
        val span = if (thresholds.isDownFirst) {
            extremeRaw - entryRaw
        } else {
            entryRaw - extremeRaw
        }

        val stageMs = nowMs - stageEnteredAtMs
        val romExplosiveBypass = explosiveStrongRomBypass(thresholds, span)
        if (stageMs < thresholds.minStageDurationMs && !romExplosiveBypass) return false

        val velocityGateActive = velocityGateApplies()
        val velCut = minSignedCompletionVelocity()
        val completionVelocityOk = if (thresholds.isDownFirst) {
            latestAngularVelocity >= velCut
        } else {
            latestAngularVelocity <= -velCut
        }
        val slowDeepRep = span >= thresholds.minRepSpan + slowRepSpanSlop() &&
            framesInState >= slowRepMinFrames()
        if (exerciseType == ExerciseType.PUSHUPS &&
            thresholds.isDownFirst &&
            consecutivePushupNearTopFrames < PUSHUP_NEAR_TOP_FRAMES_REQUIRED
        ) {
            return false
        }

        val turnaroundBypass = explosiveTurnaroundBypass(thresholds, span, angle, despiked)
        if (velocityGateActive &&
            !latestPoseWasSynthetic &&
            !completionVelocityOk &&
            !slowDeepRep &&
            !turnaroundBypass
        ) {
            return false
        }

        val atTarget = when {
            thresholds.isDownFirst -> extendTowardUpFirst(angle, despiked) >= thresholds.up
            else -> flexTowardUpFirst(angle, despiked) <= thresholds.up
        }
        return span >= thresholds.minRepSpan && atTarget
    }

    private fun velocityGateApplies(): Boolean = true

    /** Short cooldown so fast push-ups are not capped at ~2 Hz. */
    private fun effectiveRepCooldownMs(): Long = when (exerciseType) {
        ExerciseType.PUSHUPS -> 180L
        ExerciseType.PULL_UPS -> 420L
        else -> defaultRepCooldownMs
    }

    private fun effectiveRequiredFrames(): Int = when (exerciseType) {
        ExerciseType.PUSHUPS -> 2
        else -> requiredFrames
    }

    /** Fast full-ROM rep may hit velocity ~0 at the top/bottom turn; smoothed angle can lag brief peaks. */
    private fun explosiveTurnaroundBypass(thresholds: Thresholds, span: Double, angle: Double, despiked: Double): Boolean {
        if (framesInState < effectiveRequiredFrames()) return false
        val minRom = thresholds.minRepSpan * 1.28
        return when (exerciseType) {
            ExerciseType.PUSHUPS ->
                thresholds.isDownFirst &&
                    span >= minRom &&
                    extendTowardUpFirst(angle, despiked) >= thresholds.up &&
                    latestAngularVelocity >= PUSHUP_TURNAROUND_MIN_VEL
            ExerciseType.PULL_UPS ->
                !thresholds.isDownFirst &&
                    span >= minRom &&
                    flexTowardUpFirst(angle, despiked) <= thresholds.up
            else -> false
        }
    }

    /** Allows completing phase faster than minStageDurationMs only on very large ROM (not shallow spikes). */
    private fun explosiveStrongRomBypass(thresholds: Thresholds, span: Double): Boolean {
        return when (exerciseType) {
            ExerciseType.PUSHUPS ->
                thresholds.isDownFirst && span >= thresholds.minRepSpan * 1.35
            ExerciseType.PULL_UPS ->
                !thresholds.isDownFirst && span >= thresholds.minRepSpan * 1.48
            else -> false
        }
    }

    private fun enterStage(newStage: Stage, angle: Double, despiked: Double, nowMs: Long) {
        if (newStage == Stage.DOWN && exerciseType == ExerciseType.PUSHUPS) {
            consecutivePushupNearTopFrames = 0
        }
        stage = newStage
        framesInState = 1
        stageEnteredAtMs = nowMs
        stageEntryAngle = angle
        stageEntryRaw = despiked
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
        val c = landmarkCutoff()
        if (plankMidlineSupportPresent(pose, c)) return true
        val leftTorso = hasLandmark(pose, PoseLandmark.LEFT_SHOULDER) && hasLandmark(pose, PoseLandmark.LEFT_HIP)
        val rightTorso = hasLandmark(pose, PoseLandmark.RIGHT_SHOULDER) && hasLandmark(pose, PoseLandmark.RIGHT_HIP)
        val leftArm = hasLandmark(pose, PoseLandmark.LEFT_ELBOW) || hasLandmark(pose, PoseLandmark.LEFT_WRIST)
        val rightArm = hasLandmark(pose, PoseLandmark.RIGHT_ELBOW) || hasLandmark(pose, PoseLandmark.RIGHT_WRIST)
        return (leftTorso && leftArm) || (rightTorso && rightArm)
    }

    /** Front-facing plank: both sides of torso + feet/knees + some arm support visible. */
    private fun plankMidlineSupportPresent(pose: Pose, c: Float): Boolean {
        fun ok(id: Int) = (pose.getPoseLandmark(id)?.inFrameLikelihood ?: 0f) >= c
        val torso = ok(PoseLandmark.LEFT_SHOULDER) && ok(PoseLandmark.RIGHT_SHOULDER) &&
            ok(PoseLandmark.LEFT_HIP) && ok(PoseLandmark.RIGHT_HIP)
        if (!torso) return false
        val lower = (ok(PoseLandmark.LEFT_ANKLE) && ok(PoseLandmark.RIGHT_ANKLE)) ||
            (ok(PoseLandmark.LEFT_KNEE) && ok(PoseLandmark.RIGHT_KNEE))
        val arms = ok(PoseLandmark.LEFT_WRIST) || ok(PoseLandmark.RIGHT_WRIST) ||
            ok(PoseLandmark.LEFT_ELBOW) || ok(PoseLandmark.RIGHT_ELBOW)
        return lower && arms
    }

    private fun hasLandmark(pose: Pose, landmarkType: Int): Boolean {
        val landmark = pose.getPoseLandmark(landmarkType) ?: return false
        return landmark.inFrameLikelihood >= landmarkCutoff()
    }

    private fun plankAngle(pose: Pose): Double? {
        val midline = plankMidlineHipAngle(pose)
        val left = angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE)
        val right = angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_KNEE)
        val sideAvg = averageAngle(left, right)

        if (isLikelyFrontPlankOrientation(pose) && midline != null) return midline
        if (left != null && right != null && abs(left - right) > 32.0 && midline != null) return midline

        if (sideAvg != null) return sideAvg
        val fromAnkle = averageAngle(
            angleForSide(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_ANKLE),
            angleForSide(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_ANKLE)
        )
        if (fromAnkle != null) return fromAnkle
        return midline ?: torsoHorizontalProxyAngle(pose)
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
        val cut = landmarkCutoff()
        if (shoulder.inFrameLikelihood < cut || hip.inFrameLikelihood < cut) return null

        val support = pose.getPoseLandmark(elbowId).takeIf { it != null && it.inFrameLikelihood >= cut }
            ?: pose.getPoseLandmark(wristId).takeIf { it != null && it.inFrameLikelihood >= cut }
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
        private const val RAPID_REP_CUE_DURATION_MS = 3200L
        private const val RAPID_VELOCITY_CUE_DURATION_MS = 2200L
        private const val HIGH_VELOCITY_MIN_FRAMES = 5
        /** Block turnaround bypass on obvious downward flicker while "at the top". */
        private const val PUSHUP_TURNAROUND_MIN_VEL = -4.5
        private const val PUSHUP_NEAR_TOP_FRAMES_REQUIRED = 3
        /** Frames must exceed soft lockout by this amount to count toward the streak (strict `> up + slop`). */
        private const val PUSHUP_NEAR_TOP_CLEARANCE_SLOP_DEG = 20.0
        private const val FORM_PROMPT_STABLE_FRAMES = 32
        private const val ELBOW_ASYMMETRY_DEG = 35.0
        private const val LEG_ASYMMETRY_DEG = 35.0
        private const val PLANK_SIDE_ASYMMETRY_DEG = 18.0
        /** Deg/s; sign must match motion toward finish (reduces bounce/noise counts). */
        private const val MIN_SIGNED_COMPLETION_VELOCITY = 4.5
        /** Extra degrees beyond [Thresholds.minRepSpan] to accept slow reps with damped velocity. */
        private const val SLOW_REP_SPAN_SLOP = 10.0
        private const val SLOW_REP_MIN_FRAMES = 6
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
        if (max - min < calibrationMotionMin()) return
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

    private fun calibrationMotionMin(): Double = when (exerciseType) {
        else -> 18.0
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
