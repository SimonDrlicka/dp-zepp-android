package com.example.zepp_gestures

data class AccelBands(
    val axMin: Double,
    val axMax: Double,
    val ayMin: Double,
    val ayMax: Double,
    val azMin: Double,
    val azMax: Double
)

data class GestureDefinition(
    val name: String,
    val message: String,
    val bands: AccelBands
)

object GestureConfig {
    const val POINT_GYRO_THRESHOLD = 7.0
    const val POINT_GYRO_SCALE = 100.0
    val gestures: List<GestureDefinition> = listOf(
        GestureDefinition(
            name = "Hand up",
            message = "Gesture detected: hand up",
            bands = AccelBands(
                axMin = 8.5,
                axMax = 10.5,
                ayMin = -5.0,
                ayMax = 0.0,
                azMin = 2.5,
                azMax = 5.0
            )
        ),
        GestureDefinition(
            name = "Hand down",
            message = "Gesture detected: hand down",
            bands = AccelBands(
                axMin = -11.0,
                axMax = -9.0,
                ayMin = -4.0,
                ayMax = -2.0,
                azMin = 0.0,
                azMax = 3.0
            )
        ),
        GestureDefinition(
            name = "Red warning",
            message = "Gesture detected: red warning",
            bands = AccelBands(
                axMin = -7.1,
                axMax = -4.62,
                ayMin = -1.58,
                ayMax = 0.7,
                azMin = 7.01,
                azMax = 9.01
            )
        ),
        GestureDefinition(
            name = "Warning blue",
            message = "Gesture detected: warning blue",
            bands = AccelBands(
                axMin = -6.41,
                axMax = -3.12,
                ayMin = -10.42,
                ayMax = -7.67,
                azMin = -1.63,
                azMax = 3.89
            )
        )
    )
}
