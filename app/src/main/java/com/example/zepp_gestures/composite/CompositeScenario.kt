package com.example.zepp_gestures.composite

import com.example.zepp_gestures.GestureMode

data class Score(val red: Int, val blue: Int)

data class CompositeScenario(
    val id: String,
    val name: String,
    val displayName: String,
    val expectedGestureIds: List<Int>,
    val expectedFinalScore: Score,
    val expectedFinalMode: GestureMode
)
