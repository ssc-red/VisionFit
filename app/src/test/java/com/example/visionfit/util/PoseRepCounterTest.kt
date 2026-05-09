package com.example.visionfit.util

import com.example.visionfit.model.ExerciseType
import org.junit.Assert.assertEquals
import org.junit.Test

class PoseRepCounterTest {
    @Test
    fun pushupNoiseSpikeDoesNotCountAsRep() {
        val counter = PoseRepCounter(ExerciseType.PUSHUPS)

        counter.onAngleSample(165.0, poseConfident = true, nowMs = 0L)
        counter.onAngleSample(90.0, poseConfident = true, nowMs = 100L)
        counter.onAngleSample(160.0, poseConfident = true, nowMs = 180L)

        assertEquals(0, counter.onAngleSample(160.0, poseConfident = true, nowMs = 260L).reps)
    }

    @Test
    fun pushupRealRepCountsOnce() {
        val counter = PoseRepCounter(ExerciseType.PUSHUPS)
        var now = 0L
        fun sample(angle: Double) {
            counter.onAngleSample(angle, poseConfident = true, nowMs = now)
            now += 120L
        }
        repeat(6) { sample(164.0) }
        repeat(10) { sample(56.0) }
        repeat(14) { sample(158.0) }
        val update = counter.onAngleSample(160.0, poseConfident = true, nowMs = now)
        assertEquals(1, update.reps)
    }

    @Test
    fun briefPoseDropoutDoesNotResetAnInProgressRep() {
        val counter = PoseRepCounter(ExerciseType.PUSHUPS)
        var now = 0L
        fun sample(angle: Double) {
            counter.onAngleSample(angle, poseConfident = true, nowMs = now)
            now += 120L
        }
        sample(165.0)
        repeat(8) { sample(52.0) }
        counter.onAngleSample(null, poseConfident = false, nowMs = now)
        now += 80L
        repeat(14) { sample(158.0) }
        assertEquals(1, counter.onAngleSample(158.0, poseConfident = true, nowMs = now).reps)
    }

    @Test
    fun pushupBoundaryFlickerWithWrongSignVelocityDoesNotCount() {
        val counter = PoseRepCounter(ExerciseType.PUSHUPS)
        var now = 0L
        fun sample(angle: Double) {
            counter.onAngleSample(angle, poseConfident = true, nowMs = now)
            now += 120L
        }
        repeat(5) { sample(164.0) }
        repeat(5) { sample(92.0) }
        sample(164.0)
        sample(150.0)
        sample(164.0)
        assertEquals(0, counter.onAngleSample(164.0, poseConfident = true, nowMs = now).reps)
    }

    @Test
    fun pullupCountsWithoutFullyStraightArmsInDegrees() {
        val counter = PoseRepCounter(ExerciseType.PULL_UPS)
        var now = 0L
        fun sample(angle: Double) {
            counter.onAngleSample(angle, poseConfident = true, nowMs = now)
            now += 130L
        }
        repeat(5) { sample(132.0) }
        repeat(12) { sample(68.0) }
        assertEquals(1, counter.onAngleSample(130.0, poseConfident = true, nowMs = now).reps)
    }
}

