package com.example.zepp_gestures

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.zepp_gestures.composite.CompositeScenario
import com.example.zepp_gestures.composite.CompositeScenarios
import com.example.zepp_gestures.composite.compositeGestureName

class TestingPhase2Fragment : Fragment() {

    private val main: MainActivity get() = activity as MainActivity

    private lateinit var attemptsInput: EditText
    private lateinit var scenarioList: LinearLayout

    companion object {
        private const val DEFAULT_ATTEMPTS = 5
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_testing_phase2, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.testingPhase2BackBtn).setOnClickListener {
            main.showTestingPhaseSelect()
        }

        attemptsInput = view.findViewById(R.id.compositeAttemptsInput)
        scenarioList = view.findViewById(R.id.compositeScenarioList)

        renderScenarioList()
    }

    private fun renderScenarioList() {
        scenarioList.removeAllViews()
        val inflater = LayoutInflater.from(requireContext())
        for (scenario in CompositeScenarios.ALL) {
            val row = inflater.inflate(R.layout.item_composite_scenario, scenarioList, false)
            row.findViewById<TextView>(R.id.scenarioTitle).text = scenario.displayName
            row.findViewById<TextView>(R.id.scenarioSequence).text =
                buildSequencePreview(scenario)
            row.findViewById<TextView>(R.id.scenarioFinalScore).text =
                buildExpectedFinalLine(scenario)
            row.findViewById<Button>(R.id.scenarioRunBtn).setOnClickListener {
                onRunScenario(scenario)
            }
            scenarioList.addView(row)
        }
    }

    private fun buildSequencePreview(scenario: CompositeScenario): String =
        scenario.expectedGestureIds
            .joinToString(separator = "  →  ") { "${it}. ${compositeGestureName(it)}" }

    private fun buildExpectedFinalLine(scenario: CompositeScenario): String {
        if (scenario.expectedGestureIds == listOf(8)) {
            return "Finálne skóre: irelevantné  •  režim: ${scenario.expectedFinalMode.name}"
        }
        val s = scenario.expectedFinalScore
        return "Finálne skóre: red ${s.red}  |  blue ${s.blue}  •  režim: ${scenario.expectedFinalMode.name}"
    }

    private fun onRunScenario(scenario: CompositeScenario) {
        val attempts = parseAttempts(attemptsInput.text?.toString())
        if (attempts <= 0) {
            Toast.makeText(requireContext(), "Počet pokusov musí byť > 0", Toast.LENGTH_SHORT).show()
            return
        }
        main.showCompositeTest(scenario.id, attempts)
    }

    private fun parseAttempts(text: String?): Int =
        text?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_ATTEMPTS
}
