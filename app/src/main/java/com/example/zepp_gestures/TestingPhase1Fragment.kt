package com.example.zepp_gestures

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TestingPhase1Fragment : Fragment() {

    private val main: MainActivity get() = activity as MainActivity
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var gestureSpinner: Spinner
    private lateinit var attemptsInput: EditText
    private lateinit var startStopBtn: Button
    private lateinit var skipBtn: Button
    private lateinit var statusText: TextView
    private lateinit var progressText: TextView
    private lateinit var filesList: TextView
    private lateinit var backBtn: Button
    private lateinit var accelGraph: GraphView
    private lateinit var accelTsText: TextView

    private var totalAttempts: Int = DEFAULT_ATTEMPTS
    private val savedFiles = mutableListOf<String>()

    companion object {
        private const val DEFAULT_ATTEMPTS = 20
        private const val GRAPH_REFRESH_MS = 300L
    }

    private val graphUpdater = object : Runnable {
        override fun run() {
            if (!isAdded) return
            val samples = main.getTestingService()?.getLiveSamples().orEmpty()
            accelGraph.setSeries(
                samples.map {
                    GraphView.Sample(
                        it.ts,
                        floatArrayOf(it.ax.toFloat(), it.ay.toFloat(), it.az.toFloat())
                    )
                },
                listOf("ax", "ay", "az")
            )
            updateAccelTsText(samples)
            handler.postDelayed(this, GRAPH_REFRESH_MS)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_testing_phase1, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        gestureSpinner = view.findViewById(R.id.testingGestureSpinner)
        attemptsInput = view.findViewById(R.id.testingAttemptsInput)
        startStopBtn = view.findViewById(R.id.testingStartStopBtn)
        skipBtn = view.findViewById(R.id.testingSkipBtn)
        statusText = view.findViewById(R.id.testingStatusText)
        progressText = view.findViewById(R.id.testingProgressText)
        filesList = view.findViewById(R.id.testingFilesList)
        backBtn = view.findViewById(R.id.testingPhase1BackBtn)
        accelGraph = view.findViewById(R.id.testingAccelGraph)
        accelTsText = view.findViewById(R.id.testingAccelTsText)

        accelGraph.setSeries(emptyList(), listOf("ax", "ay", "az"))
        accelGraph.setMinimumRange(-10f, 10f)

        gestureSpinner.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            TestingGestures.ALL.map { "${it.id}. ${it.displayName}" }
        )

        gestureSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                applyExpectedGestureBands()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        applyExpectedGestureBands()

        backBtn.setOnClickListener {
            main.stopTestingServer()
            main.showTestingPhaseSelect()
        }

        startStopBtn.setOnClickListener {
            if (main.isTestingRunning()) {
                main.stopTestingServer()
                onTestStoppedManually()
            } else {
                startTest()
            }
        }

        skipBtn.setOnClickListener {
            if (main.isTestingRunning()) {
                main.skipCurrentTestingAttempt()

                skipBtn.isEnabled = false
            }
        }

        refreshUiToIdle()
    }

    override fun onResume() {
        super.onResume()

        if (main.isTestingRunning()) {
            main.bindTestingCallbacks(
                onAttemptCompleted = ::handleAttemptCompleted,
                onTestFinished = ::handleTestFinished,
                onProgressChanged = ::handleProgressChanged
            )
            switchUiToRunning()
        }
        handler.post(graphUpdater)
    }

    override fun onPause() {

        main.unbindTestingCallbacks()
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    private fun expectedGesture(): TestingGesture =
        TestingGestures.ALL[gestureSpinner.selectedItemPosition]

    private fun startTest() {
        totalAttempts = parseAttempts(attemptsInput.text?.toString())
        if (totalAttempts <= 0) {
            Toast.makeText(requireContext(), "Počet pokusov musí byť > 0", Toast.LENGTH_SHORT).show()
            return
        }

        val expected = expectedGesture()
        savedFiles.clear()
        renderFiles()

        val started = main.startTestingServer(
            attemptCount = totalAttempts,
            onAttemptCompleted = ::handleAttemptCompleted,
            onTestFinished = ::handleTestFinished,
            onProgressChanged = ::handleProgressChanged
        )

        if (!started) {
            Toast.makeText(requireContext(), "Server sa nepodarilo spustiť", Toast.LENGTH_SHORT).show()
            return
        }

        statusText.text = "Server beží na porte 8080. Očakávané gesto: ${expected.displayName}."
        progressText.text = "Pokus: 0 / $totalAttempts"
        switchUiToRunning()
    }

    private fun parseAttempts(text: String?): Int =
        text?.trim()?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: DEFAULT_ATTEMPTS

    private fun handleAttemptCompleted(
        samples: List<ImuSample>,
        attemptIndex: Int,
        detectedAtTs: Long?,
        detectedGesture: TestingGesture?
    ) {

        val expected = expectedGesture()
        val attemptStr = attemptIndex.toString().padStart(2, '0')
        val expectedTag = "${expected.id}-${expected.slug}"
        val detectedTag = when {
            detectedAtTs == null -> "skipped"
            detectedGesture != null -> "${detectedGesture.id}-${detectedGesture.slug}"
            else -> "unknown"
        }
        val prefix = "test_p1_${expectedTag}_${attemptStr}_${detectedTag}"
        handler.post {
            val savedName = main.exportTestingCsvAndReturnName(
                samples = samples,
                prefix = prefix,
                detectedAtTs = detectedAtTs,
                detectedGestureId = detectedGesture?.id
            )
            if (savedName != null) {
                savedFiles.add(savedName)
                renderFiles()
            }
        }
    }

    private fun handleTestFinished() {
        handler.post {
            statusText.text = "Test dokončený."
            switchUiToIdle()
            Toast.makeText(requireContext(), "Test dokončený.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleProgressChanged(
        completed: Int,
        total: Int,
        capturingTail: Boolean,
        paused: Boolean
    ) {
        handler.post {
            progressText.text = "Pokus: $completed / $total"
            statusText.text = when {
                capturingTail -> "Detekcia gesta — zaznamenávam ďalšiu sekundu…"
                paused -> "Krátka pauza pred ďalším pokusom…"
                else -> "Server beží — čakám na akékoľvek gesto…"
            }

            skipBtn.isEnabled = main.isTestingRunning() && !capturingTail && !paused
        }
    }

    private fun onTestStoppedManually() {
        statusText.text = "Test zastavený používateľom."
        switchUiToIdle()
    }

    private fun switchUiToRunning() {
        startStopBtn.text = "Ukončiť test"
        gestureSpinner.isEnabled = false
        attemptsInput.isEnabled = false
        skipBtn.isEnabled = true
    }

    private fun switchUiToIdle() {
        startStopBtn.text = "Spustiť test"
        gestureSpinner.isEnabled = true
        attemptsInput.isEnabled = true
        skipBtn.isEnabled = false
    }

    private fun refreshUiToIdle() {
        if (main.isTestingRunning()) {
            switchUiToRunning()
        } else {
            switchUiToIdle()
            statusText.text = "Server pripravený."
            progressText.text = "Pokus: 0 / ${parseAttempts(attemptsInput.text?.toString())}"
        }
    }

    private fun renderFiles() {
        filesList.text = if (savedFiles.isEmpty()) {
            "(žiadne)"
        } else {
            savedFiles.joinToString("\n")
        }
    }

    private fun applyExpectedGestureBands() {
        val expected = expectedGesture()
        val def = GestureConfig.gestures.firstOrNull { it.name == expected.internalName }
        if (def == null) {
            accelGraph.setBands(emptyList())
            return
        }
        val alpha = 0x33
        val colors = GraphView.DEFAULT_SERIES_COLORS
        accelGraph.setBands(
            listOf(
                GraphView.Band(0, def.bands.axMin.toFloat(), def.bands.axMax.toFloat(), withAlpha(colors[0], alpha)),
                GraphView.Band(1, def.bands.ayMin.toFloat(), def.bands.ayMax.toFloat(), withAlpha(colors[1], alpha)),
                GraphView.Band(2, def.bands.azMin.toFloat(), def.bands.azMax.toFloat(), withAlpha(colors[2], alpha))
            )
        )
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    private fun updateAccelTsText(samples: List<ImuSample>) {
        if (samples.isEmpty()) {
            accelTsText.text = "ts: -"
            return
        }
        val minTs = samples.minOf { it.ts }
        val maxTs = samples.maxOf { it.ts }
        val midTs = minTs + (maxTs - minTs) / 2
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        accelTsText.text =
            "ts: ${fmt.format(Date(minTs))} | ${fmt.format(Date(midTs))} | ${fmt.format(Date(maxTs))}"
    }
}
