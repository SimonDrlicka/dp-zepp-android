package com.example.zepp_gestures

/**
 * Catalog entry for a gesture that can be tested in Testing-mode Phase 1.
 *
 * - [id]: numeric gesture ID written into the testing CSV's `detected`
 *   column on the row where the gesture was recognised.
 * - [slug]: kebab-case identifier used in the saved CSV filename.
 * - [displayName]: label shown in the UI dropdown.
 * - [internalName]: must exactly match a [GestureDefinition.name] from
 *   [GestureConfig.gestures] -- it's the link the detection logic uses
 *   to look up the AccelBands when checking matches.
 *
 * Flick is intentionally absent: it's a gyroscope-threshold gesture only
 * meaningful inside a scoring sequence, and Phase 1 evaluates isolated
 * gestures via accelerometer-band matching.
 */
data class TestingGesture(
    val id: Int,
    val slug: String,
    val displayName: String,
    val internalName: String
)

object TestingGestures {
    val ALL: List<TestingGesture> = listOf(
        TestingGesture(1, "rise-arm",            "Rise Arm",              "Hand up"),
        TestingGesture(2, "hand-back",           "Hand Back",             "Hand back"),
        TestingGesture(3, "hand-down",           "Hand Down",             "Hand down"),
        TestingGesture(4, "pasivita-cerveny",    "PASIVITA — červený",    "Passivity red"),
        TestingGesture(5, "pasivita-modry",      "PASIVITA — modrý",      "Passivity blue"),
        TestingGesture(6, "napomenutie-cerveny", "NAPOMENUTIE — červený", "Warning red"),
        TestingGesture(7, "napomenutie-modry",   "NAPOMENUTIE — modrý",   "Warning blue"),
        TestingGesture(8, "touche",              "TOUCHE",                "Touche")
    )
}
