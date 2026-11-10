package dev.plume.tmdb

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * 1080p Android TV Circular Rating Gauge:
 * - Circular track filled to vote_average / 10
 * - Color: #42D6C5 if score >= 8.0, else #8B7CFF
 * - Center text with score (e.g. "7.9")
 */
class RatingGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var score: Float = 0f
    private val strokeWidthPx = (3f * resources.displayMetrics.density)
    private val arcBounds = RectF()

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        color = 0x33FFFFFF // 20% white track
    }

    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    fun setScore(value: Double) {
        this.score = value.toFloat().coerceIn(0f, 10f)
        val color = if (score >= 8.0f) 0xFF42D6C5.toInt() else 0xFF8B7CFF.toInt()
        progressPaint.color = color
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val pad = strokeWidthPx / 2f + 1f
        arcBounds.set(pad, pad, w - pad, h - pad)
        textPaint.textSize = (h * 0.42f).coerceAtLeast(10f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // 1. Draw track circle
        canvas.drawOval(arcBounds, trackPaint)

        // 2. Draw progress arc (starts at -90 deg, top)
        val sweepAngle = (score / 10f) * 360f
        canvas.drawArc(arcBounds, -90f, sweepAngle, false, progressPaint)

        // 3. Draw score text in centre
        val text = String.format(java.util.Locale.US, "%.1f", score)
        val textY = h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
        canvas.drawText(text, w / 2f, textY, textPaint)
    }
}
