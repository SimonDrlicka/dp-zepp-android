package com.example.zepp_gestures

enum class GestureMode(val label: String) {
    WAITING("waiting"),
    GESTURE_RED("gesture red"),
    GESTURE_BLUE("gesture blue"),
    WARNING_RED("warning red"),
    WARNING_BLUE("warning blue");

    val isWarning: Boolean get() = this == WARNING_RED || this == WARNING_BLUE
    val isScoring: Boolean get() = this == GESTURE_RED || this == GESTURE_BLUE || this.isWarning
}
