package com.example.zepp_gestures

import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MatchEventAdapter(

    private val onDeleteRequest: (MatchEvent) -> Unit = {}
) : ListAdapter<MatchEvent, MatchEventAdapter.ViewHolder>(DIFF) {

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density

        val text = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            textSize = 14f
            setPadding(0, (4 * density).toInt(), 0, (4 * density).toInt())
        }

        val deleteBtn = Button(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (8 * density).toInt()
            }
            this.text = "✕"
            textSize = 14f
            minWidth = (40 * density).toInt()
            minimumWidth = (40 * density).toInt()

            val pv = (4 * density).toInt()
            val ph = (10 * density).toInt()
            setPadding(ph, pv, ph, pv)

            setTextColor("#a32626".toColorInt())
            contentDescription = "Delete event"
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            addView(text)
            addView(deleteBtn)
        }

        return ViewHolder(row, text, deleteBtn)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val event = getItem(position)
        holder.text.text = "${fmt.format(Date(event.ts))}  ${event.event}"
        holder.text.paintFlags = if (event.invalidated) {
            holder.text.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
        } else {
            holder.text.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
        }
        holder.text.alpha = if (event.invalidated) 0.5f else 1f
        holder.deleteBtn.visibility = if (event.invalidated) View.INVISIBLE else View.VISIBLE
        holder.deleteBtn.setOnClickListener(
            if (event.invalidated) null else View.OnClickListener { onDeleteRequest(event) }
        )
    }

    class ViewHolder(
        row: View,
        val text: TextView,
        val deleteBtn: Button
    ) : RecyclerView.ViewHolder(row)

    companion object {

        private val DIFF = object : DiffUtil.ItemCallback<MatchEvent>() {
            override fun areItemsTheSame(old: MatchEvent, new: MatchEvent): Boolean =
                old.ts == new.ts && old.event == new.event
            override fun areContentsTheSame(old: MatchEvent, new: MatchEvent): Boolean =
                old == new
        }
    }
}
