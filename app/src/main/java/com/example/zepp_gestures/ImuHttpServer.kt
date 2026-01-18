package com.example.zepp_gestures

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

data class ParsedSample(
    val gx: Double,
    val gy: Double,
    val gz: Double,
    val ax: Double,
    val ay: Double,
    val az: Double,
    val ts: Long
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

class ImuHttpServer(
    private val gestureConfig: List<GestureDefinition>,
    private val latestGestureMessage: AtomicReference<String>,
    port: Int = 8080
) : NanoHTTPD(port) {
    private val allSamples = mutableListOf<ImuSample>()
    private val lastSecondSamples = mutableListOf<ImuSample>()

    private val lock = Any()

    private val lastHalfSecond = mutableListOf<ImuSample>()

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

        updateBuffers(parsed)

        updateHalfSecond(parsed)
        val activeGestures = inRangeHalfSecond()
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

    fun getLastSecondSamples(): List<ImuSample> = synchronized(lock) {
        lastSecondSamples.toList()
    }
}
