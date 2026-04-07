package com.example.zepp_gestures

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DebugFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusText: TextView
    private lateinit var gyroGraph: GraphView
    private lateinit var accelGraph: GraphView
    private lateinit var gyroTsText: TextView
    private lateinit var accelTsText: TextView
    private lateinit var poseSelect: Spinner
    private lateinit var graphRangeSelect: Spinner
    private lateinit var modeText: TextView
    private lateinit var pointsText: TextView
    private lateinit var passivityTimerText: TextView

    private val gestures = GestureConfig.gestures
    private var selectedGesture: GestureDefinition = gestures.first()
    private var graphDisplayMode: GraphDisplayMode = GraphDisplayMode.LAST_20_SECONDS

    private val main: MainActivity get() = activity as MainActivity

    private enum class GraphDisplayMode(val label: String) {
        LAST_SECOND("Graf: posledna 1 s"),
        LAST_20_SECONDS("Graf: poslednych 20 s"),
        ALL_SAMPLES("Graf: vsetky data od startu servera")
    }

    private val graphDisplayModes = GraphDisplayMode.values().toList()

    private val uiUpdater = object : Runnable {
        override fun run() {
            if (!isAdded) return
            val server = main.server
            val gestureInfo = main.latestGestureMessage.get()
            val mode = server?.getMode() ?: GestureMode.WAITING
            val modeLabel = when (mode) {
                GestureMode.GESTURE -> "Mode: gesture"
                GestureMode.WAITING -> "Mode: waiting"
                GestureMode.WARNING_RED -> "Mode: warning red"
                GestureMode.WARNING_BLUE -> "Mode: warning blue"
            }
            modeText.text = "$modeLabel | $gestureInfo"
            val (blue, red) = server?.getPoints() ?: (0 to 0)
            pointsText.text = "Blue: $blue | Red: $red | Freq: ${formatReceiveFrequency(server)}"
            val samples = when (graphDisplayMode) {
                GraphDisplayMode.LAST_SECOND -> server?.getLastSecondSamples().orEmpty()
                GraphDisplayMode.LAST_20_SECONDS -> getLastTwentySecondSamples(server)
                GraphDisplayMode.ALL_SAMPLES -> server?.getAllSamples().orEmpty()
            }
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
            updatePassivityTimer(server)
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_debug, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.statusText)
        modeText = view.findViewById(R.id.modeText)
        pointsText = view.findViewById(R.id.pointsText)
        gyroGraph = view.findViewById(R.id.gyroGraph)
        accelGraph = view.findViewById(R.id.accelGraph)
        gyroTsText = view.findViewById(R.id.gyroTsText)
        accelTsText = view.findViewById(R.id.accelTsText)
        poseSelect = view.findViewById(R.id.poseSelect)
        graphRangeSelect = view.findViewById(R.id.graphRangeSelect)
        passivityTimerText = view.findViewById(R.id.passivityTimerText)

        gyroGraph.setSeries(emptyList(), listOf("gx", "gy", "gz"))
        gyroGraph.setHorizontalLines(
            listOf(
                GraphView.HorizontalLine(700f, 0x66D62728),
                GraphView.HorizontalLine(-700f, 0x66D62728)
            )
        )
        accelGraph.setSeries(emptyList(), listOf("ax", "ay", "az"))
        applyAccelBands(selectedGesture)
        accelGraph.setFixedRange(-10f, 10f)

        val graphOptions = graphDisplayModes.map { it.label }
        graphRangeSelect.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, graphOptions)
        graphRangeSelect.setSelection(graphDisplayMode.ordinal)
        graphRangeSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                graphDisplayMode = graphDisplayModes.getOrElse(position) { GraphDisplayMode.LAST_SECOND }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                graphDisplayMode = GraphDisplayMode.LAST_SECOND
            }
        }

        val options = gestures.map { it.name }
        poseSelect.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, options)
        poseSelect.setSelection(0)
        poseSelect.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                selectedGesture = gestures.getOrNull(position) ?: gestures.first()
                applyAccelBands(selectedGesture)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {
                selectedGesture = gestures.first()
                applyAccelBands(selectedGesture)
            }
        }

        view.findViewById<Button>(R.id.startBtn).setOnClickListener {
            main.startServer()
            statusText.text = "Server running on port 8080"
        }

        view.findViewById<Button>(R.id.stopBtn).setOnClickListener {
            main.stopServer()
            statusText.text = "Server stopped"
            modeText.text = "Mode: waiting | No gesture detected"
            pointsText.text = "Blue: 0 | Red: 0 | Freq: -"
            gyroGraph.setSeries(emptyList(), listOf("gx", "gy", "gz"))
            accelGraph.setSeries(emptyList(), listOf("ax", "ay", "az"))
            gyroTsText.text = "ts: -"
            accelTsText.text = "ts: -"
        }

        view.findViewById<Button>(R.id.exportBtn).setOnClickListener {
            main.exportAllCsv()
        }

        if (main.server != null) {
            statusText.text = "Server running on port 8080"
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(uiUpdater)
    }

    override fun onPause() {
        handler.removeCallbacks(uiUpdater)
        super.onPause()
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

    private fun updatePassivityTimer(server: ImuHttpServer?) {
        val (redDeadline, blueDeadline) = server?.getPassivityDeadlines() ?: (0L to 0L)
        val now = System.currentTimeMillis()
        val activeDeadline = when {
            redDeadline > 0 -> redDeadline
            blueDeadline > 0 -> blueDeadline
            else -> 0L
        }
        if (activeDeadline > 0 && activeDeadline > now) {
            val remainingSec = (activeDeadline - now) / 1000.0
            val who = if (redDeadline > 0) "Red" else "Blue"
            passivityTimerText.text = "Passivity $who: ${"%.1f".format(remainingSec)}s"
            passivityTimerText.visibility = View.VISIBLE
        } else {
            passivityTimerText.visibility = View.GONE
        }
    }

    private fun getLastTwentySecondSamples(server: ImuHttpServer?): List<ImuSample> {
        val allSamples = server?.getAllSamples().orEmpty()
        if (allSamples.isEmpty()) {
            return emptyList()
        }

        val newestTs = allSamples.maxOf { it.ts }
        val threshold = newestTs - 20_000L
        return allSamples.filter { it.ts >= threshold }
    }

    private fun formatReceiveFrequency(server: ImuHttpServer?): String {
        val frequencyHz = server?.getReceiveRequestFrequencyHz() ?: 0.0
        if (frequencyHz == 0.0) {
            return "-"
        }
        if (frequencyHz < 0.0) {
            return "..."
        }
        return "${"%.1f".format(frequencyHz)} Hz"
    }
}
