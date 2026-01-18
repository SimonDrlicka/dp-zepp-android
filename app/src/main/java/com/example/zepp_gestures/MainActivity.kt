package com.example.zepp_gestures

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoHTTPD
import android.widget.ArrayAdapter
import android.widget.AdapterView

class MainActivity : AppCompatActivity() {

    private var server: ImuHttpServer? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private val latestHandUp = java.util.concurrent.atomic.AtomicBoolean(false)
    private val latestHandDown = java.util.concurrent.atomic.AtomicBoolean(false)

    private lateinit var gyroGraph: GraphView
    private lateinit var accelGraph: GraphView
    private lateinit var gyroTsText: TextView
    private lateinit var accelTsText: TextView
    private lateinit var poseSelect: Spinner
    private var selectedBands: ImuHttpServer.HandBands = ImuHttpServer.HandBands.HAND_UP

    private lateinit var inRangeText: TextView

    private val uiUpdater = object : Runnable {
        override fun run() {
            inRangeText.text = "hand up: ${latestHandUp.get()} | hand down: ${latestHandDown.get()}"
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
        inRangeText = findViewById(R.id.inRangeText)
        gyroGraph = findViewById(R.id.gyroGraph)
        accelGraph = findViewById(R.id.accelGraph)
        gyroTsText = findViewById(R.id.gyroTsText)
        accelTsText = findViewById(R.id.accelTsText)
        poseSelect = findViewById(R.id.poseSelect)

        gyroGraph.setSeries(emptyList(), listOf("gx", "gy", "gz"))
        accelGraph.setSeries(emptyList(), listOf("ax", "ay", "az"))
        applyAccelBands(selectedBands)

        val options = listOf("Hand up", "Hand down")
        poseSelect.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, options)
        poseSelect.setSelection(0)
        poseSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                selectedBands = if (position == 0) {
                    ImuHttpServer.HandBands.HAND_UP
                } else {
                    ImuHttpServer.HandBands.HAND_DOWN
                }
                applyAccelBands(selectedBands)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedBands = ImuHttpServer.HandBands.HAND_UP
                applyAccelBands(selectedBands)
            }
        }

        startBtn.setOnClickListener {
            if (server == null) {
                server = ImuHttpServer(latestHandUp, latestHandDown, 8080).apply {
                    start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                }
                statusText.text = "Server running on port 8080"
                handler.post(uiUpdater)
            }
        }

        stopBtn.setOnClickListener {
            server?.stop()
            server = null
            handler.removeCallbacks(uiUpdater)
            statusText.text = "Server stopped"
            inRangeText.text = "hand up: ${latestHandUp.get()} | hand down: ${latestHandDown.get()}"
            gyroGraph.setSeries(emptyList(), listOf("gx", "gy", "gz"))
            accelGraph.setSeries(emptyList(), listOf("ax", "ay", "az"))
            gyroTsText.text = "ts: -"
            accelTsText.text = "ts: -"
        }
    }

    override fun onDestroy() {
        server?.stop()
        handler.removeCallbacks(uiUpdater)
        super.onDestroy()
    }

    private fun applyAccelBands(bands: ImuHttpServer.HandBands) {
        val alpha = 0x33
        val colors = GraphView.DEFAULT_SERIES_COLORS
        accelGraph.setBands(
            listOf(
                GraphView.Band(0, bands.axMin.toFloat(), bands.axMax.toFloat(), withAlpha(colors[0], alpha)),
                GraphView.Band(1, bands.ayMin.toFloat(), bands.ayMax.toFloat(), withAlpha(colors[1], alpha)),
                GraphView.Band(2, bands.azMin.toFloat(), bands.azMax.toFloat(), withAlpha(colors[2], alpha))
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
        target.text = "ts: $minTs | $midTs | $maxTs"
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }
}
