package com.example.zepp_gestures

data class ImuSample(
    val gx: Double,
    val gy: Double,
    val gz: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val ts: Long
)

data class MatchEvent(
    val ts: Long,
    val event: String
)

data class VibrationCommand(
    val count: Int,
    val duration: String = "short"
)

data class IngestResult(
    val received: Int,
    val total: Int,
    val lastSecondCount: Int,
    val bluePoints: Int,
    val redPoints: Int,
    val message: String,
    val vibration: VibrationCommand
)
