package com.example.zepp_gestures

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class GraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class Sample(val ts: Long, val values: FloatArray)
    data class Band(val seriesIndex: Int, val min: Float, val max: Float, val color: Int)

    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3C3C3C")
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E0E0E0")
        strokeWidth = dp(1f)
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E1E1E")
        textSize = dp(12f)
    }
    private val seriesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2f)
        style = Paint.Style.STROKE
    }

    private val seriesColors = DEFAULT_SERIES_COLORS

    private var samples: List<Sample> = emptyList()
    private var labels: List<String> = emptyList()
    private var bands: List<Band> = emptyList()
    private var fixedMin: Float? = null
    private var fixedMax: Float? = null

    fun setSeries(samples: List<Sample>, labels: List<String>) {
        this.samples = samples
        this.labels = labels
        invalidate()
    }

    fun setBands(bands: List<Band>) {
        this.bands = bands
        invalidate()
    }

    fun setFixedRange(min: Float, max: Float) {
        fixedMin = min
        fixedMax = max
        invalidate()
    }

    fun clearFixedRange() {
        fixedMin = null
        fixedMax = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft.toFloat() + dp(36f)
        val right = width.toFloat() - paddingRight.toFloat() - dp(8f)
        val top = paddingTop.toFloat() + dp(12f)
        val bottom = height.toFloat() - paddingBottom.toFloat() - dp(28f)

        if (right <= left || bottom <= top) return

        // Grid
        val gridLines = 4
        for (i in 0..gridLines) {
            val y = top + (bottom - top) * (i / gridLines.toFloat())
            canvas.drawLine(left, y, right, y, gridPaint)
        }

        canvas.drawRect(left, top, right, bottom, axisPaint)

        if (samples.isEmpty()) {
            canvas.drawText("No data", left, top + dp(16f), textPaint)
            drawTimestampAxis(canvas, left, right, bottom, 0L, 0L)
            return
        }

        val minTs = samples.minOf { it.ts }
        val maxTs = samples.maxOf { it.ts }

        var minVal = fixedMin ?: Float.POSITIVE_INFINITY
        var maxVal = fixedMax ?: Float.NEGATIVE_INFINITY
        if (fixedMin == null || fixedMax == null) {
            samples.forEach { s ->
                s.values.forEach { v ->
                    minVal = min(minVal, v)
                    maxVal = max(maxVal, v)
                }
            }
            if (minVal == maxVal) {
                minVal -= 1f
                maxVal += 1f
            }
        }

        val tsRange = max(1L, maxTs - minTs)
        val valRange = max(0.0001f, maxVal - minVal)

        drawBands(canvas, left, right, top, bottom, minVal, maxVal)

        val seriesCount = samples.maxOf { it.values.size }
        for (seriesIndex in 0 until seriesCount) {
            val path = Path()
            var started = false
            for (s in samples) {
                if (seriesIndex >= s.values.size) continue
                val x = left + (s.ts - minTs).toFloat() / tsRange * (right - left)
                val y = bottom - (s.values[seriesIndex] - minVal) / valRange * (bottom - top)
                if (!started) {
                    path.moveTo(x, y)
                    started = true
                } else {
                    path.lineTo(x, y)
                }
            }
            seriesPaint.color = seriesColors[seriesIndex % seriesColors.size]
            canvas.drawPath(path, seriesPaint)
        }

        drawLegend(canvas, left, top)
        drawValueAxis(canvas, left, top, bottom, minVal, maxVal)
        drawTimestampAxis(canvas, left, right, bottom, minTs, maxTs)
    }

    private fun drawBands(
        canvas: Canvas,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        minVal: Float,
        maxVal: Float
    ) {
        if (bands.isEmpty()) return

        val valRange = max(0.0001f, maxVal - minVal)
        bands.forEach { band ->
            val yMax = bottom - (band.max - minVal) / valRange * (bottom - top)
            val yMin = bottom - (band.min - minVal) / valRange * (bottom - top)
            val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = band.color
                style = Paint.Style.FILL
            }
            canvas.drawRect(left, min(yMin, yMax), right, max(yMin, yMax), bandPaint)
        }
    }

    private fun drawLegend(canvas: Canvas, left: Float, top: Float) {
        if (labels.isEmpty()) return
        var x = left
        val y = top - dp(6f)
        labels.forEachIndexed { index, label ->
            seriesPaint.color = seriesColors[index % seriesColors.size]
            canvas.drawLine(x, y, x + dp(14f), y, seriesPaint)
            canvas.drawText(label, x + dp(18f), y + dp(4f), textPaint)
            x += dp(60f)
        }
    }

    private fun drawValueAxis(
        canvas: Canvas,
        left: Float,
        top: Float,
        bottom: Float,
        minVal: Float,
        maxVal: Float
    ) {
        val textPadding = dp(4f)
        val minText = "%.2f".format(minVal)
        val maxText = "%.2f".format(maxVal)
        canvas.drawText(maxText, left - dp(32f), top + textPadding + dp(8f), textPaint)
        canvas.drawText(minText, left - dp(32f), bottom, textPaint)
    }

    private fun drawTimestampAxis(
        canvas: Canvas,
        left: Float,
        right: Float,
        bottom: Float,
        minTs: Long,
        maxTs: Long
    ) {
        val midTs = minTs + (maxTs - minTs) / 2
        val y = bottom + dp(18f)
        canvas.drawText("ts", left - dp(24f), y, textPaint)
        canvas.drawText(minTs.toString(), left, y, textPaint)
        canvas.drawText(midTs.toString(), (left + right) / 2 - dp(18f), y, textPaint)
        canvas.drawText(maxTs.toString(), right - dp(36f), y, textPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    companion object {
        val DEFAULT_SERIES_COLORS = intArrayOf(
            Color.parseColor("#0E79B2"),
            Color.parseColor("#F39237"),
            Color.parseColor("#BF2F2F"),
            Color.parseColor("#2E933C")
        )
    }
}
