package com.example.visionfit.model

enum class ExerciseType(val displayName: String, val defaultSecondsPerRep: Int) {
    PUSHUPS("Pushups", 30),
    SQUATS("Squats", 30),
    PULL_UPS("Pull Ups", 30),
    CRUNCHES("Crunches", 30),
    PLANK("Plank", 1)
}
