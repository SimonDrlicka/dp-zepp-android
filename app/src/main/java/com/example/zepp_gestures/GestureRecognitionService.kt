package com.example.zepp_gestures

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GestureRecognitionService(
    private val gestureConfig: List<GestureDefinition>,
    private val latestGestureMessage: AtomicReference<String>,
    private val onGestureSegmentReady: (List<ImuSample>) -> Unit = {},
    // When false (prod mode) the passivity timer is fully disabled: no
    // 30 s countdown, no "Passivity ..." match events, and no automatic
    // penalty point. Useful for live matches where the referee handles
    // passivity manually.
    private val passivityTrackingEnabled: Boolean = true,
    // When true (debug mode) the activation gesture "Hand up" (Rise Arm)
    // is logged into the match event list so the diagnostic UI can show
    // exactly when scoring was armed. Prod mode keeps it filtered out --
    // the referee doesn't need pre-scoring noise in the live event log.
    private val logActivationGestures: Boolean = true,
    // Live stream of every newly-appended [MatchEvent], fired from inside
    // the HTTP worker thread *outside* the service's own lock. Default
    // is a no-op so debug/prod modes are unaffected. Composite testing
    // (Phase 2) hooks this to drive its sequence-tracking state machine
    // without having to poll [matchEvents].
    private val onMatchEventEmitted: (MatchEvent) -> Unit = {}
) : ImuIngestor {
    private val allSamples = mutableListOf<ImuSample>()
    private val sessionSamples = LinkedHashSet<ImuSample>()
    private val lastSecondSamples = mutableListOf<ImuSample>()
    private val lastTwoSeconds = mutableListOf<ImuSample>()
    private val captureSamples = mutableListOf<ImuSample>()
    private val matchEvents = mutableListOf<MatchEvent>()
    private val previousActiveEventGestures = mutableSetOf<String>()
    private val currentMode = AtomicReference(GestureMode.WAITING)
    private val bluePoints = AtomicInteger(0)
    private val redPoints = AtomicInteger(0)
    private var pointArmed = true
    private var passivityRedDeadline: Long = 0L
    private var passivityBlueDeadline: Long = 0L
    // Number of points scored within the currently-running scoring gesture
    // (reset on entering scoring, handed off to [vibrationResolver] on exit
    // so the watch can buzz a per-gesture summary).
    private var pointsThisGesture: Int = 0
    // Edge-detection state for prod-mode passivity. In debug the deadline
    // doubles as a de-dupe so we don't need this, but in prod (no deadline)
    // we must remember the previous tick to emit the event/buzz only on
    // the rising edge of each passivity pose.
    private var previousPassivityRedActive: Boolean = false
    private var previousPassivityBlueActive: Boolean = false

    // Encapsulates all vibration-command state and resolution so the
    // gesture pipeline doesn't have to know which tick produces what buzz.
    private val vibrationResolver = VibrationResolver()

    // Per-instance event filter -- in debug mode the activation gestures
    // "Hand up" (Rise Arm) and "Hand back", as well as the deactivation
    // gesture "Hand down", are surfaced in the match events log so the
    // diagnostic UI shows exactly when scoring was armed and disarmed.
    // In prod mode all three stay hidden.
    private val ignoredGestureNames: Set<String> =
        if (logActivationGestures) IGNORED_GESTURE_NAMES - setOf("Hand up", "Hand back", "Hand down")
        else IGNORED_GESTURE_NAMES

    private val lock = Any()

    companion object {
        private const val PASSIVITY_TIMEOUT_MS = 30_000L
        // Rolling buffer length over which we look for a recent gesture
        // hold. Samples older than this are evicted on every ingest.
        private const val BUFFER_DURATION_MS = 2_000L
        // Minimum continuous time (ms) a sample stream must remain inside
        // a gesture's AccelBands to count as a detection. The matched span
        // and everything older than it are then dropped from the buffer
        // (see [detectAndConsume]) so a fresh hold is required for the
        // next match.
        private const val MATCH_DURATION_MS = 300L
        private val EVENT_GESTURE_NAMES = setOf("Touche")
        private val IGNORED_GESTURE_NAMES = setOf(
            "Hand up", "Hand down", "Hand back",
            "Warning red", "Warning blue",
            "Passivity red", "Passivity blue",
            "Flick red", "Flick blue"
        )
    }

    override fun ingest(parsed: List<ImuSample>): IngestResult {
        appendToSessionSamples(parsed)
        updateLastTwoSeconds(parsed)
        val activeGestures = detectAndConsume()
        val modeChange = updateMode(activeGestures)
        updateBuffers(parsed)
        return runScoringPipeline(parsed, activeGestures, modeChange)
    }

    override fun ingestReset(parsed: List<ImuSample>): IngestResult {
        appendToSessionSamples(parsed)
        replaceBuffers(parsed)
        val activeGestures = detectAndConsume()
        val modeChange = updateMode(activeGestures)
        return runScoringPipeline(parsed, activeGestures, modeChange)
    }

    private fun runScoringPipeline(
        parsed: List<ImuSample>,
        activeGestures: List<GestureDefinition>,
        modeChange: Pair<GestureMode, GestureMode>
    ): IngestResult {
        val latestTs = parsed.last().ts
        // Each updateXxx call returns the events it newly appended so we
        // can fire the live callback below, outside any service lock.
        val newPointEvents = updatePoints(parsed, activeGestures, latestTs)
        updateCapture(parsed, modeChange)
        val newMatchEvents = mutableListOf<MatchEvent>()
        val newEventGestures = updateMatchEvents(activeGestures, modeChange, latestTs, newMatchEvents)
        val (passivityStarted, passivityExpired, newPassivityEvents) = updatePassivity(activeGestures, latestTs)
        val message = if (activeGestures.isEmpty()) {
            "No gesture detected"
        } else {
            activeGestures.joinToString(" | ") { it.message }
        }
        latestGestureMessage.set(message)

        val vibration = vibrationResolver.resolve(modeChange, passivityStarted, passivityExpired, newEventGestures)
        // Fire the live event callback for everything newly appended this
        // tick. Order: points first (they happen earliest in the pipeline),
        // then plain match events, then passivity. The callback runs on the
        // HTTP worker thread; subscribers must marshal to the UI thread.
        if (newPointEvents.isNotEmpty()) newPointEvents.forEach(onMatchEventEmitted)
        if (newMatchEvents.isNotEmpty()) newMatchEvents.forEach(onMatchEventEmitted)
        if (newPassivityEvents.isNotEmpty()) newPassivityEvents.forEach(onMatchEventEmitted)
        return synchronized(lock) {
            IngestResult(
                received = parsed.size,
                total = allSamples.size,
                lastSecondCount = lastSecondSamples.size,
                bluePoints = bluePoints.get(),
                redPoints = redPoints.get(),
                message = message,
                vibration = vibration
            )
        }
    }

    fun getMode(): GestureMode = currentMode.get()

    fun getPoints(): Pair<Int, Int> = bluePoints.get() to redPoints.get()

    fun getMatchEvents(): List<MatchEvent> = synchronized(lock) {
        matchEvents.toList()
    }

    fun getPassivityDeadlines(): Pair<Long, Long> = synchronized(lock) {
        passivityRedDeadline to passivityBlueDeadline
    }

    fun getLastSecondSamples(): List<ImuSample> = synchronized(lock) {
        lastSecondSamples.toList()
    }

    fun getAllSamples(): List<ImuSample> = synchronized(lock) {
        allSamples.toList()
    }

    fun getSessionSamples(): List<ImuSample> = synchronized(lock) {
        sessionSamples.toList()
    }

    fun resetPoints() {
        bluePoints.set(0)
        redPoints.set(0)
        synchronized(lock) {
            pointArmed = true
            passivityRedDeadline = 0L
            passivityBlueDeadline = 0L
            pointsThisGesture = 0
            previousPassivityRedActive = false
            previousPassivityBlueActive = false
        }
        vibrationResolver.reset()
    }

    /**
     * Full reset between Phase 2 composite-test attempts: zero score, drop
     * all events, clear sliding windows and capture buffers, return to
     * WAITING. Leaves [sessionSamples] alone so the caller (which exports
     * per-attempt CSVs) can read the slice it cares about and clear it
     * separately via [clearSessionSamples].
     */
    fun resetForNextAttempt() {
        resetPoints()
        synchronized(lock) {
            currentMode.set(GestureMode.WAITING)
            matchEvents.clear()
            previousActiveEventGestures.clear()
            lastTwoSeconds.clear()
            lastSecondSamples.clear()
            captureSamples.clear()
        }
    }

    /**
     * Wipe the per-attempt session buffer. Composite testing calls this
     * after exporting the CSV so the next attempt starts from an empty
     * record.
     */
    fun clearSessionSamples() {
        synchronized(lock) {
            sessionSamples.clear()
            allSamples.clear()
        }
    }

    private fun appendToSessionSamples(newSamples: List<ImuSample>) {
        if (newSamples.isEmpty()) return
        synchronized(lock) {
            sessionSamples.addAll(newSamples)
        }
    }

    private fun updateLastTwoSeconds(newSamples: List<ImuSample>) {
        if (newSamples.isEmpty()) return

        synchronized(lock) {
            lastTwoSeconds.addAll(newSamples)

            val newestTs = lastTwoSeconds.maxOf { it.ts }
            val threshold = newestTs - BUFFER_DURATION_MS

            val it = lastTwoSeconds.iterator()
            while (it.hasNext()) {
                if (it.next().ts < threshold) it.remove()
            }
        }
    }

    /**
     * Look for a continuous ≥ [MATCH_DURATION_MS] in-band run for each
     * configured gesture inside the rolling [BUFFER_DURATION_MS] buffer.
     * If at least one match is found, drop every sample at or before the
     * latest matched-span end timestamp from [lastTwoSeconds] so the next
     * detection requires a fresh hold (and the same span cannot trigger
     * again on the next tick).
     */
    private fun detectAndConsume(): List<GestureDefinition> {
        val active = ArrayList<GestureDefinition>()
        var consumeUntilTs: Long = Long.MIN_VALUE
        var snapshotSize = 0

        synchronized(lock) {
            if (lastTwoSeconds.isEmpty()) return emptyList()
            val snapshot = lastTwoSeconds.toList()
            snapshotSize = snapshot.size

            gestureConfig.forEach { gesture ->
                val endTs = findMatchEndTs(snapshot, gesture) ?: return@forEach
                active.add(gesture)
                if (endTs > consumeUntilTs) consumeUntilTs = endTs
            }

            if (consumeUntilTs > Long.MIN_VALUE) {
                val it = lastTwoSeconds.iterator()
                while (it.hasNext()) {
                    if (it.next().ts <= consumeUntilTs) it.remove() else break
                }
            }
        }

        Log.d(
            "GestureRecognitionService",
            "detectAndConsume: samples=$snapshotSize " +
                    "active=${active.joinToString { it.name }} " +
                    "consumeUntilTs=" +
                    if (consumeUntilTs > Long.MIN_VALUE) consumeUntilTs.toString() else "-"
        )

        return active
    }

    /**
     * Walk [buf] left to right and return the timestamp of the sample at
     * which a continuous in-band run first reaches [MATCH_DURATION_MS]
     * (measured between the run's first and current sample timestamps).
     * Any out-of-band sample resets the run. `null` if no run qualifies.
     */
    private fun findMatchEndTs(buf: List<ImuSample>, gesture: GestureDefinition): Long? {
        var runStart: Long? = null
        for (s in buf) {
            val inBand =
                s.ax in gesture.bands.axMin..gesture.bands.axMax &&
                        s.ay in gesture.bands.ayMin..gesture.bands.ayMax &&
                        s.az in gesture.bands.azMin..gesture.bands.azMax
            if (!inBand) {
                runStart = null
                continue
            }
            if (runStart == null) {
                runStart = s.ts
                continue
            }
            if (s.ts - runStart!! >= MATCH_DURATION_MS) return s.ts
        }
        return null
    }

    private fun updateMode(activeGestures: List<GestureDefinition>): Pair<GestureMode, GestureMode> {
        val previous = currentMode.get()
        if (activeGestures.isEmpty()) return previous to previous

        val hasHandUp = activeGestures.any { it.name == "Hand up" }
        val hasHandBack = activeGestures.any { it.name == "Hand back" }
        val hasHandDown = activeGestures.any { it.name == "Hand down" }
        val hasRedWarning = activeGestures.any { it.name == "Warning red" }
        val hasBlueWarning = activeGestures.any { it.name == "Warning blue" }

        if (previous.isWarning) {
            if (hasHandDown) {
                currentMode.set(GestureMode.WAITING)
            }
        } else {
            when {
                hasHandDown -> currentMode.set(GestureMode.WAITING)
                hasRedWarning -> currentMode.set(GestureMode.WARNING_RED)
                hasBlueWarning -> currentMode.set(GestureMode.WARNING_BLUE)
                hasHandUp -> currentMode.set(GestureMode.GESTURE_RED)
                hasHandBack -> currentMode.set(GestureMode.GESTURE_BLUE)
            }
        }
        return previous to currentMode.get()
    }

    private fun updateCapture(newSamples: List<ImuSample>, modeChange: Pair<GestureMode, GestureMode>) {
        val (previous, current) = modeChange
        var segmentToExport: List<ImuSample>? = null
        var flicksToLatch: Int? = null
        synchronized(lock) {
            if (current.isScoring) {
                if (!previous.isScoring) {
                    captureSamples.clear()
                    pointsThisGesture = 0
                }
                captureSamples.addAll(newSamples)
            } else if (previous.isScoring && current == GestureMode.WAITING) {
                flicksToLatch = pointsThisGesture
                pointsThisGesture = 0
                if (captureSamples.isNotEmpty()) {
                    segmentToExport = captureSamples.toList()
                    captureSamples.clear()
                }
            }
        }
        // Hand off side-effects outside the lock to keep the critical
        // section short and avoid nested-lock acquisition with the
        // resolver's own lock.
        flicksToLatch?.let(vibrationResolver::latchScoringExitFlicks)
        segmentToExport?.let(onGestureSegmentReady)
    }

    private fun updatePoints(
        newSamples: List<ImuSample>,
        activeGestures: List<GestureDefinition>,
        latestTs: Long
    ): List<MatchEvent> {
        val mode = currentMode.get()
        if (!mode.isScoring) return emptyList()
        val emitted = mutableListOf<MatchEvent>()
        synchronized(lock) {
            val gxThreshold = GestureConfig.POINT_GYRO_GX_THRESHOLD * GestureConfig.POINT_GYRO_GX_SCALE
            val label = mode.label
            newSamples.forEach { s ->
                if (pointArmed) {
                    if (s.gx < -gxThreshold) {
                        when (mode) {
                            GestureMode.GESTURE_RED -> {
                                redPoints.incrementAndGet()
                                val ev = MatchEvent(s.ts, "Red point ($label)")
                                matchEvents.add(ev); emitted.add(ev)
                                pointsThisGesture++
                                clearPassivityAndDisarm()
                            }
                            GestureMode.GESTURE_BLUE -> {
                                bluePoints.incrementAndGet()
                                val ev = MatchEvent(s.ts, "Blue point ($label)")
                                matchEvents.add(ev); emitted.add(ev)
                                pointsThisGesture++
                                clearPassivityAndDisarm()
                            }
                            GestureMode.WARNING_RED -> {
                                bluePoints.incrementAndGet()
                                val ev = MatchEvent(s.ts, "Blue point ($label)")
                                matchEvents.add(ev); emitted.add(ev)
                                pointsThisGesture++
                                clearPassivityAndDisarm()
                            }
                            GestureMode.WARNING_BLUE -> {
                                redPoints.incrementAndGet()
                                val ev = MatchEvent(s.ts, "Red point ($label)")
                                matchEvents.add(ev); emitted.add(ev)
                                pointsThisGesture++
                                clearPassivityAndDisarm()
                            }
                            else -> { /* unreachable: guarded by mode.isScoring */ }
                        }
                    }
                } else if (s.gx >= -gxThreshold) {
                    pointArmed = true
                }
            }
        }
        return emitted
    }

    private fun clearPassivityAndDisarm() {
        passivityRedDeadline = 0L
        passivityBlueDeadline = 0L
        pointArmed = false
    }

    private fun updateMatchEvents(
        activeGestures: List<GestureDefinition>,
        modeChange: Pair<GestureMode, GestureMode>,
        latestTs: Long,
        emitted: MutableList<MatchEvent>
    ): Set<String> {
        val (previous, current) = modeChange
        synchronized(lock) {
            // Log warning mode transitions
            if (!previous.isWarning && current == GestureMode.WARNING_RED) {
                val ev = MatchEvent(latestTs, "Warning red")
                matchEvents.add(ev); emitted.add(ev)
            }
            if (!previous.isWarning && current == GestureMode.WARNING_BLUE) {
                val ev = MatchEvent(latestTs, "Warning blue")
                matchEvents.add(ev); emitted.add(ev)
            }

            // Log detected gestures with deduplication (skip hand_up/hand_down)
            val currentEventNames = activeGestures
                .map { it.name }
                .toSet()

            val newlyFired = mutableSetOf<String>()
            for (name in currentEventNames) {
                if (name in previousActiveEventGestures) continue
                if (name in ignoredGestureNames) continue
                // Hand down is only meaningful when it actually exits a
                // scoring/warning mode -- otherwise the wrist resting in
                // the "down" pose would spam the log while already in
                // WAITING. Skip the entry unless this tick produced the
                // transition out of a non-WAITING state.
                if (name == "Hand down" && previous == GestureMode.WAITING) continue
                val ev = MatchEvent(latestTs, name)
                matchEvents.add(ev); emitted.add(ev)
                if (name in EVENT_GESTURE_NAMES) {
                    newlyFired.add(name)
                }
            }
            previousActiveEventGestures.clear()
            previousActiveEventGestures.addAll(currentEventNames)
            return newlyFired
        }
    }

    private data class PassivityResult(
        val started: Boolean,
        val expired: Boolean,
        val emitted: List<MatchEvent>
    )

    private fun updatePassivity(activeGestures: List<GestureDefinition>, latestTs: Long): PassivityResult {
        synchronized(lock) {
            var started = false
            var expired = false
            val emitted = mutableListOf<MatchEvent>()

            val mode = currentMode.get()
            val hasPassivityRed = activeGestures.any { it.name == "Passivity red" }
            val hasPassivityBlue = activeGestures.any { it.name == "Passivity blue" }

            if (passivityTrackingEnabled) {
                // Debug mode: original deadline-based timer + penalty.
                // The deadline doubles as a de-dupe -- the event/buzz fire
                // once when the timer starts; subsequent ticks find
                // anyPassivityActive=true and skip.
                val anyPassivityActive = passivityRedDeadline > 0 || passivityBlueDeadline > 0
                if (mode == GestureMode.WAITING && !anyPassivityActive) {
                    if (hasPassivityRed) {
                        passivityRedDeadline = latestTs + PASSIVITY_TIMEOUT_MS
                        val ev = MatchEvent(latestTs, "Passivity red")
                        matchEvents.add(ev); emitted.add(ev)
                        started = true
                    } else if (hasPassivityBlue) {
                        passivityBlueDeadline = latestTs + PASSIVITY_TIMEOUT_MS
                        val ev = MatchEvent(latestTs, "Passivity blue")
                        matchEvents.add(ev); emitted.add(ev)
                        started = true
                    }
                }

                if (passivityRedDeadline in 1..latestTs) {
                    bluePoints.incrementAndGet()
                    val ev = MatchEvent(latestTs, "Passivity red penalty (blue +1)")
                    matchEvents.add(ev); emitted.add(ev)
                    passivityRedDeadline = 0L
                    expired = true
                }
                if (passivityBlueDeadline in 1..latestTs) {
                    redPoints.incrementAndGet()
                    val ev = MatchEvent(latestTs, "Passivity blue penalty (red +1)")
                    matchEvents.add(ev); emitted.add(ev)
                    passivityBlueDeadline = 0L
                    expired = true
                }
            } else {
                // Prod mode: detection still fires the event + buzz once per
                // appearance, but there is no 30 s timer and no automatic
                // penalty point. Edge-detect on the previous-tick state so
                // we only emit on the rising edge of each passivity pose.
                if (passivityRedDeadline != 0L || passivityBlueDeadline != 0L) {
                    passivityRedDeadline = 0L
                    passivityBlueDeadline = 0L
                }
                val redEdgeStart = hasPassivityRed && !previousPassivityRedActive
                val blueEdgeStart = hasPassivityBlue && !previousPassivityBlueActive
                previousPassivityRedActive = hasPassivityRed
                previousPassivityBlueActive = hasPassivityBlue

                if (mode == GestureMode.WAITING) {
                    if (redEdgeStart) {
                        val ev = MatchEvent(latestTs, "Passivity red")
                        matchEvents.add(ev); emitted.add(ev)
                        started = true
                    } else if (blueEdgeStart) {
                        val ev = MatchEvent(latestTs, "Passivity blue")
                        matchEvents.add(ev); emitted.add(ev)
                        started = true
                    }
                }
            }

            return PassivityResult(started, expired, emitted)
        }
    }

    private fun updateBuffers(newSamples: List<ImuSample>) {
        if (newSamples.isEmpty()) return

        synchronized(lock) {
            // 1️⃣ append to full history
            allSamples.addAll(newSamples)

            // 2️⃣ update last-second window
            lastSecondSamples.addAll(newSamples)

            val newestTs = lastSecondSamples.maxOf { it.ts }
            val threshold = newestTs - 1000  // last 1 second

            // remove everything older than 1 second
            val it = lastSecondSamples.iterator()
            while (it.hasNext()) {
                if (it.next().ts < threshold) {
                    it.remove()
                }
            }
        }
    }

    private fun replaceBuffers(allData: List<ImuSample>) {
        if (allData.isEmpty()) return

        synchronized(lock) {
            allSamples.clear()
            lastSecondSamples.clear()
            lastTwoSeconds.clear()
            captureSamples.clear()

            allSamples.addAll(allData)

            val newestTs = allData.maxOf { it.ts }
            val lastSecondThreshold = newestTs - 1000L
            val bufferDurationThreshold = newestTs - BUFFER_DURATION_MS

            allData.forEach { s ->
                if (s.ts >= lastSecondThreshold) {
                    lastSecondSamples.add(s)
                }
                if (s.ts >= bufferDurationThreshold) {
                    lastTwoSeconds.add(s)
                }
            }
        }
    }
}
