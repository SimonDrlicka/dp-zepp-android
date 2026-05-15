package com.example.zepp_gestures.composite

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.zepp_gestures.GestureMode
import com.example.zepp_gestures.ImuSample
import com.example.zepp_gestures.MainActivity
import com.example.zepp_gestures.MatchEvent
import com.example.zepp_gestures.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CompositeTestFragment : Fragment() {

    private val main: MainActivity get() = activity as MainActivity
    private val ui = Handler(Looper.getMainLooper())

    private lateinit var scenario: CompositeScenario
    private var totalAttempts: Int = 0
    private var currentAttemptIndex: Int = 0
    private val attemptOutcomes: MutableList<AttemptOutcome> = mutableListOf()

    private var runner: CompositeTestRunner? = null
    private var lastSampleTs: Long = 0L
    private var attemptStartSampleCount: Int = 0

    private var pendingOutcome: AttemptOutcome? = null
    private var pendingSamples: List<ImuSample> = emptyList()

    private lateinit var runTimestamp: String

    private lateinit var runningSection: LinearLayout
    private lateinit var resultSection: LinearLayout
    private lateinit var summarySection: LinearLayout

    private lateinit var titleText: TextView
    private lateinit var attemptCounter: TextView

    private lateinit var currentStepText: TextView
    private lateinit var mismatchBanner: TextView
    private lateinit var stepListText: TextView
    private lateinit var currentModeText: TextView
    private lateinit var currentScoreText: TextView
    private lateinit var endAttemptBtn: Button
    private lateinit var endTestBtn: Button

    private lateinit var resultStatusText: TextView
    private lateinit var resultStepListText: TextView
    private lateinit var resultScoreText: TextView
    private lateinit var resultModeText: TextView
    private lateinit var resultReasonText: TextView
    private lateinit var resultFilesText: TextView
    private lateinit var saveNextBtn: Button
    private lateinit var discardNextBtn: Button
    private lateinit var resultEndTestBtn: Button

    private lateinit var summaryHeader: TextView
    private lateinit var summaryList: TextView
    private lateinit var summaryPath: TextView
    private lateinit var summaryDoneBtn: Button

    private val pollRunnable = object : Runnable {
        override fun run() {
            tickRunner()
            ui.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    companion object {
        private const val ARG_SCENARIO_ID = "scenarioId"
        private const val ARG_ATTEMPT_COUNT = "attemptCount"
        private const val POLL_INTERVAL_MS: Long = 200L

        fun newInstance(scenarioId: String, attemptCount: Int): CompositeTestFragment {
            return CompositeTestFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_SCENARIO_ID, scenarioId)
                    putInt(ARG_ATTEMPT_COUNT, attemptCount)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_composite_test, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val scenarioId = arguments?.getString(ARG_SCENARIO_ID).orEmpty()
        scenario = CompositeScenarios.byId(scenarioId)
            ?: throw IllegalStateException("Unknown scenario id: $scenarioId")
        totalAttempts = arguments?.getInt(ARG_ATTEMPT_COUNT, 5) ?: 5
        runTimestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

        bindViews(view)

        view.findViewById<Button>(R.id.compositeBackBtn).setOnClickListener {
            stopAndExit()
        }
        endAttemptBtn.setOnClickListener { onEndAttemptClicked() }
        endTestBtn.setOnClickListener { stopAndExit() }
        saveNextBtn.setOnClickListener { onSaveAndContinue() }
        discardNextBtn.setOnClickListener { onDiscardAndContinue() }
        resultEndTestBtn.setOnClickListener {

            clearPending()
            showSummary()
        }
        summaryDoneBtn.setOnClickListener {
            main.showTestingPhaseSelect()
        }

        titleText.text = "Scenár ${scenario.displayName}"

        if (!main.isCompositeRunning()) {
            val started = main.startCompositeServer()
            if (!started) {
                Toast.makeText(requireContext(), "Server sa nepodarilo spustiť", Toast.LENGTH_SHORT).show()
                main.showTestingPhaseSelect()
                return
            }
        }

        startNextAttempt()
    }

    override fun onDestroyView() {
        ui.removeCallbacks(pollRunnable)
        main.unbindCompositeMatchEventListener()
        super.onDestroyView()
    }

    private fun bindViews(view: View) {
        runningSection = view.findViewById(R.id.compositeRunningSection)
        resultSection = view.findViewById(R.id.compositeResultSection)
        summarySection = view.findViewById(R.id.compositeSummarySection)

        titleText = view.findViewById(R.id.compositeScenarioTitle)
        attemptCounter = view.findViewById(R.id.compositeAttemptCounter)

        currentStepText = view.findViewById(R.id.compositeCurrentStep)
        mismatchBanner = view.findViewById(R.id.compositeMismatchBanner)
        stepListText = view.findViewById(R.id.compositeStepList)
        currentModeText = view.findViewById(R.id.compositeCurrentMode)
        currentScoreText = view.findViewById(R.id.compositeCurrentScore)
        endAttemptBtn = view.findViewById(R.id.compositeEndAttemptBtn)
        endTestBtn = view.findViewById(R.id.compositeEndTestBtn)

        resultStatusText = view.findViewById(R.id.compositeResultStatus)
        resultStepListText = view.findViewById(R.id.compositeResultStepList)
        resultScoreText = view.findViewById(R.id.compositeResultScore)
        resultModeText = view.findViewById(R.id.compositeResultMode)
        resultReasonText = view.findViewById(R.id.compositeResultReason)
        resultFilesText = view.findViewById(R.id.compositeResultFiles)
        saveNextBtn = view.findViewById(R.id.compositeSaveNextBtn)
        discardNextBtn = view.findViewById(R.id.compositeDiscardNextBtn)
        resultEndTestBtn = view.findViewById(R.id.compositeResultEndTestBtn)

        summaryHeader = view.findViewById(R.id.compositeSummaryHeader)
        summaryList = view.findViewById(R.id.compositeSummaryList)
        summaryPath = view.findViewById(R.id.compositeSummaryPath)
        summaryDoneBtn = view.findViewById(R.id.compositeSummaryDoneBtn)
    }

    private fun startNextAttempt() {
        if (currentAttemptIndex >= totalAttempts) {
            showSummary()
            return
        }
        currentAttemptIndex++
        attemptCounter.text = "Pokus $currentAttemptIndex z $totalAttempts"

        main.resetCompositeForNextAttempt()

        attemptStartSampleCount = main.getCompositeService()?.getSessionSamples()?.size ?: 0

        val newRunner = CompositeTestRunner(scenario, currentAttemptIndex)
        runner = newRunner

        lastSampleTs = 0L

        main.bindCompositeMatchEventListener { event ->
            ui.post { handleMatchEvent(event) }
        }

        showRunningSection()
        renderRunningUi()
        ui.removeCallbacks(pollRunnable)
        ui.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    private fun handleMatchEvent(event: MatchEvent) {
        val r = runner ?: return
        if (r.terminationReason != null) return
        if (r.startTimestamp == 0L) {
            r.start(event.ts)
        }
        r.onMatchEvent(event)
        renderRunningUi()
    }

    private fun tickRunner() {
        val r = runner ?: return
        val svc = main.getCompositeService() ?: return
        val samples = svc.getSessionSamples()
        if (samples.isNotEmpty()) {
            lastSampleTs = samples.last().ts

            if (r.startTimestamp == 0L) {
                r.start(samples.first().ts)
            }
        }
        if (lastSampleTs > 0L && r.onTick(lastSampleTs)) {
            finishAttempt()
        }
    }

    private fun onEndAttemptClicked() {
        val r = runner ?: return
        r.terminate(CompositeTestRunner.TerminationReason.MANUAL, lastSampleTs)
        finishAttempt()
    }

    private fun finishAttempt() {
        val r = runner ?: return
        ui.removeCallbacks(pollRunnable)
        main.unbindCompositeMatchEventListener()

        val svc = main.getCompositeService()
        val (blue, red) = svc?.getPoints() ?: (0 to 0)
        val mode = svc?.getMode() ?: GestureMode.WAITING

        val outcome = r.buildOutcome(
            actualFinalScore = Score(red = red, blue = blue),
            actualFinalMode = mode
        )

        val allSamples = svc?.getSessionSamples().orEmpty()
        val attemptSamples = if (attemptStartSampleCount < allSamples.size) {
            allSamples.subList(attemptStartSampleCount, allSamples.size).toList()
        } else allSamples.toList()

        pendingOutcome = outcome
        pendingSamples = attemptSamples
        showResultSection(outcome)
    }

    private fun onSaveAndContinue() {
        val outcome = pendingOutcome ?: return
        val samples = pendingSamples
        val (csvName, jsonName) = main.exportCompositeAttemptFiles(
            scenario = scenario,
            attemptNumber = outcome.attemptNumber,
            samples = samples,
            outcome = outcome,
            runTimestamp = runTimestamp
        )
        if (csvName == null && jsonName == null) {
            Toast.makeText(requireContext(), "Uloženie zlyhalo", Toast.LENGTH_SHORT).show()
            return
        }
        attemptOutcomes.add(outcome)
        clearPending()
        advancePastResult()
    }

    private fun onDiscardAndContinue() {
        clearPending()
        advancePastResult()
    }

    private fun advancePastResult() {
        if (currentAttemptIndex >= totalAttempts) showSummary() else startNextAttempt()
    }

    private fun clearPending() {
        pendingOutcome = null
        pendingSamples = emptyList()
    }

    private fun stopAndExit() {
        ui.removeCallbacks(pollRunnable)
        main.unbindCompositeMatchEventListener()
        main.stopCompositeServer()
        main.showTestingPhaseSelect()
    }

    private fun showRunningSection() {
        runningSection.visibility = View.VISIBLE
        resultSection.visibility = View.GONE
        summarySection.visibility = View.GONE
    }

    private fun showResultSection(outcome: AttemptOutcome) {
        runningSection.visibility = View.GONE
        resultSection.visibility = View.VISIBLE
        summarySection.visibility = View.GONE

        if (outcome.success) {
            resultStatusText.text = "Pokus ${outcome.attemptNumber} z $totalAttempts: ✓ ÚSPEŠNÝ"
            resultStatusText.setBackgroundColor(0xFF2E7D32.toInt())
        } else {
            resultStatusText.text = "Pokus ${outcome.attemptNumber} z $totalAttempts: ✗ NEÚSPEŠNÝ"
            resultStatusText.setBackgroundColor(0xFFC62828.toInt())
        }
        resultStatusText.setTextColor(0xFFFFFFFF.toInt())
        resultStepListText.text = renderResultStepList(outcome)
        resultScoreText.text = formatScoreLine(
            "Skóre",
            outcome.actualFinalScore,
            outcome.expectedFinalScore,
            ignoreExpected = scenario.expectedGestureIds == listOf(8)
        )
        resultModeText.text =
            "Režim: ${outcome.actualFinalMode.name} (očakávaný: ${outcome.expectedFinalMode.name})"
        resultReasonText.text =
            outcome.failureReason?.let { "Dôvod zlyhania: $it" } ?: ""

        resultFilesText.text = ""

        val isLast = currentAttemptIndex >= totalAttempts
        saveNextBtn.text = if (isLast) "Uložiť a zobraziť súhrn" else "Uložiť a pokračovať"
        discardNextBtn.text = if (isLast) "Zahodiť a zobraziť súhrn" else "Zahodiť a pokračovať"
    }

    private fun showSummary() {
        ui.removeCallbacks(pollRunnable)
        main.unbindCompositeMatchEventListener()
        main.stopCompositeServer()

        runningSection.visibility = View.GONE
        resultSection.visibility = View.GONE
        summarySection.visibility = View.VISIBLE

        val successes = attemptOutcomes.count { it.success }
        val total = attemptOutcomes.size
        val pct = if (total == 0) 0 else (successes * 100) / total
        summaryHeader.text =
            "Výsledky: $successes/$total úspešných ($pct%)"

        summaryList.text = attemptOutcomes.joinToString("\n") { o ->
            if (o.success) {
                "Pokus ${o.attemptNumber}: ✓ Úspešný"
            } else {
                val reason = o.failureReason ?: "neznámy dôvod"
                "Pokus ${o.attemptNumber}: ✗ $reason"
            }
        }

        summaryPath.text = "Cesta uloženia:\nDownload/testing/composite/${scenario.id}/"
    }

    private fun renderRunningUi() {
        val r = runner ?: return
        val svc = main.getCompositeService()
        val (blue, red) = svc?.getPoints() ?: (0 to 0)
        val mode = svc?.getMode() ?: GestureMode.WAITING

        if (r.nextExpectedIndex >= scenario.expectedGestureIds.size) {
            currentStepText.text = "Hotovo — sekvencia ukončená"
        } else {
            val expectedId = scenario.expectedGestureIds[r.nextExpectedIndex]
            val stepNum = r.nextExpectedIndex + 1
            val total = scenario.expectedGestureIds.size
            currentStepText.text =
                "Krok $stepNum z $total: ▶ ${compositeGestureName(expectedId).uppercase()}"
        }

        if (r.lastDetectionWasMismatch && r.lastDetectedId != null) {
            val expectedId = scenario.expectedGestureIds.getOrNull(
                r.actualGestures.lastOrNull()?.stepIndex ?: -1
            )
            val expectedName = expectedId?.let { compositeGestureName(it) } ?: "(žiadne)"
            mismatchBanner.text =
                "✗ Detegované: ${compositeGestureName(r.lastDetectedId!!)}, ale očakávané: $expectedName"
            mismatchBanner.visibility = View.VISIBLE
        } else {
            mismatchBanner.visibility = View.GONE
        }

        stepListText.text = renderRunningStepList(r)
        currentModeText.text = "Režim: ${mode.name}"
        currentScoreText.text = "Skóre: red $red  |  blue $blue"
    }

    private fun renderRunningStepList(r: CompositeTestRunner): String {
        val sb = StringBuilder()
        scenario.expectedGestureIds.forEachIndexed { i, expectedId ->
            val actual = r.actualGestures.getOrNull(i)
            val name = compositeGestureName(expectedId)
            val line = when {
                actual != null && actual.matchedExpected ->
                    "✓ $name"
                actual != null && !actual.matchedExpected ->
                    "✗ očakávané: $name (detegované: ${compositeGestureName(actual.gestureId)})"
                i == r.nextExpectedIndex && r.terminationReason == null ->
                    "▶ $name"
                else ->
                    "○ $name"
            }
            sb.append(line)
            if (i != scenario.expectedGestureIds.lastIndex) sb.append("\n")
        }

        if (r.actualGestures.size > scenario.expectedGestureIds.size) {
            sb.append("\n")
            for (i in scenario.expectedGestureIds.size until r.actualGestures.size) {
                val det = r.actualGestures[i]
                sb.append("\n+ navyše: ${compositeGestureName(det.gestureId)}")
            }
        }
        return sb.toString()
    }

    private fun renderResultStepList(outcome: AttemptOutcome): String {
        val sb = StringBuilder()
        outcome.expectedGestures.forEachIndexed { i, expectedId ->
            val actual = outcome.actualGestures.getOrNull(i)
            val name = compositeGestureName(expectedId)
            val line = when {
                actual == null ->
                    "○ $name (chýba)"
                actual.gestureId == expectedId ->
                    "✓ $name"
                else ->
                    "✗ $name (detegované: ${compositeGestureName(actual.gestureId)})"
            }
            sb.append(line)
            if (i != outcome.expectedGestures.lastIndex) sb.append("\n")
        }
        if (outcome.actualGestures.size > outcome.expectedGestures.size) {
            sb.append("\n")
            for (i in outcome.expectedGestures.size until outcome.actualGestures.size) {
                val det = outcome.actualGestures[i]
                sb.append("\n+ navyše: ${compositeGestureName(det.gestureId)}")
            }
        }
        return sb.toString()
    }

    private fun formatScoreLine(
        label: String,
        actual: Score,
        expected: Score,
        ignoreExpected: Boolean
    ): String {
        return if (ignoreExpected) {
            "$label: red ${actual.red}  |  blue ${actual.blue}  (očakávané: irelevantné)"
        } else {
            "$label: red ${actual.red}  |  blue ${actual.blue}  " +
                "(očakávané: red ${expected.red}  |  blue ${expected.blue})"
        }
    }
}
