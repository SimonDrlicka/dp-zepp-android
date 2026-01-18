package com.example.zepp_gestures

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import fi.iki.elonen.NanoHTTPD

class MainActivity : AppCompatActivity() {

    private var server: ImuHttpServer? = null

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private val latestHandUp = java.util.concurrent.atomic.AtomicBoolean(false)
    private val latestHandDown = java.util.concurrent.atomic.AtomicBoolean(false)

    private lateinit var gyroGraph: GraphView
    private lateinit var accelGraph: GraphView

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

        gyroGraph.setSeries(emptyList(), listOf("gx", "gy", "gz"))
        accelGraph.setSeries(emptyList(), listOf("ax", "ay", "az"))

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
        }
    }

    override fun onDestroy() {
        server?.stop()
        handler.removeCallbacks(uiUpdater)
        super.onDestroy()
    }
}
