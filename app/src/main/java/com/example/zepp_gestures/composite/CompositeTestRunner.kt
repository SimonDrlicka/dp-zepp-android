package com.example.zepp_gestures.composite

import com.example.zepp_gestures.GestureMode
import com.example.zepp_gestures.MatchEvent

class CompositeTestRunner(
    val scenario: CompositeScenario,
    val attemptNumber: Int
) {

    companion object {
        const val INACTIVITY_TIMEOUT_MS: Long = 10_000L

        const val SUCCESS_TAIL_MS: Long = 500L
    }

    enum class TerminationReason { COMPLETED_SEQUENCE, TIMEOUT, MANUAL }

    private val _actualGestures: MutableList<DetectedGesture> = mutableListOf()
    val actualGestures: List<DetectedGesture> get() = _actualGestures

    var startTimestamp: Long = 0L
        private set
    var endTimestamp: Long = 0L
        private set

    var nextExpectedIndex: Int = 0
        private set

    private var stillOnTrack: Boolean = true

    private var lastEventTs: Long = 0L

    private var sequenceCompletedAt: Long? = null

    var terminationReason: TerminationReason? = null
        private set

    var lastDetectionWasMismatch: Boolean = false
        private set

    var lastDetectedId: Int? = null
        private set

    fun start(referenceTs: Long) {
        startTimestamp = referenceTs
        lastEventTs = referenceTs
    }

    fun onMatchEvent(event: MatchEvent): DetectedGesture? {
        if (terminationReason != null) return null
        val gestureId = mapEventNameToCompositeId(event.event) ?: return null

        val expectedHere =
            if (nextExpectedIndex < scenario.expectedGestureIds.size)
                scenario.expectedGestureIds[nextExpectedIndex]
            else null

        val matchesPosition = expectedHere != null && expectedHere == gestureId
        val matched = stillOnTrack && matchesPosition

        val detected = DetectedGesture(
            gestureId = gestureId,
            timestamp = event.ts,
            stepIndex = _actualGestures.size,
            matchedExpected = matched
        )
        _actualGestures.add(detected)

        lastEventTs = event.ts
        lastDetectedId = gestureId
        lastDetectionWasMismatch = !matchesPosition

        if (matched) {
            nextExpectedIndex++
            if (nextExpectedIndex >= scenario.expectedGestureIds.size) {
                sequenceCompletedAt = event.ts + SUCCESS_TAIL_MS
            }
        } else {

            stillOnTrack = false
        }

        return detected
    }

    fun onTick(latestSampleTs: Long): Boolean {
        if (terminationReason != null) return false
        if (startTimestamp == 0L) return false
        endTimestamp = latestSampleTs

        sequenceCompletedAt?.let { graceEnd ->
            if (latestSampleTs >= graceEnd) {
                terminationReason = TerminationReason.COMPLETED_SEQUENCE
                return true
            }
        }
        if (latestSampleTs - lastEventTs >= INACTIVITY_TIMEOUT_MS) {
            terminationReason = TerminationReason.TIMEOUT
            return true
        }
        return false
    }

    fun terminate(reason: TerminationReason = TerminationReason.MANUAL, ts: Long) {
        if (terminationReason != null) return
        terminationReason = reason
        endTimestamp = ts
    }

    fun buildOutcome(actualFinalScore: Score, actualFinalMode: GestureMode): AttemptOutcome {
        val expectedScore = scenario.expectedFinalScore
        val sequenceMatchesFully =
            stillOnTrack && nextExpectedIndex == scenario.expectedGestureIds.size

        val ignoreScore = scenario.expectedGestureIds == listOf(8)
        val scoreMatches = ignoreScore || actualFinalScore == expectedScore
        val modeMatches = actualFinalMode == scenario.expectedFinalMode
        val success = sequenceMatchesFully && scoreMatches && modeMatches

        val failureReason = when {
            success -> null
            !sequenceMatchesFully -> firstSequenceFailureMessage()
            !modeMatches -> "Final mode mismatch: expected ${scenario.expectedFinalMode.name}, got ${actualFinalMode.name}"
            !scoreMatches -> "Final score mismatch: expected red=${expectedScore.red} blue=${expectedScore.blue}, " +
                "got red=${actualFinalScore.red} blue=${actualFinalScore.blue}"
            else -> "Unknown failure"
        }

        return AttemptOutcome(
            scenarioId = scenario.id,
            scenarioName = scenario.name,
            attemptNumber = attemptNumber,
            expectedGestures = scenario.expectedGestureIds,
            expectedFinalScore = expectedScore,
            expectedFinalMode = scenario.expectedFinalMode,
            actualGestures = _actualGestures.toList(),
            actualFinalScore = actualFinalScore,
            actualFinalMode = actualFinalMode,
            success = success,
            failureReason = failureReason,
            startTimestamp = startTimestamp,
            endTimestamp = endTimestamp,
            durationMs = (endTimestamp - startTimestamp).coerceAtLeast(0L)
        )
    }

    private fun firstSequenceFailureMessage(): String {

        for ((i, det) in _actualGestures.withIndex()) {
            if (i >= scenario.expectedGestureIds.size) {
                return "Extra gesture at step $i: got ${compositeGestureName(det.gestureId)} (${det.gestureId})"
            }
            val expected = scenario.expectedGestureIds[i]
            if (expected != det.gestureId) {
                return "Wrong gesture at step $i: expected ${compositeGestureName(expected)} ($expected), " +
                    "got ${compositeGestureName(det.gestureId)} (${det.gestureId})"
            }
        }

        val missing = scenario.expectedGestureIds.drop(_actualGestures.size)
        val missingDesc = missing.joinToString(", ") { "${compositeGestureName(it)} ($it)" }
        return "Missing gestures: $missingDesc"
    }
}

fun mapEventNameToCompositeId(eventName: String): Int? = when {
    eventName == "Rise Arm" -> 1
    eventName == "Hand back" -> 2
    eventName == "Hand down" -> 3
    eventName == "Passivity red" -> 4
    eventName == "Passivity blue" -> 5
    eventName == "Warning red" -> 6
    eventName == "Warning blue" -> 7
    eventName == "Touche" -> 8
    eventName.startsWith("Red point") -> 9
    eventName.startsWith("Blue point") -> 9
    else -> null
}
