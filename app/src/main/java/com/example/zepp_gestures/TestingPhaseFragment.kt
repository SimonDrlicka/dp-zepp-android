package com.example.zepp_gestures

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment

class TestingPhaseFragment : Fragment() {

    private val main: MainActivity get() = activity as MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_testing_phase, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.testingPhaseBackBtn).setOnClickListener {
            main.backToModeSelect()
        }
        view.findViewById<Button>(R.id.testingPhase1Btn).setOnClickListener {
            main.showTestingPhase1()
        }
        view.findViewById<Button>(R.id.testingPhase2Btn).setOnClickListener {
            main.showTestingPhase2()
        }
    }
}
