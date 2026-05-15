package com.example.zepp_gestures

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

        private const val LIVE_WINDOW_MS = 20_000L
    }

    private val lock = Any()

    private val activeBuffer = mutableListOf<ImuSample>()

    private val liveSamplesWindow = mutableListOf<ImuSample>()

    private val lastTwoSeconds = mutableListOf<ImuSample>()

    private var detectedAtTs: Long? = null
    private var detectedGesture: TestingGesture? = null

    private var skipPending: Boolean = false
    private var currentAttemptSkipped: Boolean = false

    private var pauseUntilTs: Long? = null

    private var attemptsCompleted: Int = 0
    private var stopped: Boolean = false

    private val catalogWithBands: List<Pair<TestingGesture, AccelBands>> =
        TestingGestures.ALL.mapNotNull { tg ->
            GestureConfig.gestures.firstOrNull { it.name == tg.internalName }
                ?.let { def -> tg to def.bands }
        }

    fun stop() {
        synchronized(lock) { stopped = true }
    }

    fun getLiveSamples(): List<ImuSample> = synchronized(lock) {
        liveSamplesWindow.toList()
    }

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
                lastTwoSeconds.clear()
                detectedAtTs = null
                detectedGesture = null
                skipPending = false
                currentAttemptSkipped = false
                pauseUntilTs = null
            }

            val latestTs = parsed.last().ts

            liveSamplesWindow.addAll(parsed)
            val liveCutoff = latestTs - LIVE_WINDOW_MS
            val liveIt = liveSamplesWindow.iterator()
            while (liveIt.hasNext()) {
                if (liveIt.next().ts < liveCutoff) liveIt.remove()
            }

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

            lastTwoSeconds.addAll(parsed)
            val newest = lastTwoSeconds.maxOf { it.ts }
            val cutoff = newest - GestureConfig.BUFFER_DURATION_MS
            val it = lastTwoSeconds.iterator()
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

                    activeBuffer.clear()
                    lastTwoSeconds.clear()
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
            lastSecondCount = lastTwoSeconds.size,
            bluePoints = 0,
            redPoints = 0,
            message = msg,
            vibration = VibrationCommand(0, "short")
        )
    }

    private fun firstMatchingGesture(): TestingGesture? {
        if (lastTwoSeconds.isEmpty()) return null
        for ((tg, bands) in catalogWithBands) {
            if (findMatchEndTs(lastTwoSeconds, bands) != null) return tg
        }
        return null
    }

    private fun findMatchEndTs(buf: List<ImuSample>, bands: AccelBands): Long? {
        var runStart: Long? = null
        for (s in buf) {
            val inBand = s.ax in bands.axMin..bands.axMax &&
                s.ay in bands.ayMin..bands.ayMax &&
                s.az in bands.azMin..bands.azMax
            if (!inBand) {
                runStart = null
                continue
            }
            if (runStart == null) {
                runStart = s.ts
                continue
            }
            if (s.ts - runStart!! >= GestureConfig.MATCH_DURATION_MS) return s.ts
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
