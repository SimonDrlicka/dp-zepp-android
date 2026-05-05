package com.example.zepp_gestures

/**
 * Backend pipeline used in Testing mode (Phase 1 -- isolated gestures).
 *
 * Workflow per attempt:
 *  1. Buffer all incoming samples in [activeBuffer].
 *  2. On every tick, decide whether *any* of the eight catalog gestures
 *     ([TestingGestures.ALL]) is currently active using the same 90 %
 *     band-match heuristic as [GestureRecognitionService].
 *  3. The first match (in catalog order) wins -- the iteration is
 *     anchored on the first gesture recognised, regardless of whether
 *     it's the one the user expected. This is intentional: the saved
 *     CSV will then show the false-positive case and the analyst can
 *     score the test accordingly.
 *  4. After detection we keep recording for [TAIL_CAPTURE_MS] more ms,
 *     then hand the buffer to [onAttemptCompleted] which writes the CSV.
 *  5. A short cooldown ([INTER_ATTEMPT_PAUSE_MS]) is enforced before
 *     the next attempt starts -- samples arriving during the cooldown
 *     are discarded so the next attempt's CSV starts clean.
 *  6. When [attemptCount] is reached, [onTestFinished] fires and any
 *     further ingest calls become no-ops.
 *
 * [skipCurrentAttempt] lets the user "complete" an attempt even though
 * the framework didn't recognise their gesture: the next ingest tick
 * promotes the latest sample to a synthetic detection, the tail still
 * gets captured, and the saved CSV's `detected` column is all `-`.
 *
 * All callbacks are invoked synchronously from inside [ingest] /
 * [ingestReset], i.e. on the HTTP worker thread. Callers that need to
 * touch the UI must marshal to the main thread themselves.
 */
class TestingService(
    private val attemptCount: Int,
    private val onAttemptCompleted: (
        samples: List<ImuSample>,
        attemptIndex: Int,
        detectedAtTs: Long?,
        detectedGesture: TestingGesture?
    ) -> Unit,
    private val onTestFinished: () -> Unit,
    private val onProgressChanged: (
        attemptsCompleted: Int,
        totalAttempts: Int,
        capturingTail: Boolean,
        paused: Boolean
    ) -> Unit = { _, _, _, _ -> }
) : ImuIngestor {

    companion object {
        private const val TAIL_CAPTURE_MS = 1_000L
        private const val INTER_ATTEMPT_PAUSE_MS = 1_000L
        private const val GESTURE_WINDOW_MS = 300L
        private const val GESTURE_MATCH_RATIO = 0.9
    }

    private val lock = Any()

    // Samples for the current attempt -- everything since the last reset.
    private val activeBuffer = mutableListOf<ImuSample>()

    // Sliding window used for band-match decisions. Mirrors the
    // GestureRecognitionService.lastHalfSecond logic.
    private val lastHalfSecond = mutableListOf<ImuSample>()

    // Set when any catalog gesture was recognised this attempt; null
    // otherwise. While set, we're recording the post-detection tail.
    private var detectedAtTs: Long? = null
    private var detectedGesture: TestingGesture? = null

    // Skip flow -- see [skipCurrentAttempt].
    private var skipPending: Boolean = false
    private var currentAttemptSkipped: Boolean = false

    // Cooldown gate. While [pauseUntilTs] is non-null and the latest
    // sample timestamp is below it, ingested samples are dropped.
    private var pauseUntilTs: Long? = null

    private var attemptsCompleted: Int = 0
    private var stopped: Boolean = false

    // Bands look-up for each catalog gesture, resolved once at construction
    // so the per-tick detection loop is a tight band-match.
    private val catalogWithBands: List<Pair<TestingGesture, AccelBands>> =
        TestingGestures.ALL.mapNotNull { tg ->
            GestureConfig.gestures.firstOrNull { it.name == tg.internalName }
                ?.let { def -> tg to def.bands }
        }

    fun stop() {
        synchronized(lock) { stopped = true }
    }

    fun isStopped(): Boolean = synchronized(lock) { stopped }

    /**
     * Mark the current attempt as "user-confirmed but not detected".
     * No-op while already in tail-capture / cooldown / stopped.
     */
    fun skipCurrentAttempt() {
        synchronized(lock) {
            if (stopped) return
            if (detectedAtTs != null) return
            if (pauseUntilTs != null) return
            skipPending = true
        }
    }

    override fun ingest(parsed: List<ImuSample>): IngestResult =
        ingestInternal(parsed, reset = false)

    override fun ingestReset(parsed: List<ImuSample>): IngestResult =
        ingestInternal(parsed, reset = true)

    private fun ingestInternal(parsed: List<ImuSample>, reset: Boolean): IngestResult {
        if (parsed.isEmpty()) return emptyResult("Empty payload")

        var attemptToReport: List<ImuSample>? = null
        var attemptIndexToReport: Int = 0
        var attemptDetectionTsToReport: Long? = null
        var attemptDetectedGesture: TestingGesture? = null
        var testFinished = false
        var progressTotal = 0
        var progressCapturing = false
        var progressPaused = false

        synchronized(lock) {
            if (stopped) return emptyResult("Test stopped")

            if (reset) {
                activeBuffer.clear()
                lastHalfSecond.clear()
                detectedAtTs = null
                detectedGesture = null
                skipPending = false
                currentAttemptSkipped = false
                pauseUntilTs = null
            }

            val latestTs = parsed.last().ts

            // Inter-attempt cooldown: drop samples until the pause window
            // elapses. We do not buffer them -- the next attempt should
            // start with a clean slate.
            val pauseEnds = pauseUntilTs
            if (pauseEnds != null) {
                if (latestTs < pauseEnds) {
                    progressTotal = attemptsCompleted
                    progressPaused = true
                    return@synchronized
                } else {
                    pauseUntilTs = null
                }
            }

            activeBuffer.addAll(parsed)

            // Update the sliding "last 300 ms" window for band matching.
            lastHalfSecond.addAll(parsed)
            val newest = lastHalfSecond.maxOf { it.ts }
            val cutoff = newest - GESTURE_WINDOW_MS
            val it = lastHalfSecond.iterator()
            while (it.hasNext()) {
                if (it.next().ts < cutoff) it.remove()
            }

            val detectedTs = detectedAtTs

            if (detectedTs != null) {
                if (latestTs - detectedTs >= TAIL_CAPTURE_MS) {
                    attemptToReport = activeBuffer.toList()
                    attemptsCompleted++
                    attemptIndexToReport = attemptsCompleted
                    attemptDetectionTsToReport =
                        if (currentAttemptSkipped) null else detectedTs
                    attemptDetectedGesture =
                        if (currentAttemptSkipped) null else detectedGesture

                    // Reset for the next attempt and start the cooldown.
                    activeBuffer.clear()
                    lastHalfSecond.clear()
                    detectedAtTs = null
                    detectedGesture = null
                    currentAttemptSkipped = false
                    skipPending = false

                    if (attemptsCompleted >= attemptCount) {
                        stopped = true
                        testFinished = true
                    } else {
                        pauseUntilTs = latestTs + INTER_ATTEMPT_PAUSE_MS
                    }
                }
            } else {
                // Real match wins over a pending skip on the same tick.
                val match = firstMatchingGesture()
                if (match != null) {
                    detectedAtTs = latestTs
                    detectedGesture = match
                    currentAttemptSkipped = false
                    skipPending = false
                } else if (skipPending) {
                    detectedAtTs = latestTs
                    detectedGesture = null
                    currentAttemptSkipped = true
                    skipPending = false
                }
            }

            progressTotal = attemptsCompleted
            progressCapturing = detectedAtTs != null
            progressPaused = pauseUntilTs != null
        }

        attemptToReport?.let { samples ->
            onAttemptCompleted(
                samples,
                attemptIndexToReport,
                attemptDetectionTsToReport,
                attemptDetectedGesture
            )
        }
        if (testFinished) {
            onTestFinished()
        }
        onProgressChanged(progressTotal, attemptCount, progressCapturing, progressPaused)

        val msg = when {
            testFinished -> "Test complete: $progressTotal / $attemptCount"
            progressCapturing -> "Capturing tail (${progressTotal} / $attemptCount done)"
            progressPaused -> "Pause (${progressTotal} / $attemptCount done)"
            else -> "Waiting for any gesture ($progressTotal / $attemptCount done)"
        }
        return IngestResult(
            received = parsed.size,
            total = activeBuffer.size,
            lastSecondCount = lastHalfSecond.size,
            bluePoints = 0,
            redPoints = 0,
            message = msg,
            vibration = VibrationCommand(0, "short")
        )
    }

    /**
     * Walk the catalog in [TestingGestures.ALL] order and return the first
     * gesture whose AccelBands match at least 90 % of the half-second
     * window samples. `null` if none match.
     */
    private fun firstMatchingGesture(): TestingGesture? {
        val snapshot = lastHalfSecond
        if (snapshot.isEmpty()) return null
        val total = snapshot.size.toDouble()
        for ((tg, bands) in catalogWithBands) {
            var inCount = 0
            for (s in snapshot) {
                val ok = s.ax in bands.axMin..bands.axMax &&
                    s.ay in bands.ayMin..bands.ayMax &&
                    s.az in bands.azMin..bands.azMax
                if (ok) inCount++
            }
            if (inCount.toDouble() / total >= GESTURE_MATCH_RATIO) {
                return tg
            }
        }
        return null
    }

    private fun emptyResult(message: String) = IngestResult(
        received = 0,
        total = 0,
        lastSecondCount = 0,
        bluePoints = 0,
        redPoints = 0,
        message = message,
        vibration = VibrationCommand(0, "short")
    )
}
