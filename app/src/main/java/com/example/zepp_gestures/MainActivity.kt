package com.example.zepp_gestures

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoHTTPD
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.content.ContentValues
import android.provider.MediaStore
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var server: ImuHttpServer? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private val latestGestureMessage = AtomicReference("No gesture detected")

    private lateinit var gyroGraph: GraphView
    private lateinit var accelGraph: GraphView
    private lateinit var gyroTsText: TextView
    private lateinit var accelTsText: TextView
    private lateinit var poseSelect: Spinner
    private val gestures = GestureConfig.gestures
    private var selectedGesture: GestureDefinition = gestures.first()

    private lateinit var inRangeText: TextView
    private lateinit var modeText: TextView
    private lateinit var pointsText: TextView

    private val uiUpdater = object : Runnable {
        override fun run() {
            inRangeText.text = latestGestureMessage.get()
            val mode = server?.getMode() ?: ImuHttpServer.AppMode.WAITING
            modeText.text = when (mode) {
                ImuHttpServer.AppMode.GESTURE -> "Mode: gesture"
                ImuHttpServer.AppMode.WAITING -> "Mode: waiting"
            }
            val (blue, red) = server?.getPoints() ?: (0 to 0)
            pointsText.text = "Blue: $blue | Red: $red"
            val samples = server?.getLastSecondSamples().orEmpty()
            gyroGraph.setSeries(
                samples.map { GraphView.Sample(it.ts, floatArrayOf(it.gx.toFloat(), it.gy.toFloat(), it.gz.toFloat())) },
                listOf("gx", "gy", "gz")
            )
            accelGraph.setSeries(
                samples.map { GraphView.Sample(it.ts, floatArrayOf(it.ax.toFloat(), it.ay.toFloat(), it.az.toFloat())) },
                listOf("ax", "ay", "az")
            )
            updateTimestampText(samples, gyroTsText)
            updateTimestampText(samples, accelTsText)
            handler.postDelayed(this, 300) // refresh ~3x per second
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        val startBtn = findViewById<Button>(R.id.startBtn)
        val stopBtn = findViewById<Button>(R.id.stopBtn)
        val exportBtn = findViewById<Button>(R.id.exportBtn)
        inRangeText = findViewById(R.id.inRangeText)
        modeText = findViewById(R.id.modeText)
        pointsText = findViewById(R.id.pointsText)
        gyroGraph = findViewById(R.id.gyroGraph)
        accelGraph = findViewById(R.id.accelGraph)
        gyroTsText = findViewById(R.id.gyroTsText)
        accelTsText = findViewById(R.id.accelTsText)
        poseSelect = findViewById(R.id.poseSelect)

        gyroGraph.setSeries(emptyList(), listOf("gx", "gy", "gz"))
        accelGraph.setSeries(emptyList(), listOf("ax", "ay", "az"))
        applyAccelBands(selectedGesture)
        accelGraph.setFixedRange(-10f, 10f)

        val options = gestures.map { it.name }
        poseSelect.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        poseSelect.setSelection(0)
        poseSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                selectedGesture = gestures.getOrNull(position) ?: gestures.first()
                applyAccelBands(selectedGesture)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedGesture = gestures.first()
                applyAccelBands(selectedGesture)
            }
        }

        startBtn.setOnClickListener {
            if (server == null) {
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
                statusText.text = "Server running on port 8080"
                handler.post(uiUpdater)
            }
        }

        stopBtn.setOnClickListener {
            val (blue, red) = server?.getPoints() ?: (0 to 0)
            server?.stop()
            server = null
            handler.removeCallbacks(uiUpdater)
            statusText.text = "Server stopped"
            inRangeText.text = "No gesture detected"
            modeText.text = "Mode: waiting"
            pointsText.text = "Blue: 0 | Red: 0"
            gyroGraph.setSeries(emptyList(), listOf("gx", "gy", "gz"))
            accelGraph.setSeries(emptyList(), listOf("ax", "ay", "az"))
            gyroTsText.text = "ts: -"
            accelTsText.text = "ts: -"
            exportPointsCsv(blue, red)
        }

        exportBtn.setOnClickListener {
            exportAllCsv()
        }
    }

    override fun onDestroy() {
        server?.stop()
        handler.removeCallbacks(uiUpdater)
        super.onDestroy()
    }

    private fun applyAccelBands(gesture: GestureDefinition) {
        val alpha = 0x33
        val colors = GraphView.DEFAULT_SERIES_COLORS
        accelGraph.setBands(
            listOf(
                GraphView.Band(0, gesture.bands.axMin.toFloat(), gesture.bands.axMax.toFloat(), withAlpha(colors[0], alpha)),
                GraphView.Band(1, gesture.bands.ayMin.toFloat(), gesture.bands.ayMax.toFloat(), withAlpha(colors[1], alpha)),
                GraphView.Band(2, gesture.bands.azMin.toFloat(), gesture.bands.azMax.toFloat(), withAlpha(colors[2], alpha))
            )
        )
    }

    private fun updateTimestampText(samples: List<ImuSample>, target: TextView) {
        if (samples.isEmpty()) {
            target.text = "ts: -"
            return
        }
        val minTs = samples.minOf { it.ts }
        val maxTs = samples.maxOf { it.ts }
        val midTs = minTs + (maxTs - minTs) / 2
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        target.text = "ts: ${fmt.format(Date(minTs))} | ${fmt.format(Date(midTs))} | ${fmt.format(Date(maxTs))}"
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun exportAllCsv() {
        val samples = server?.getAllSamples().orEmpty()
        if (samples.isEmpty()) {
            Toast.makeText(this, "No samples to export", Toast.LENGTH_SHORT).show()
            return
        }
        exportCsv(samples, "imu_samples")
    }

    private fun exportCsv(samples: List<ImuSample>, prefix: String) {
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
                out.write(csv.toString().toByteArray(Charsets.UTF_8))
            } ?: run {
                Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "Exported to Downloads/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun exportPointsCsv(blue: Int, red: Int) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "points_$timestamp.csv"
        val csv = StringBuilder()
        csv.append("blue_points,red_points\n")
        csv.append(blue).append(',').append(red).append('\n')

        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "text/csv")
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Toast.makeText(this, "Failed to create points file", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            resolver.openOutputStream(uri)?.use { out ->
                out.write(csv.toString().toByteArray(Charsets.UTF_8))
            } ?: run {
                Toast.makeText(this, "Failed to open points file", Toast.LENGTH_SHORT).show()
                return
            }
            Toast.makeText(this, "Points exported to Downloads/$fileName", Toast.LENGTH_LONG).show()
        } catch (e: IOException) {
            Toast.makeText(this, "Points export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
