package com.example.zepp_gestures

enum class GestureMode {
    WAITING,
    GESTURE_RED,
    GESTURE_BLUE,
    WARNING_RED,
    WARNING_BLUE;

    val isWarning: Boolean get() = this == WARNING_RED || this == WARNING_BLUE
    val isScoring: Boolean get() = this == GESTURE_RED || this == GESTURE_BLUE || this.isWarning
}
