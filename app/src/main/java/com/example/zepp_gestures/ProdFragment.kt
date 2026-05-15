package com.example.zepp_gestures

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProdFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private val eventTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private lateinit var statusText: TextView
    private lateinit var pointsText: TextView
    private lateinit var passivityTimerText: TextView
    private lateinit var eventList: RecyclerView
    private val adapter = MatchEventAdapter(onDeleteRequest = ::confirmDeleteEvent)

    private val main: MainActivity get() = activity as MainActivity

    private var lastEventCount = 0

    private val uiUpdater = object : Runnable {
        override fun run() {
            if (!isAdded) return
            val service = main.service
            val (blue, red) = service?.getPoints() ?: (0 to 0)
            pointsText.text = "Blue: $blue | Red: $red"

            updatePassivityTimer(service)

            val events = service?.getMatchEvents().orEmpty()
            if (events.size != lastEventCount) {
                adapter.submitList(events)
                lastEventCount = events.size
                if (events.isNotEmpty()) {
                    eventList.scrollToPosition(events.size - 1)
                }
            }

            handler.postDelayed(this, 300)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_prod, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        statusText = view.findViewById(R.id.statusText)
        pointsText = view.findViewById(R.id.pointsText)
        passivityTimerText = view.findViewById(R.id.passivityTimerText)
        eventList = view.findViewById(R.id.eventList)

        eventList.layoutManager = LinearLayoutManager(requireContext())
        eventList.adapter = adapter

        view.findViewById<Button>(R.id.startBtn).setOnClickListener {
            val prod = main.selectedProdMode == true

            adapter.submitList(emptyList())
            lastEventCount = 0
            pointsText.text = "Blue: 0 | Red: 0"
            passivityTimerText.visibility = View.GONE
            main.startServer(prodMode = prod)
            statusText.text = "Match in progress (${if (prod) "prod" else "debug"})"
        }

        view.findViewById<Button>(R.id.stopBtn).setOnClickListener {
            main.stopServer()
            statusText.text = "Match ended"

        }

        if (main.server != null) {
            statusText.text = "Match in progress"
        }
    }

    override fun onResume() {
        super.onResume()
        lastEventCount = 0
        handler.post(uiUpdater)
    }

    override fun onPause() {
        handler.removeCallbacks(uiUpdater)
        super.onPause()
    }

    private fun confirmDeleteEvent(event: MatchEvent) {
        val service = main.service ?: return
        val timeStr = eventTimeFmt.format(Date(event.ts))
        AlertDialog.Builder(requireContext())
            .setTitle("Vymazať event?")
            .setMessage("$timeStr  ${event.event}")
            .setPositiveButton("Vymazať") { _, _ ->
                if (service.deleteMatchEvent(event)) {

                    val updated = service.getMatchEvents()
                    adapter.submitList(updated)
                    lastEventCount = updated.size
                    val (blue, red) = service.getPoints()
                    pointsText.text = "Blue: $blue | Red: $red"
                }
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    private fun updatePassivityTimer(service: GestureRecognitionService?) {
        val (redDeadline, blueDeadline) = service?.getPassivityDeadlines() ?: (0L to 0L)
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
}
