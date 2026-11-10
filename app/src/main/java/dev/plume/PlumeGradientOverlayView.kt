package dev.plume

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * Three-way dark gradient overlay for Plume Home:
 * 1. Left-to-right dark gradient (~78% to 0% across first 65% width)
 * 2. Top gradient behind header (down across first ~140dp)
 * 3. Bottom gradient behind apps row (up across bottom ~220dp)
 * Uses pure hardware-accelerated shaders with zero blurs.
 */
class PlumeGradientOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val horizontalPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val topPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Base background color #090B10
    private val baseR = 0x09
    private val baseG = 0x0B
    private val baseB = 0x10

    private var lastW = 0
    private var lastH = 0

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        lastW = w
        lastH = h

        val c78 = (0xC7 shl 24) or (baseR shl 16) or (baseG shl 8) or baseB
        val c85 = (0xD9 shl 24) or (baseR shl 16) or (baseG shl 8) or baseB
        val c90 = (0xE6 shl 24) or (baseR shl 16) or (baseG shl 8) or baseB
        val cTrans = (0x00 shl 24) or (baseR shl 16) or (baseG shl 8) or baseB

        // 1. Horizontal gradient: 78% opacity across first 65%
        horizontalPaint.shader = LinearGradient(
            0f, 0f, w * 0.65f, 0f,
            intArrayOf(c78, cTrans),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        // 2. Top gradient: 85% opacity across top 140dp
        val topH = (140 * resources.displayMetrics.density).coerceAtMost(h * 0.35f)
        topPaint.shader = LinearGradient(
            0f, 0f, 0f, topH,
            intArrayOf(c85, cTrans),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )

        // 3. Bottom gradient: 90% opacity across bottom 220dp
        val botH = (220 * resources.displayMetrics.density).coerceAtMost(h * 0.5f)
        bottomPaint.shader = LinearGradient(
            0f, h - botH, 0f, h.toFloat(),
            intArrayOf(cTrans, c90),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lastW <= 0 || lastH <= 0) return
        val w = lastW.toFloat()
        val h = lastH.toFloat()

        // Draw horizontal gradient
        canvas.drawRect(0f, 0f, w, h, horizontalPaint)
        // Draw top gradient
        canvas.drawRect(0f, 0f, w, h, topPaint)
        // Draw bottom gradient
        canvas.drawRect(0f, 0f, w, h, bottomPaint)
    }
}

