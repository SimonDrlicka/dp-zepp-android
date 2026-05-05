package com.example.zepp_gestures.composite

import com.example.zepp_gestures.GestureMode

data class Score(val red: Int, val blue: Int)

/**
 * Phase 2 composite scenario: an ordered chain of gesture IDs the user
 * must execute, plus the expected final state of the gesture-recognition
 * service after the chain runs.
 *
 * Gesture IDs follow the Phase 2 numbering (Phase 1 uses 1-8, Phase 2
 * adds 9 = Flick):
 *
 *   1 Rise Arm | 2 Hand Back | 3 Hand Down |
 *   4 Passivity red | 5 Passivity blue |
 *   6 Warning red | 7 Warning blue |
 *   8 Touche | 9 Flick
 *
 * [id] is the directory-friendly identifier used as the on-disk folder
 * name and CSV/JSON file prefix. [name] is the short human label.
 * [displayName] is the long form shown in the scenario picker.
 */
data class CompositeScenario(
    val id: String,
    val name: String,
    val displayName: String,
    val expectedGestureIds: List<Int>,
    val expectedFinalScore: Score,
    val expectedFinalMode: GestureMode
)
