package com.example.zepp_gestures

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
