package com.example.zepp_gestures

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class ImuSample(
    val gx: Double,
    val gy: Double,
    val gz: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val ts: Long
)

class ImuHttpServer(
    private val gestureConfig: List<GestureDefinition>,
    private val latestGestureMessage: AtomicReference<String>,
    port: Int = 8080,
    private val onGestureSegmentReady: (List<ImuSample>) -> Unit = {}
) : NanoHTTPD(port) {
    enum class AppMode {
        WAITING,
        GESTURE
    }

    private val allSamples = mutableListOf<ImuSample>()
    private val lastSecondSamples = mutableListOf<ImuSample>()

    private val lock = Any()

    private val lastHalfSecond = mutableListOf<ImuSample>()
    private val currentMode = AtomicReference(AppMode.WAITING)
    private val captureSamples = mutableListOf<ImuSample>()
    private val bluePoints = AtomicInteger(0)
    private val redPoints = AtomicInteger(0)
    private var pointArmed = true

    private fun updateHalfSecond(newSamples: List<ImuSample>) {
        if (newSamples.isEmpty()) return

        synchronized(lock) {
            lastHalfSecond.addAll(newSamples)

            val newestTs = lastHalfSecond.maxOf { it.ts }
            val threshold = newestTs - 500L

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
            var outCount = 0
            snapshot.forEach { s ->
                val ok =
                    s.ax in gesture.bands.axMin..gesture.bands.axMax &&
                            s.ay in gesture.bands.ayMin..gesture.bands.ayMax &&
                            s.az in gesture.bands.azMin..gesture.bands.azMax
                if (!ok) outCount++
            }
            if (outCount == 0) {
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

    private fun updateMode(activeGestures: List<GestureDefinition>): Pair<AppMode, AppMode> {
        val previous = currentMode.get()
        if (activeGestures.isEmpty()) return previous to previous

        val hasHandUp = activeGestures.any { it.name == "Hand up" }
        val hasHandDown = activeGestures.any { it.name == "Hand down" }

        if (hasHandUp) {
            currentMode.set(AppMode.GESTURE)
        } else if (hasHandDown) {
            currentMode.set(AppMode.WAITING)
        }
        return previous to currentMode.get()
    }

    private fun updateCapture(newSamples: List<ImuSample>, modeChange: Pair<AppMode, AppMode>) {
        val (previous, current) = modeChange
        var segmentToExport: List<ImuSample>? = null
        synchronized(lock) {
            if (current == AppMode.GESTURE) {
                if (previous != AppMode.GESTURE) {
                    captureSamples.clear()
                }
                captureSamples.addAll(newSamples)
            } else if (previous == AppMode.GESTURE && current == AppMode.WAITING && captureSamples.isNotEmpty()) {
                segmentToExport = captureSamples.toList()
                captureSamples.clear()
            }
        }
        segmentToExport?.let(onGestureSegmentReady)
    }

    private fun updatePoints(newSamples: List<ImuSample>) {
        if (currentMode.get() != AppMode.GESTURE) return
        synchronized(lock) {
            val threshold = GestureConfig.POINT_GYRO_THRESHOLD * GestureConfig.POINT_GYRO_SCALE
            newSamples.forEach { s ->
                if (pointArmed) {
                    when {
                        s.gx < -threshold -> {
                            bluePoints.incrementAndGet()
                            pointArmed = false
                        }
                        s.gx > threshold -> {
                            redPoints.incrementAndGet()
                            pointArmed = false
                        }
                    }
                } else if (s.gx >= -threshold && s.gx <= threshold) {
                    pointArmed = true
                }
            }
        }
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

        updateHalfSecond(parsed)
        val activeGestures = inRangeHalfSecond()
        val modeChange = updateMode(activeGestures)
        updateBuffers(parsed)
        updatePoints(parsed)
        updateCapture(parsed, modeChange)
        val message = if (activeGestures.isEmpty()) {
            "No gesture detected"
        } else {
            activeGestures.joinToString(" | ") { it.message }
        }
        latestGestureMessage.set(message)

        return """{
            "status":"ok",
            "received":${parsed.size},
            "total":${allSamples.size},
            "last_second":${lastSecondSamples.size}
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

        val activeGestures = inRangeHalfSecond()
        val modeChange = updateMode(activeGestures)
        replaceBuffers(parsed)
        updatePoints(parsed)
        updateCapture(parsed, modeChange)
        val message = if (activeGestures.isEmpty()) {
            "No gesture detected"
        } else {
            activeGestures.joinToString(" | ") { it.message }
        }
        latestGestureMessage.set(message)

        return """{
            "status":"ok",
            "received":${parsed.size},
            "total":${allSamples.size},
            "last_second":${lastSecondSamples.size}
        }"""
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

    fun getMode(): AppMode = currentMode.get()

    fun getPoints(): Pair<Int, Int> = bluePoints.get() to redPoints.get()

    fun resetPoints() {
        bluePoints.set(0)
        redPoints.set(0)
        synchronized(lock) {
            pointArmed = true
        }
    }
}
