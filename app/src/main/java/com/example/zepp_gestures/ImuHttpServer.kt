package com.example.zepp_gestures

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

class ImuHttpServer(
    private val service: ImuIngestor,
    port: Int = 8080
) : NanoHTTPD(port) {

    private val receiveRequestTimestamps = mutableListOf<Long>()
    private val lock = Any()

    companion object {
        private const val RECEIVE_FREQ_WINDOW_MS = 1_000L
    }

    override fun serve(session: IHTTPSession): Response {
        return try {
            when {
                session.method == Method.GET && session.uri == "/health" -> {
                    newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
                }

                session.method == Method.POST && session.uri == "/gyro-data-full" -> {
                    handlePost(session) { service.ingest(it) }
                }

                session.method == Method.POST && session.uri == "/gyro-data-full-reset" -> {
                    handlePost(session) { service.ingestReset(it) }
                }

                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (e: Exception) {
            Log.e("ImuHttpServer", "Error", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        }
    }

    private fun handlePost(
        session: IHTTPSession,
        ingest: (List<ImuSample>) -> IngestResult
    ): Response {
        recordReceiveRequest()

        val files = HashMap<String, String>()
        session.parseBody(files)
        val body = files["postData"] ?: ""

        val packed = parsePackedField(body)
            ?: return jsonResponse("""{"status":"error","detail":"Invalid JSON"}""")
        if (packed.isEmpty()) {
            return jsonResponse("""{"status":"error","detail":"Empty data"}""")
        }

        val parsed = PackedDataParser.parse(packed)
        if (parsed.isEmpty()) {
            return jsonResponse("""{"status":"error","detail":"No valid samples"}""")
        }

        val result = ingest(parsed)
        return jsonResponse(buildResponseJson(result))
    }

    private fun parsePackedField(body: String): String? = try {
        JSONObject(body).optString("data", "").trim()
    } catch (_: Exception) {
        null
    }

    private fun jsonResponse(body: String): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", body)

    private fun buildResponseJson(r: IngestResult): String {
        val score = "${r.bluePoints}-${r.redPoints}"
        return """{
            "status":"ok",
            "received":${r.received},
            "total":${r.total},
            "last_second":${r.lastSecondCount},
            "blue_points":${r.bluePoints},
            "red_points":${r.redPoints},
            "bluePoints":${r.bluePoints},
            "redPoints":${r.redPoints},
            "score":"$score",
            "message":"${r.message}",
            "vibration":${r.vibration.count},
            "vibrationDuration":"${r.vibration.duration}",
            "vibrationFollowup":${r.vibration.followupCount},
            "vibrationFollowupDuration":"${r.vibration.followupDuration}"
        }"""
    }

    private fun recordReceiveRequest() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            receiveRequestTimestamps.add(now)
            val threshold = now - RECEIVE_FREQ_WINDOW_MS
            receiveRequestTimestamps.removeAll { it < threshold }
        }
    }

    fun getReceiveRequestFrequencyHz(): Double = synchronized(lock) {
        if (receiveRequestTimestamps.size < 2) {
            return@synchronized if (receiveRequestTimestamps.isEmpty()) 0.0 else -1.0
        }

        val minTs = receiveRequestTimestamps.minOrNull() ?: return@synchronized 0.0
        val maxTs = receiveRequestTimestamps.maxOrNull() ?: return@synchronized 0.0
        val durationMs = (maxTs - minTs).coerceAtLeast(1L)
        (receiveRequestTimestamps.size - 1) * 1000.0 / durationMs
    }
}
