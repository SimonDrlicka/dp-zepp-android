package com.example.zepp_gestures.composite

import com.example.zepp_gestures.GestureMode

/**
 * One detected gesture inside a Phase 2 attempt's actual sequence.
 *
 * - [stepIndex]: the gesture's position in the actual stream (0-based).
 * - [matchedExpected]: true iff this gesture matches the expected
 *   sequence at this position AND every prior gesture also matched
 *   (i.e. the actual prefix is still a prefix of the expected sequence
 *   up to and including this one). Cascades to false once any earlier
 *   detection went off-script -- see [CompositeTestRunner].
 */
data class DetectedGesture(
    val gestureId: Int,
    val timestamp: Long,
    val stepIndex: Int,
    val matchedExpected: Boolean
)

/**
 * Final result of a single Phase 2 attempt. Serialized to JSON next to
 * the attempt's CSV by [com.example.zepp_gestures.MainActivity].
 */
data class AttemptOutcome(
    val scenarioId: String,
    val scenarioName: String,
    val attemptNumber: Int,
    val expectedGestures: List<Int>,
    val expectedFinalScore: Score,
    val expectedFinalMode: GestureMode,
    val actualGestures: List<DetectedGesture>,
    val actualFinalScore: Score,
    val actualFinalMode: GestureMode,
    val success: Boolean,
    val failureReason: String?,
    val startTimestamp: Long,
    val endTimestamp: Long,
    val durationMs: Long
) {
    fun toJson(): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"scenarioId\": ").appendJsonString(scenarioId).append(",\n")
        sb.append("  \"scenarioName\": ").appendJsonString(scenarioName).append(",\n")
        sb.append("  \"attemptNumber\": ").append(attemptNumber).append(",\n")
        sb.append("  \"expectedGestures\": [")
            .append(expectedGestures.joinToString(", "))
            .append("],\n")
        sb.append("  \"expectedFinalScore\": ").appendJsonScore(expectedFinalScore).append(",\n")
        sb.append("  \"expectedFinalMode\": ").appendJsonString(expectedFinalMode.name).append(",\n")
        sb.append("  \"actualGestures\": [")
        if (actualGestures.isEmpty()) {
            sb.append("],\n")
        } else {
            sb.append("\n")
            actualGestures.forEachIndexed { i, det ->
                sb.append("    { \"gestureId\": ").append(det.gestureId)
                    .append(", \"timestamp\": ").append(det.timestamp)
                    .append(", \"stepIndex\": ").append(det.stepIndex)
                    .append(", \"matchedExpected\": ").append(det.matchedExpected)
                    .append(" }")
                if (i < actualGestures.lastIndex) sb.append(",")
                sb.append("\n")
            }
            sb.append("  ],\n")
        }
        sb.append("  \"actualFinalScore\": ").appendJsonScore(actualFinalScore).append(",\n")
        sb.append("  \"actualFinalMode\": ").appendJsonString(actualFinalMode.name).append(",\n")
        sb.append("  \"success\": ").append(success).append(",\n")
        sb.append("  \"failureReason\": ")
        if (failureReason == null) sb.append("null") else sb.appendJsonString(failureReason)
        sb.append(",\n")
        sb.append("  \"startTimestamp\": ").append(startTimestamp).append(",\n")
        sb.append("  \"endTimestamp\": ").append(endTimestamp).append(",\n")
        sb.append("  \"durationMs\": ").append(durationMs).append("\n")
        sb.append("}\n")
        return sb.toString()
    }
}

private fun StringBuilder.appendJsonString(value: String): StringBuilder {
    append('"')
    for (c in value) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) {
                append("\\u%04x".format(c.code))
            } else {
                append(c)
            }
        }
    }
    append('"')
    return this
}

private fun StringBuilder.appendJsonScore(score: Score): StringBuilder {
    append("{ \"red\": ").append(score.red)
        .append(", \"blue\": ").append(score.blue)
        .append(" }")
    return this
}
