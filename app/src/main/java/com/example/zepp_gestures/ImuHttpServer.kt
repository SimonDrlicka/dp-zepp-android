package com.example.zepp_gestures

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class MatchEvent(
    val ts: Long,
    val event: String
)

data class ImuSample(
    val gx: Double,
    val gy: Double,
    val gz: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val ts: Long
)

data class VibrationCommand(
    val count: Int,
    val duration: String = "short"
)

class ImuHttpServer(
    private val gestureConfig: List<GestureDefinition>,
    private val latestGestureMessage: AtomicReference<String>,
    port: Int = 8080,
    private val onGestureSegmentReady: (List<ImuSample>) -> Unit = {}
) : NanoHTTPD(port) {
    private val allSamples = mutableListOf<ImuSample>()
    private val sessionSamples = LinkedHashSet<ImuSample>()
    private val lastSecondSamples = mutableListOf<ImuSample>()

    private val lock = Any()

    private val lastHalfSecond = mutableListOf<ImuSample>()
    private val currentMode = AtomicReference(GestureMode.WAITING)
    private val captureSamples = mutableListOf<ImuSample>()
    private val bluePoints = AtomicInteger(0)
    private val redPoints = AtomicInteger(0)
    private var pointArmed = true

    private val matchEvents = mutableListOf<MatchEvent>()
    private val previousActiveEventGestures = mutableSetOf<String>()

    private var passivityRedDeadline: Long = 0L
    private var passivityBlueDeadline: Long = 0L

    companion object {
        private const val PASSIVITY_TIMEOUT_MS = 30_000L
        private const val GESTURE_WINDOW_MS = 300L
        private const val GESTURE_MATCH_RATIO = 0.9
        private val EVENT_GESTURE_NAMES = setOf("Touche")
        private val IGNORED_GESTURE_NAMES = setOf("Hand up", "Hand down")
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
            "ImuHttpServer",
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
        val hasHandDown = activeGestures.any { it.name == "Hand down" }
        val hasRedWarning = activeGestures.any { it.name == "Warning red" }
        val hasBlueWarning = activeGestures.any { it.name == "Warning blue" }

        if (previous.isWarning) {
            if (hasHandDown) {
                currentMode.set(GestureMode.WAITING)
            }
        } else {
            if (hasHandDown) {
                currentMode.set(GestureMode.WAITING)
            } else if (hasRedWarning) {
                currentMode.set(GestureMode.WARNING_RED)
            } else if (hasBlueWarning) {
                currentMode.set(GestureMode.WARNING_BLUE)
            } else if (hasHandUp) {
                currentMode.set(GestureMode.GESTURE)
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
                }
                captureSamples.addAll(newSamples)
            } else if (previous.isScoring && current == GestureMode.WAITING && captureSamples.isNotEmpty()) {
                segmentToExport = captureSamples.toList()
                captureSamples.clear()
            }
        }
        segmentToExport?.let(onGestureSegmentReady)
    }

    private fun modeLabel(mode: GestureMode): String = when (mode) {
        GestureMode.GESTURE -> "gesture"
        GestureMode.WAITING -> "waiting"
        GestureMode.WARNING_RED -> "warning red"
        GestureMode.WARNING_BLUE -> "warning blue"
    }

    private fun updatePoints(newSamples: List<ImuSample>) {
        val mode = currentMode.get()
        if (!mode.isScoring) return
        synchronized(lock) {
            val threshold = GestureConfig.POINT_GYRO_THRESHOLD * GestureConfig.POINT_GYRO_SCALE
            val label = modeLabel(mode)
            newSamples.forEach { s ->
                if (pointArmed) {
                    when {
                        s.gx < -threshold && mode != GestureMode.WARNING_BLUE -> {
                            bluePoints.incrementAndGet()
                            matchEvents.add(MatchEvent(s.ts, "Blue point ($label)"))
                            passivityRedDeadline = 0L
                            passivityBlueDeadline = 0L
                            pointArmed = false
                        }
                        s.gx > threshold && mode != GestureMode.WARNING_RED -> {
                            redPoints.incrementAndGet()
                            matchEvents.add(MatchEvent(s.ts, "Red point ($label)"))
                            passivityRedDeadline = 0L
                            passivityBlueDeadline = 0L
                            pointArmed = false
                        }
                    }
                } else if (s.gx >= -threshold && s.gx <= threshold) {
                    pointArmed = true
                }
            }
        }
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
            if (passivityRedDeadline > 0 && latestTs >= passivityRedDeadline) {
                bluePoints.incrementAndGet()
                matchEvents.add(MatchEvent(latestTs, "Passivity red penalty (blue +1)"))
                passivityRedDeadline = 0L
                expired = true
            }
            if (passivityBlueDeadline > 0 && latestTs >= passivityBlueDeadline) {
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
        if (passivityExpired) return VibrationCommand(1, "long")
        if ("Touche" in newEventGestures) return VibrationCommand(5, "short")
        if (passivityStarted) return VibrationCommand(2, "short")
        val (previous, current) = modeChange
        if (previous != current) {
            if (current.isWarning) return VibrationCommand(2, "short")
            return VibrationCommand(1, "short")
        }
        return VibrationCommand(0, "short")
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/health" -> {
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                }

                // ✅ NEW: matches your FastAPI endpoint
                session.method == Method.POST && session.uri == "/gyro-data-full" -> {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val body = files["postData"] ?: ""

                    val result = handleGyroDataFull(body)
                    newFixedLengthResponse(Response.Status.OK, "application/json", result)
                }

                // ✅ NEW: replace all stored data with the full history payload
                session.method == Method.POST && session.uri == "/gyro-data-full-reset" -> {
                    val files = HashMap<String, String>()
                    session.parseBody(files)
                    val body = files["postData"] ?: ""

                    val result = handleGyroDataFullReset(body)
                    newFixedLengthResponse(Response.Status.OK, "application/json", result)
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            Log.e("ImuHttpServer", "Error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun handleGyroDataFull(body: String): String {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return """{"status":"error","detail":"Invalid JSON"}"""
        }

        val packed = json.optString("data", "").trim()
        if (packed.isEmpty()) {
            return """{"status":"error","detail":"Empty data"}"""
        }

        val parsed = parsePackedData(packed)
        if (parsed.isEmpty()) {
            return """{"status":"error","detail":"No valid samples"}"""
        }

        appendToSessionSamples(parsed)
        updateHalfSecond(parsed)
        val activeGestures = inRangeHalfSecond()
        val modeChange = updateMode(activeGestures)
        updateBuffers(parsed)
        updatePoints(parsed)
        updateCapture(parsed, modeChange)
        val newEventGestures = updateMatchEvents(activeGestures, modeChange, parsed.last().ts)
        val (passivityStarted, passivityExpired) = updatePassivity(activeGestures, parsed.last().ts)
        val message = if (activeGestures.isEmpty()) {
            "No gesture detected"
        } else {
            activeGestures.joinToString(" | ") { it.message }
        }
        latestGestureMessage.set(message)

        val blue = bluePoints.get()
        val red = redPoints.get()
        val score = "$blue-$red"
        val vibrationCmd = resolveVibration(modeChange, passivityStarted, passivityExpired, newEventGestures)

        return """{
            "status":"ok",
            "received":${parsed.size},
            "total":${allSamples.size},
            "last_second":${lastSecondSamples.size},
            "blue_points":$blue,
            "red_points":$red,
            "bluePoints":$blue,
            "redPoints":$red,
            "score":"$score",
            "message":"$message",
            "vibration":${vibrationCmd.count},
            "vibrationDuration":"${vibrationCmd.duration}"
        }"""
    }

    private fun handleGyroDataFullReset(body: String): String {
        val json = try {
            JSONObject(body)
        } catch (e: Exception) {
            return """{"status":"error","detail":"Invalid JSON"}"""
        }

        val packed = json.optString("data", "").trim()
        if (packed.isEmpty()) {
            return """{"status":"error","detail":"Empty data"}"""
        }

        val parsed = parsePackedData(packed)
        if (parsed.isEmpty()) {
            return """{"status":"error","detail":"No valid samples"}"""
        }

        appendToSessionSamples(parsed)
        replaceBuffers(parsed)
        val activeGestures = inRangeHalfSecond()
        val modeChange = updateMode(activeGestures)
        updatePoints(parsed)
        updateCapture(parsed, modeChange)
        val newEventGestures = updateMatchEvents(activeGestures, modeChange, parsed.last().ts)
        val (passivityStarted, passivityExpired) = updatePassivity(activeGestures, parsed.last().ts)
        val message = if (activeGestures.isEmpty()) {
            "No gesture detected"
        } else {
            activeGestures.joinToString(" | ") { it.message }
        }
        latestGestureMessage.set(message)

        val blue = bluePoints.get()
        val red = redPoints.get()
        val score = "$blue-$red"
        val vibrationCmd = resolveVibration(modeChange, passivityStarted, passivityExpired, newEventGestures)

        return """{
            "status":"ok",
            "received":${parsed.size},
            "total":${allSamples.size},
            "last_second":${lastSecondSamples.size},
            "blue_points":$blue,
            "red_points":$red,
            "bluePoints":$blue,
            "redPoints":$red,
            "score":"$score",
            "message":"$message",
            "vibration":${vibrationCmd.count},
            "vibrationDuration":"${vibrationCmd.duration}"
        }"""
    }

    private fun appendToSessionSamples(newSamples: List<ImuSample>) {
        if (newSamples.isEmpty()) return
        synchronized(lock) {
            sessionSamples.addAll(newSamples)
        }
    }

    private fun parsePackedData(packed: String): List<ImuSample> {
        val out = ArrayList<ImuSample>()

        val samples = packed.split("|")
        for (s0 in samples) {
            val s = s0.trim()
            if (s.isEmpty()) continue

            val parts = s.split(",")
            if (parts.size != 7) continue

            try {
                val gx = parts[0].toDouble()
                val gy = parts[1].toDouble()
                val gz = parts[2].toDouble()

                val ax = parts[3].toDouble() / 100.0
                val ay = parts[4].toDouble() / 100.0
                val az = parts[5].toDouble() / 100.0
                val ts = parts[6].toLong()

                out.add(
                    ImuSample(
                        gx = gx,
                        gy = gy,
                        gz = gz,
                        ax = ax,
                        ay = ay,
                        az = az,
                        ts = ts
                    )
                )
            } catch (_: Exception) {
                continue
            }
        }
        return out
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

    fun getLastSecondSamples(): List<ImuSample> = synchronized(lock) {
        lastSecondSamples.toList()
    }

    fun getAllSamples(): List<ImuSample> = synchronized(lock) {
        allSamples.toList()
    }

    fun getSessionSamples(): List<ImuSample> = synchronized(lock) {
        sessionSamples.toList()
    }

    fun getMode(): GestureMode = currentMode.get()

    fun getMatchEvents(): List<MatchEvent> = synchronized(lock) {
        matchEvents.toList()
    }

    fun getPassivityDeadlines(): Pair<Long, Long> = synchronized(lock) {
        passivityRedDeadline to passivityBlueDeadline
    }

    fun getPoints(): Pair<Int, Int> = bluePoints.get() to redPoints.get()

    fun resetPoints() {
        bluePoints.set(0)
        redPoints.set(0)
        synchronized(lock) {
            pointArmed = true
            passivityRedDeadline = 0L
            passivityBlueDeadline = 0L
        }
    }
}
