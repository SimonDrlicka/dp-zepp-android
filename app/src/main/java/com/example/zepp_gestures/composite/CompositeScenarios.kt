package com.example.zepp_gestures.composite

import com.example.zepp_gestures.GestureMode

/**
 * Catalog of the eight Phase 2 composite scenarios (A-H). Passivity
 * scenarios are intentionally excluded from this iteration -- the
 * 30-second timer + auto-penalty branch is disabled in Phase 2 anyway.
 */
object CompositeScenarios {

    val ALL: List<CompositeScenario> = listOf(
        CompositeScenario(
            id = "scenario-a",
            name = "Bodovanie červeného (1 bod)",
            displayName = "A — Bodovanie červeného (1 bod)",
            expectedGestureIds = listOf(1, 9, 3),
            expectedFinalScore = Score(red = 1, blue = 0),
            expectedFinalMode = GestureMode.WAITING
        ),
        CompositeScenario(
            id = "scenario-b",
            name = "Bodovanie červeného (3 body)",
            displayName = "B — Bodovanie červeného (3 body)",
            expectedGestureIds = listOf(1, 9, 9, 9, 3),
            expectedFinalScore = Score(red = 3, blue = 0),
            expectedFinalMode = GestureMode.WAITING
        ),
        CompositeScenario(
            id = "scenario-c",
            name = "Bodovanie modrého (2 body)",
            displayName = "C — Bodovanie modrého (2 body)",
            expectedGestureIds = listOf(2, 9, 9, 3),
            expectedFinalScore = Score(red = 0, blue = 2),
            expectedFinalMode = GestureMode.WAITING
        ),
        CompositeScenario(
            id = "scenario-d",
            name = "Napomenutie červeného + 2 body modrému",
            displayName = "D — Napomenutie červeného + 2 body modrému",
            expectedGestureIds = listOf(6, 9, 9, 3),
            expectedFinalScore = Score(red = 0, blue = 2),
            expectedFinalMode = GestureMode.WAITING
        ),
        CompositeScenario(
            id = "scenario-e",
            name = "Napomenutie modrého + 1 bod červenému",
            displayName = "E — Napomenutie modrého + 1 bod červenému",
            expectedGestureIds = listOf(7, 9, 3),
            expectedFinalScore = Score(red = 1, blue = 0),
            expectedFinalMode = GestureMode.WAITING
        ),
        CompositeScenario(
            id = "scenario-f",
            name = "Bodovanie modrého a následne červeného",
            displayName = "F — Bodovanie modrého a následne červeného",
            expectedGestureIds = listOf(2, 9, 9, 3, 1, 9, 3),
            expectedFinalScore = Score(red = 1, blue = 2),
            expectedFinalMode = GestureMode.WAITING
        ),
        CompositeScenario(
            id = "scenario-g",
            name = "Mix — napomenutia a bodovanie",
            displayName = "G — Mix napomenutí a bodovaní",
            expectedGestureIds = listOf(7, 9, 3, 1, 9, 9, 3, 6, 9, 3),
            expectedFinalScore = Score(red = 3, blue = 1),
            expectedFinalMode = GestureMode.WAITING
        ),
        CompositeScenario(
            id = "scenario-h",
            name = "TOUCHE",
            displayName = "H — TOUCHE",
            expectedGestureIds = listOf(8),
            // After Touche the match ends; score is irrelevant. Use the
            // baseline (0,0) to keep equality checks straightforward.
            expectedFinalScore = Score(red = 0, blue = 0),
            expectedFinalMode = GestureMode.WAITING
        )
    )

    fun byId(id: String): CompositeScenario? = ALL.firstOrNull { it.id == id }
}

/**
 * Display name lookup for composite gesture IDs (1-9). Used by the
 * sequence-tracker UI and the JSON failure-reason builder.
 */
fun compositeGestureName(id: Int): String = when (id) {
    1 -> "Rise Arm"
    2 -> "Hand Back"
    3 -> "Hand Down"
    4 -> "PASIVITA — červený"
    5 -> "PASIVITA — modrý"
    6 -> "NAPOMENUTIE — červený"
    7 -> "NAPOMENUTIE — modrý"
    8 -> "TOUCHE"
    9 -> "Flick"
    else -> "Unknown ($id)"
}
