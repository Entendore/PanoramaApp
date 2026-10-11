package com.example.panoramaapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Full-screen transparent overlay drawing the "next capture" guide:
 * a track with a fixed center notch (the capture point) and a moving marker
 * showing where the next target currently sits on screen.
 *
 * fraction: -1..1 (marker at track start..end). |fraction| < 0.08 => aligned => green.
 */
class PanGuideView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var verticalStrip = false
    var bottomInsetPx = 0f

    private val density = resources.displayMetrics.density
    private var fraction = 0f
    private var degrees = 0f

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x50FFFFFF.toInt() }
    private val notch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * density
        color = 0xFFFFFFFF.toInt()
        setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
    }
    private val rect = RectF()

    fun update(frac: Float, degreesToTarget: Float) {
        fraction = frac.coerceIn(-1f, 1f)
        degrees = abs(degreesToTarget)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val aligned = abs(fraction) < 0.08f
        marker.color = if (aligned) 0xFF66BB6A.toInt() else 0xFFFFFFFF.toInt()

        if (verticalStrip) {
            val cx = 24f * density
            val top = height * 0.30f
            val half = (height * 0.40f) / 2f
            val cy = height / 2f
            rect.set(cx - 3f * density, top, cx + 3f * density, top + half * 2)
            canvas.drawRoundRect(rect, 3f * density, 3f * density, track)
            canvas.drawRect(cx - 8f * density, cy - 1.5f * density, cx + 8f * density, cy + 1.5f * density, notch)
            val my = cy - fraction * half
            canvas.drawCircle(cx, my, 8f * density, marker)
            if (degrees >= 1f) {
                label.textAlign = Paint.Align.LEFT
                canvas.drawText("${degrees.roundToInt()}°", cx + 14f * density, my + 4f * density, label)
            }
        } else {
            val cy = height - bottomInsetPx - 170f * density
            val left = 32f * density
            val half = (width - 64f * density) / 2f
            val cx = width / 2f
            rect.set(left, cy - 3f * density, width - 32f * density, cy + 3f * density)
            canvas.drawRoundRect(rect, 3f * density, 3f * density, track)
            canvas.drawRect(cx - 1.5f * density, cy - 9f * density, cx + 1.5f * density, cy + 9f * density, notch)
            val mx = cx + fraction * half
            canvas.drawCircle(mx, cy, 8f * density, marker)
            if (degrees >= 1f) {
                label.textAlign = Paint.Align.CENTER
                canvas.drawText("${degrees.roundToInt()}°", mx, (cy - 16f * density).coerceAtLeast(14f * density), label)
            }
        }
    }
}
