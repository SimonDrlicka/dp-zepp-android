package com.example.zepp_gestures

import android.content.ContentValues
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoHTTPD
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    var server: ImuHttpServer? = null
    val latestGestureMessage = AtomicReference("No gesture detected")
    val gestures = GestureConfig.gestures

    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Data-collection build: only the graph view, no Debug/Prod
        // tabs. ProdFragment stays in the codebase but is unreachable
        // here.
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, DebugFragment())
                .commit()
        }
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    fun startServer() {
        if (server != null) return
        server = ImuHttpServer(
            gestures,
            latestGestureMessage,
            8080
        ) { samples ->
            handler.post {
                exportCsv(samples, "gesture_segment")
            }
        }.apply {
            resetPoints()
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        }
    }

    fun stopServer() {
        val s = server ?: return
        val (blue, red) = s.getPoints()
        val samples = s.getSessionSamples()
        val events = s.getMatchEvents()
        if (samples.isNotEmpty()) {
            exportCsv(samples, "stop_server_export")
        }
        if (events.isNotEmpty()) {
            exportMatchEventsCsv(events, blue, red)
        }
        exportPointsCsv(blue, red)
        s.stop()
        server = null
    }

    fun exportAllCsv() {
        val samples = server?.getSessionSamples().orEmpty()
        if (samples.isEmpty()) {
            Toast.makeText(this, "No samples to export", Toast.LENGTH_SHORT).show()
            return
        }
        exportCsv(samples, "imu_samples")
    }

    fun exportCsv(samples: List<ImuSample>, prefix: String) {
        if (samples.isEmpty()) {
            Toast.makeText(this, "No samples to export", Toast.LENGTH_SHORT).show()
            return
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${prefix}_$timestamp.csv"

        val csv = StringBuilder()
        csv.append("ts,gx,gy,gz,ax,ay,az\n")
        samples.forEach { s ->
            csv.append(s.ts).append(',')
                .append(s.gx).append(',')
                .append(s.gy).append(',')
                .append(s.gz).append(',')
                .append(s.ax).append(',')
                .append(s.ay).append(',')
                .append(s.az).append('\n')
        }

        writeToDownloads(fileName, csv.toString(), "Exported to Downloads/$fileName")
    }

    private fun exportMatchEventsCsv(events: List<MatchEvent>, blue: Int, red: Int) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "match_events_$timestamp.csv"
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

        val csv = StringBuilder()
        csv.append("# result: blue=$blue red=$red\n")
        csv.append("timestamp,datetime,event\n")
        events.forEach { e ->
            csv.append(e.ts).append(',')
                .append(fmt.format(Date(e.ts))).append(',')
                .append(e.event).append('\n')
        }

        writeToDownloads(fileName, csv.toString(), "Events exported to Downloads/$fileName")
    }

    private fun exportPointsCsv(blue: Int, red: Int) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "points_$timestamp.csv"
        val csv = StringBuilder()
        csv.append("blue_points,red_points\n")
        csv.append(blue).append(',').append(red).append('\n')

        writeToDownloads(fileName, csv.toString(), "Points exported to Downloads/$fileName")
    }

    private fun writeToDownloads(fileName: String, content: String, successMessage: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "Failed to create file", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
            } ?: run {
                Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, successMessage, Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
