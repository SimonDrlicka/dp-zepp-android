package com.example.zepp_gestures

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment

/**
 * Placeholder for Phase 2 (Composite sequences). The UI navigation is
 * wired up but the actual test logic will be added later.
 */
class TestingPhase2Fragment : Fragment() {

    private val main: MainActivity get() = activity as MainActivity

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
    }
}
