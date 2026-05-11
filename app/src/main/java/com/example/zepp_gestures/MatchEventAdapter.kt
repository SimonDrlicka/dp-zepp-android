package com.example.zepp_gestures

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MatchEventAdapter : RecyclerView.Adapter<MatchEventAdapter.ViewHolder>() {

    private var events: List<MatchEvent> = emptyList()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun submitList(newEvents: List<MatchEvent>) {
        events = newEvents
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = events.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val tv = TextView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            textSize = 14f
            setPadding(0, 4, 0, 4)
        }
        return ViewHolder(tv)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = events[position]
        (holder.itemView as TextView).text =
            "${fmt.format(Date(event.ts))}  ${displayLabel(event.event)}"
    }

    /**
     * Display-only rename of the activation-gesture identifier. The
     * underlying [MatchEvent.event] (and therefore any CSV export or
     * downstream tooling) keeps the canonical "Hand up" string so the
     * recognition pipeline and external parsers stay in sync.
     */
    private fun displayLabel(eventText: String): String =
        eventText.replace("Hand up", "Rise arm")

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
