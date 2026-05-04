package com.example.zepp_gestures

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment

class ModeSelectFragment : Fragment() {

    private val main: MainActivity get() = activity as MainActivity

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_mode_select, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<Button>(R.id.modeDebugBtn).setOnClickListener {
            main.onModeSelected(prodMode = false)
        }
        view.findViewById<Button>(R.id.modeProdBtn).setOnClickListener {
            main.onModeSelected(prodMode = true)
        }
    }
}
