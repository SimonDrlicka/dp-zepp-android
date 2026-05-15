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
    const val POINT_GYRO_GX_THRESHOLD = 7.0
    const val POINT_GYRO_GX_SCALE = 100.0

    const val BUFFER_DURATION_MS = 2_000L

    const val MATCH_DURATION_MS = 200L

    const val SCORING_LOOKBACK_MS = 1_500L

    const val VIBRATE_ON_SCORING_ARMED_IN_PROD = false
    val gestures: List<GestureDefinition> = listOf(
        GestureDefinition(
            name = "Rise Arm",
            message = "Gesture detected: rise arm",
            bands = AccelBands(
                axMin = 7.69,
                axMax = 10.5,
                ayMin = -3.17,
                ayMax = 2.81,
                azMin = 0.67,
                azMax = 6.51
            )
        ),
        GestureDefinition(
            name = "Hand down",
            message = "Gesture detected: hand down",
            bands = AccelBands(
                axMin = -12.84,
                axMax = -7.39,
                ayMin = -4.8,
                ayMax = 0.0,
                azMin = 0.0,
                azMax = 3.0
            )
        ),
        GestureDefinition(
            name = "Warning red",
            message = "Gesture detected: warning red",
            bands = AccelBands(
                axMin = -7.1,
                axMax = -4.62,
                ayMin = -4.35,
                ayMax = 2.03,
                azMin = 7.01,
                azMax = 9.01
            )
        ),
        GestureDefinition(
            name = "Warning blue",
            message = "Gesture detected: warning blue",
            bands = AccelBands(
                axMin = -6.41,
                axMax = -1.39,
                ayMin = -5.03,
                ayMax = -2.38,
                azMin = -10.09,
                azMax = -6.94
            )
        ),
        GestureDefinition(
            name = "Hand back",
            message = "Gesture detected: hand back",
            bands = AccelBands(
                axMin = -6.41,
                axMax = -3.12,
                ayMin = -10.42,
                ayMax = -7.67,
                azMin = -1.63,
                azMax = 3.89
            )
        ),
        GestureDefinition(
            name = "Passivity red",
            message = "Gesture detected: passivity red",
            bands = AccelBands(
                axMin = -1.43,
                axMax = 1.46,
                ayMin = -11.07,
                ayMax = -7.47,
                azMin = -4.55,
                azMax = 5.94
            )
        ),
        GestureDefinition(
            name = "Passivity blue",
            message = "Gesture detected: passivity blue",
            bands = AccelBands(
                axMin = -0.6,
                axMax = 1.08,
                ayMin = -4.54,
                ayMax = 1.27,
                azMin = -10.41,
                azMax = -7.99
            )
        ),
        GestureDefinition(
            name = "Touche",
            message = "Gesture detected: touche",
            bands = AccelBands(
                axMin = 5.14,
                axMax = 6.6,
                ayMin = -8.43,
                ayMax = -6.0,
                azMin = -1.1,
                azMax = 4.73
            )
        ),
    )
}
