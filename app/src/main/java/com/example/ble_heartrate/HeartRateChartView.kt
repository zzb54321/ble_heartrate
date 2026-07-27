package com.example.ble_heartrate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/**
 * Lightweight line chart that plots the most recent heart-rate samples.
 * Implemented as a plain custom view so that the project keeps its
 * zero-dependency (android.jar only) build setup.
 */
class HeartRateChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        /** Number of samples kept in the chart window. */
        const val MAX_POINTS = 120
        private const val MIN_RANGE_BPM = 20f
        private const val PADDING_BPM = 5f
    }

    private val values = ArrayDeque<Int>()
    private var threshold = 0

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#E53935")
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#22E53935")
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.parseColor("#E0E0E0")
    }

    private val thresholdPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#FF6D00")
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#757575")
        textSize = 26f
    }

    private val path = Path()

    /** Append a sample and redraw; values &lt;= 0 are ignored. */
    fun addValue(bpm: Int) {
        if (bpm <= 0) return
        values.addLast(bpm)
        while (values.size > MAX_POINTS) values.removeFirst()
        invalidate()
    }

    /** Threshold drawn as a dashed reference line (0 hides it). */
    fun setThreshold(bpm: Int) {
        if (threshold == bpm) return
        threshold = bpm
        invalidate()
    }

    fun clear() {
        values.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        if (right <= left || bottom <= top) return

        canvas.drawRect(left, top, right, bottom, gridPaint)

        if (values.isEmpty()) {
            canvas.drawText(
                context.getString(R.string.waiting_for_data),
                left + 12f,
                (top + bottom) / 2f,
                textPaint
            )
            return
        }

        val points = values.toList()
        var min = points.min().toFloat()
        var max = points.max().toFloat()
        if (threshold > 0) {
            min = minOf(min, threshold.toFloat())
            max = maxOf(max, threshold.toFloat())
        }
        min -= PADDING_BPM
        max += PADDING_BPM
        if (max - min < MIN_RANGE_BPM) {
            val center = (max + min) / 2f
            min = center - MIN_RANGE_BPM / 2f
            max = center + MIN_RANGE_BPM / 2f
        }
        val range = max - min

        fun yFor(bpm: Float): Float = bottom - (bpm - min) / range * (bottom - top)

        if (threshold > 0) {
            val y = yFor(threshold.toFloat())
            canvas.drawLine(left, y, right, y, thresholdPaint)
        }

        val stepDivisor = maxOf(points.size - 1, 1)
        val step = (right - left) / stepDivisor
        path.reset()
        points.forEachIndexed { index, bpm ->
            val x = if (points.size == 1) right else left + index * step
            val y = yFor(bpm.toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        // Filled area under the curve for readability.
        val fillPath = Path(path)
        val lastX = if (points.size == 1) right else left + (points.size - 1) * step
        fillPath.lineTo(lastX, bottom)
        fillPath.lineTo(left, bottom)
        fillPath.close()
        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)

        canvas.drawText("${max.toInt()}", left + 8f, top + textPaint.textSize, textPaint)
        canvas.drawText("${min.toInt()}", left + 8f, bottom - 8f, textPaint)
    }
}
