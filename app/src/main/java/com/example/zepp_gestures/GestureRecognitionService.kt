package com.example.zepp_gestures

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GestureRecognitionService(
    private val gestureConfig: List<GestureDefinition>,
    private val latestGestureMessage: AtomicReference<String>,
    private val onGestureSegmentReady: (List<ImuSample>) -> Unit = {},

    private val passivityTrackingEnabled: Boolean = true,

    private val logActivationGestures: Boolean = true,

    private val onMatchEventEmitted: (MatchEvent) -> Unit = {},

    private val vibrateOnScoringArmed: Boolean = true
) : ImuIngestor {
    private val allSamples = mutableListOf<ImuSample>()
    private val sessionSamples = LinkedHashSet<ImuSample>()
    private val lastSecondSamples = mutableListOf<ImuSample>()
    private val lastTwoSeconds = mutableListOf<ImuSample>()

    private val scoringLookback = mutableListOf<ImuSample>()
    private val captureSamples = mutableListOf<ImuSample>()
    private val matchEvents = mutableListOf<MatchEvent>()
    private val previousActiveEventGestures = mutableSetOf<String>()
    private val currentMode = AtomicReference(GestureMode.WAITING)
    private val bluePoints = AtomicInteger(0)
    private val redPoints = AtomicInteger(0)
    private var pointArmed = true
    private var passivityRedDeadline: Long = 0L
    private var passivityBlueDeadline: Long = 0L

    private var pointsThisGesture: Int = 0

    private var previousPassivityRedActive: Boolean = false
    private var previousPassivityBlueActive: Boolean = false

    private val vibrationResolver = VibrationResolver(vibrateOnScoringArmed)

    private val ignoredGestureNames: Set<String> =
        if (logActivationGestures) IGNORED_GESTURE_NAMES - setOf("Hand up", "Hand back", "Hand down")
        else IGNORED_GESTURE_NAMES

    private val lock = Any()

    companion object {
        private const val PASSIVITY_TIMEOUT_MS = 30_000L
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

        val newPointEvents = updatePoints(parsed, activeGestures, latestTs, modeChange)
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

    fun deleteMatchEvent(target: MatchEvent): Boolean {
        val invalidated = synchronized(lock) {
            val idx = matchEvents.indexOfFirst {
                it.ts == target.ts && it.event == target.event && !it.invalidated
            }
            if (idx < 0) return@synchronized false
            matchEvents[idx] = matchEvents[idx].copy(invalidated = true)
            true
        }
        if (!invalidated) return false
        when {
            target.event.startsWith("Red point") ->
                redPoints.updateAndGet { (it - 1).coerceAtLeast(0) }
            target.event.startsWith("Blue point") ->
                bluePoints.updateAndGet { (it - 1).coerceAtLeast(0) }
            target.event.startsWith("Passivity red penalty") ->
                bluePoints.updateAndGet { (it - 1).coerceAtLeast(0) }
            target.event.startsWith("Passivity blue penalty") ->
                redPoints.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
        return true
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

    fun resetForNextAttempt() {
        bluePoints.set(0)
        redPoints.set(0)
        synchronized(lock) {
            pointArmed = true
            passivityRedDeadline = 0L
            passivityBlueDeadline = 0L
            pointsThisGesture = 0
            previousPassivityRedActive = false
            previousPassivityBlueActive = false
            currentMode.set(GestureMode.WAITING)
            matchEvents.clear()
            previousActiveEventGestures.clear()
            lastTwoSeconds.clear()
            lastSecondSamples.clear()
            scoringLookback.clear()
            captureSamples.clear()
        }
        vibrationResolver.reset()
    }

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
            val threshold = newestTs - GestureConfig.BUFFER_DURATION_MS

            val it = lastTwoSeconds.iterator()
            while (it.hasNext()) {
                if (it.next().ts < threshold) it.remove()
            }
        }
    }

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
            if (s.ts - runStart!! >= GestureConfig.MATCH_DURATION_MS) return s.ts
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

        flicksToLatch?.let(vibrationResolver::latchScoringExitFlicks)
        segmentToExport?.let(onGestureSegmentReady)
    }

    private fun updatePoints(
        newSamples: List<ImuSample>,
        activeGestures: List<GestureDefinition>,
        latestTs: Long,
        modeChange: Pair<GestureMode, GestureMode>
    ): List<MatchEvent> {
        val emitted = mutableListOf<MatchEvent>()
        val mode = currentMode.get()
        val (previous, _) = modeChange

        synchronized(lock) {

            appendToScoringLookback(newSamples)

            if (!mode.isScoring) return@synchronized

            val samplesToScore: List<ImuSample> = if (!previous.isScoring) {
                val firstParsedTs = newSamples.firstOrNull()?.ts ?: Long.MAX_VALUE
                scoringLookback.filter { it.ts < firstParsedTs } + newSamples
            } else {
                newSamples
            }

            val gxThreshold =
                GestureConfig.POINT_GYRO_GX_THRESHOLD * GestureConfig.POINT_GYRO_GX_SCALE
            val label = mode.label
            samplesToScore.forEach { s ->
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
                            else -> {  }
                        }
                    }
                } else if (s.gx >= -gxThreshold) {
                    pointArmed = true
                }
            }
        }
        return emitted
    }

    private fun appendToScoringLookback(newSamples: List<ImuSample>) {
        if (newSamples.isEmpty()) return
        scoringLookback.addAll(newSamples)
        val newestTs = scoringLookback.maxOf { it.ts }
        val cutoff = newestTs - GestureConfig.SCORING_LOOKBACK_MS
        val it = scoringLookback.iterator()
        while (it.hasNext()) {
            if (it.next().ts < cutoff) it.remove()
        }
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

            if (!previous.isWarning && current == GestureMode.WARNING_RED) {
                val ev = MatchEvent(latestTs, "Warning red")
                matchEvents.add(ev); emitted.add(ev)
            }
            if (!previous.isWarning && current == GestureMode.WARNING_BLUE) {
                val ev = MatchEvent(latestTs, "Warning blue")
                matchEvents.add(ev); emitted.add(ev)
            }

            val currentEventNames = activeGestures
                .map { it.name }
                .toSet()

            val newlyFired = mutableSetOf<String>()
            for (name in currentEventNames) {
                if (name in previousActiveEventGestures) continue
                if (name in ignoredGestureNames) continue

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
            allSamples.addAll(newSamples)
            lastSecondSamples.addAll(newSamples)

            val newestTs = lastSecondSamples.maxOf { it.ts }
            val threshold = newestTs - 1000

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
            scoringLookback.clear()
            captureSamples.clear()

            allSamples.addAll(allData)

            val newestTs = allData.maxOf { it.ts }
            val lastSecondThreshold = newestTs - 1000L
            val bufferDurationThreshold = newestTs - GestureConfig.BUFFER_DURATION_MS
            val scoringLookbackThreshold = newestTs - GestureConfig.SCORING_LOOKBACK_MS

            allData.forEach { s ->
                if (s.ts >= lastSecondThreshold) {
                    lastSecondSamples.add(s)
                }
                if (s.ts >= bufferDurationThreshold) {
                    lastTwoSeconds.add(s)
                }
                if (s.ts >= scoringLookbackThreshold) {
                    scoringLookback.add(s)
                }
            }
        }
    }
}
