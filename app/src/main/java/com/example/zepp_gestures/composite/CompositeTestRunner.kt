package com.example.zepp_gestures.composite

import com.example.zepp_gestures.GestureMode
import com.example.zepp_gestures.MatchEvent

/**
 * State machine for a single Phase 2 attempt.
 *
 * Lifecycle:
 *   1. Construct with the [scenario] + [attemptNumber].
 *   2. Call [start] when the user begins the attempt -- locks in the
 *      reference timestamp for the inactivity timeout.
 *   3. Feed every newly-emitted [MatchEvent] from
 *      [com.example.zepp_gestures.GestureRecognitionService] via
 *      [onMatchEvent]. The runner records it in [actualGestures] (with
 *      its mapped composite gesture ID), advances the expected-step
 *      cursor when it matches, and exposes status via the read-only
 *      accessors.
 *   4. The fragment also calls [onTick] periodically with the latest
 *      sample timestamp to detect the inactivity timeout.
 *   5. When the runner's [terminationReason] becomes non-null (or the
 *      fragment force-stops via [terminate]), the fragment hands off the
 *      attempt's CSV samples + final score/mode and calls
 *      [buildOutcome] to render the JSON metadata.
 *
 * Match semantics for [DetectedGesture.matchedExpected] follow the spec:
 * once any prior detection mismatched the expected position, every
 * subsequent detection is also marked `false` (cascading), even if its
 * position happens to align with the expected sequence again.
 *
 * NOT thread-safe. The fragment marshals every call onto the main thread.
 */
class CompositeTestRunner(
    val scenario: CompositeScenario,
    val attemptNumber: Int
) {

    companion object {
        const val INACTIVITY_TIMEOUT_MS: Long = 10_000L
        // Small grace period after the final expected gesture is matched,
        // so the gesture-recognition service has time to fully process
        // hand_down -> WAITING + score increments before we snapshot.
        const val SUCCESS_TAIL_MS: Long = 500L
    }

    enum class TerminationReason { COMPLETED_SEQUENCE, TIMEOUT, MANUAL }

    private val _actualGestures: MutableList<DetectedGesture> = mutableListOf()
    val actualGestures: List<DetectedGesture> get() = _actualGestures

    var startTimestamp: Long = 0L
        private set
    var endTimestamp: Long = 0L
        private set

    /** Cursor into [scenario.expectedGestureIds]; advances on a successful match. */
    var nextExpectedIndex: Int = 0
        private set

    /** Flips to false on the first mismatch and stays false (cascading flag). */
    private var stillOnTrack: Boolean = true

    /** Timestamp of the last accepted detection, or [startTimestamp] until something fires. */
    private var lastEventTs: Long = 0L

    /** Wall-clock-ish ts (taken from sample stream) at which the success grace window closes. */
    private var sequenceCompletedAt: Long? = null

    var terminationReason: TerminationReason? = null
        private set

    /** Set on each [onMatchEvent] call; consumed by the fragment to surface mismatch UI. */
    var lastDetectionWasMismatch: Boolean = false
        private set

    /** Snapshot of the gesture ID + name for the most recent detection. */
    var lastDetectedId: Int? = null
        private set

    fun start(referenceTs: Long) {
        startTimestamp = referenceTs
        lastEventTs = referenceTs
    }

    /**
     * Feed one match event. Returns the [DetectedGesture] just appended,
     * or `null` if the event mapped to no composite gesture (e.g.
     * "Passivity red penalty" entries are not part of the catalog).
     */
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
            // Off-script: stop crediting future matches, even if they
            // happen to align positionally.
            stillOnTrack = false
        }

        return detected
    }

    /**
     * Periodic tick from the fragment's poll loop. Pass the most recent
     * sample timestamp seen on the wire; the runner uses it to evaluate
     * the success grace window and the inactivity timeout. Returns true
     * iff the runner just transitioned to a terminal state.
     *
     * No-op until [start] has been called -- callers should defer the
     * very first tick until at least one sample / event has arrived so
     * the timeout uses the same clock domain as the stream.
     */
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

    /**
     * Build the final outcome record. Caller supplies the actual
     * end-of-attempt score + mode it observed on the gesture service.
     */
    fun buildOutcome(actualFinalScore: Score, actualFinalMode: GestureMode): AttemptOutcome {
        val expectedScore = scenario.expectedFinalScore
        val sequenceMatchesFully =
            stillOnTrack && nextExpectedIndex == scenario.expectedGestureIds.size
        // Scenario H (TOUCHE) has an irrelevant expected score; treat
        // sequence-completion alone as success.
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
        // Two failure shapes: "wrong gesture at step N" or "missing tail".
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
        // We didn't get enough gestures.
        val missing = scenario.expectedGestureIds.drop(_actualGestures.size)
        val missingDesc = missing.joinToString(", ") { "${compositeGestureName(it)} ($it)" }
        return "Missing gestures: $missingDesc"
    }
}

/**
 * Map a [MatchEvent.event] string emitted by the gesture-recognition
 * pipeline to its composite gesture ID, or `null` if the event isn't
 * part of the Phase 2 catalog (e.g. "Passivity red penalty (...)").
 *
 * Note: "Red point ..." / "Blue point ..." entries are emitted whenever
 * scoring increments -- they correspond to a Flick (id 9) regardless of
 * which colour ultimately got the point. The mode-specific mapping
 * (Warning_red flick -> blue point) is handled by the gesture service
 * itself; for sequence-matching purposes we only care that "a flick
 * happened".
 */
fun mapEventNameToCompositeId(eventName: String): Int? = when {
    eventName == "Hand up" -> 1
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
