package com.example.zepp_gestures

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GestureRecognitionService(
    private val gestureConfig: List<GestureDefinition>,
    private val latestGestureMessage: AtomicReference<String>,
    private val onGestureSegmentReady: (List<ImuSample>) -> Unit = {}
) {
    private val allSamples = mutableListOf<ImuSample>()
    private val sessionSamples = LinkedHashSet<ImuSample>()
    private val lastSecondSamples = mutableListOf<ImuSample>()
    private val lastHalfSecond = mutableListOf<ImuSample>()
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
    // (reset on entering scoring, latched out on exit so the watch can buzz
    // a per-gesture summary).
    private var pointsThisGesture: Int = 0
    private var pendingScoringExitFlicks: Int? = null

    private val lock = Any()

    companion object {
        private const val PASSIVITY_TIMEOUT_MS = 30_000L
        private const val GESTURE_WINDOW_MS = 300L
        private const val GESTURE_MATCH_RATIO = 0.9
        private val EVENT_GESTURE_NAMES = setOf("Touche")
        private val IGNORED_GESTURE_NAMES = setOf(
            "Hand up", "Hand down", "Hand back",
            "Warning red", "Warning blue",
            "Passivity red", "Passivity blue",
            "Flick red", "Flick blue"
        )
    }

    fun ingest(parsed: List<ImuSample>): IngestResult {
        appendToSessionSamples(parsed)
        updateHalfSecond(parsed)
        val activeGestures = inRangeHalfSecond()
        val modeChange = updateMode(activeGestures)
        updateBuffers(parsed)
        return runScoringPipeline(parsed, activeGestures, modeChange)
    }

    fun ingestReset(parsed: List<ImuSample>): IngestResult {
        appendToSessionSamples(parsed)
        replaceBuffers(parsed)
        val activeGestures = inRangeHalfSecond()
        val modeChange = updateMode(activeGestures)
        return runScoringPipeline(parsed, activeGestures, modeChange)
    }

    private fun runScoringPipeline(
        parsed: List<ImuSample>,
        activeGestures: List<GestureDefinition>,
        modeChange: Pair<GestureMode, GestureMode>
    ): IngestResult {
        val latestTs = parsed.last().ts
        updatePoints(parsed, activeGestures, latestTs)
        updateCapture(parsed, modeChange)
        val newEventGestures = updateMatchEvents(activeGestures, modeChange, latestTs)
        val (passivityStarted, passivityExpired) = updatePassivity(activeGestures, latestTs)
        val message = if (activeGestures.isEmpty()) {
            "No gesture detected"
        } else {
            activeGestures.joinToString(" | ") { it.message }
        }
        latestGestureMessage.set(message)

        val vibration = resolveVibration(modeChange, passivityStarted, passivityExpired, newEventGestures)
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
            pendingScoringExitFlicks = null
        }
    }

    private fun appendToSessionSamples(newSamples: List<ImuSample>) {
        if (newSamples.isEmpty()) return
        synchronized(lock) {
            sessionSamples.addAll(newSamples)
        }
    }

    private fun updateHalfSecond(newSamples: List<ImuSample>) {
        if (newSamples.isEmpty()) return

        synchronized(lock) {
            lastHalfSecond.addAll(newSamples)

            val newestTs = lastHalfSecond.maxOf { it.ts }
            val threshold = newestTs - GESTURE_WINDOW_MS

            val it = lastHalfSecond.iterator()
            while (it.hasNext()) {
                if (it.next().ts < threshold) it.remove()
            }
        }
    }

    private fun inRangeHalfSecond(): List<GestureDefinition> {

        val snapshot: List<ImuSample> = synchronized(lock) { lastHalfSecond.toList() }
        if (snapshot.isEmpty()) return emptyList()

        val active = ArrayList<GestureDefinition>()

        gestureConfig.forEach { gesture ->
            var inCount = 0
            snapshot.forEach { s ->
                val ok =
                    s.ax in gesture.bands.axMin..gesture.bands.axMax &&
                            s.ay in gesture.bands.ayMin..gesture.bands.ayMax &&
                            s.az in gesture.bands.azMin..gesture.bands.azMax
                if (ok) inCount++
            }
            val matchRatio = inCount.toDouble() / snapshot.size.toDouble()
            if (matchRatio >= GESTURE_MATCH_RATIO) {
                active.add(gesture)
            }
        }

        Log.d(
            "GestureRecognitionService",
            "inRangeHalfSecond summary: samples=${snapshot.size} " +
                    "mean value: ax=${"%.2f".format(snapshot.map { it.ax }.average())} " +
                    "ay=${"%.2f".format(snapshot.map { it.ay }.average()) } " +
                    " az=${"%.2f".format(snapshot.map { it.az }.average())} " +
                    "| active=${active.joinToString { it.name }}"
        )

        return active
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
        synchronized(lock) {
            if (current.isScoring) {
                if (!previous.isScoring) {
                    captureSamples.clear()
                    pointsThisGesture = 0
                }
                captureSamples.addAll(newSamples)
            } else if (previous.isScoring && current == GestureMode.WAITING) {
                pendingScoringExitFlicks = pointsThisGesture
                pointsThisGesture = 0
                if (captureSamples.isNotEmpty()) {
                    segmentToExport = captureSamples.toList()
                    captureSamples.clear()
                }
            }
        }
        segmentToExport?.let(onGestureSegmentReady)
    }

    private fun updatePoints(
        newSamples: List<ImuSample>,
        activeGestures: List<GestureDefinition>,
        latestTs: Long
    ) {
        val mode = currentMode.get()
        if (!mode.isScoring) return
        synchronized(lock) {
            val gxThreshold = GestureConfig.POINT_GYRO_GX_THRESHOLD * GestureConfig.POINT_GYRO_GX_SCALE
            val label = mode.label
            newSamples.forEach { s ->
                if (pointArmed) {
                    if (s.gx < -gxThreshold) {
                        when (mode) {
                            GestureMode.GESTURE_RED -> {
                                redPoints.incrementAndGet()
                                matchEvents.add(MatchEvent(s.ts, "Red point ($label)"))
                                pointsThisGesture++
                                clearPassivityAndDisarm()
                            }
                            GestureMode.GESTURE_BLUE -> {
                                bluePoints.incrementAndGet()
                                matchEvents.add(MatchEvent(s.ts, "Blue point ($label)"))
                                pointsThisGesture++
                                clearPassivityAndDisarm()
                            }
                            GestureMode.WARNING_RED -> {
                                bluePoints.incrementAndGet()
                                matchEvents.add(MatchEvent(s.ts, "Blue point ($label)"))
                                pointsThisGesture++
                                clearPassivityAndDisarm()
                            }
                            GestureMode.WARNING_BLUE -> {
                                redPoints.incrementAndGet()
                                matchEvents.add(MatchEvent(s.ts, "Red point ($label)"))
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
    }

    private fun clearPassivityAndDisarm() {
        passivityRedDeadline = 0L
        passivityBlueDeadline = 0L
        pointArmed = false
    }

    private fun updateMatchEvents(
        activeGestures: List<GestureDefinition>,
        modeChange: Pair<GestureMode, GestureMode>,
        latestTs: Long
    ): Set<String> {
        val (previous, current) = modeChange
        synchronized(lock) {
            // Log warning mode transitions
            if (!previous.isWarning && current == GestureMode.WARNING_RED) {
                matchEvents.add(MatchEvent(latestTs, "Warning red"))
            }
            if (!previous.isWarning && current == GestureMode.WARNING_BLUE) {
                matchEvents.add(MatchEvent(latestTs, "Warning blue"))
            }

            // Log detected gestures with deduplication (skip hand_up/hand_down)
            val currentEventNames = activeGestures
                .map { it.name }
                .toSet()

            val newlyFired = mutableSetOf<String>()
            for (name in currentEventNames) {
                if (name !in previousActiveEventGestures && name !in IGNORED_GESTURE_NAMES) {
                    matchEvents.add(MatchEvent(latestTs, name))
                    if (name in EVENT_GESTURE_NAMES) {
                        newlyFired.add(name)
                    }
                }
            }
            previousActiveEventGestures.clear()
            previousActiveEventGestures.addAll(currentEventNames)
            return newlyFired
        }
    }

    private fun updatePassivity(activeGestures: List<GestureDefinition>, latestTs: Long): Pair<Boolean, Boolean> {
        synchronized(lock) {
            var started = false
            var expired = false

            // Start new timer only in WAITING mode and only if no passivity is already active
            val anyPassivityActive = passivityRedDeadline > 0 || passivityBlueDeadline > 0
            if (currentMode.get() == GestureMode.WAITING && !anyPassivityActive) {
                val hasPassivityRed = activeGestures.any { it.name == "Passivity red" }
                val hasPassivityBlue = activeGestures.any { it.name == "Passivity blue" }

                if (hasPassivityRed) {
                    passivityRedDeadline = latestTs + PASSIVITY_TIMEOUT_MS
                    matchEvents.add(MatchEvent(latestTs, "Passivity red"))
                    started = true
                } else if (hasPassivityBlue) {
                    passivityBlueDeadline = latestTs + PASSIVITY_TIMEOUT_MS
                    matchEvents.add(MatchEvent(latestTs, "Passivity blue"))
                    started = true
                }
            }

            // Check expired timers (runs in any mode)
            if (passivityRedDeadline in 1..latestTs) {
                bluePoints.incrementAndGet()
                matchEvents.add(MatchEvent(latestTs, "Passivity red penalty (blue +1)"))
                passivityRedDeadline = 0L
                expired = true
            }
            if (passivityBlueDeadline in 1..latestTs) {
                redPoints.incrementAndGet()
                matchEvents.add(MatchEvent(latestTs, "Passivity blue penalty (red +1)"))
                passivityBlueDeadline = 0L
                expired = true
            }

            return started to expired
        }
    }

    private fun resolveVibration(
        modeChange: Pair<GestureMode, GestureMode>,
        passivityStarted: Boolean,
        passivityExpired: Boolean,
        newEventGestures: Set<String>
    ): VibrationCommand {
        // Always consume the latched flick count so a stale value can never
        // leak into a later tick if a higher-priority signal pre-empts it.
        val flickCount = synchronized(lock) {
            val v = pendingScoringExitFlicks
            pendingScoringExitFlicks = null
            v
        }
        if (passivityExpired) return VibrationCommand(1, "long")
        if ("Touche" in newEventGestures) return VibrationCommand(5, "short")
        // Scoring gesture just ended: long buzz + N shorts (one per flick).
        if (flickCount != null) {
            return VibrationCommand(
                count = 1,
                duration = "long",
                followupCount = flickCount,
                followupDuration = "short"
            )
        }
        if (passivityStarted) return VibrationCommand(2, "short")
        val (previous, current) = modeChange
        if (previous != current) {
            if (current.isWarning) return VibrationCommand(2, "short")
            // Entering a scoring gesture (hand_up -> GESTURE_RED,
            // hand_back -> GESTURE_BLUE) gets a distinctive short-strong
            // buzz so the wearer can tell scoring just armed.
            if (current.isScoring) return VibrationCommand(1, "short_strong")
            return VibrationCommand(1, "short")
        }
        return VibrationCommand(0, "short")
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
            lastHalfSecond.clear()
            captureSamples.clear()

            allSamples.addAll(allData)

            val newestTs = allData.maxOf { it.ts }
            val lastSecondThreshold = newestTs - 1000L
            val lastHalfSecondThreshold = newestTs - 500L

            allData.forEach { s ->
                if (s.ts >= lastSecondThreshold) {
                    lastSecondSamples.add(s)
                }
                if (s.ts >= lastHalfSecondThreshold) {
                    lastHalfSecond.add(s)
                }
            }
        }
    }
}
