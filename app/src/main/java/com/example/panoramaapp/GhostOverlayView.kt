package com.example.panoramaapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws the last captured frame translucently at the position its content
 * occupies in the current view — the exact region the next frame must overlap.
 * The offset (px) is computed by MainActivity from gyro angle + camera FOV;
 * this view only does the drawing.
 */
class GhostOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint().apply { alpha = 85 }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = 0x88FFFFFF.toInt()
    }
    private val dst = RectF()
    private var bmp: Bitmap? = null
    private var dx = 0f
    private var dy = 0f

    fun render(bitmap: Bitmap?, dxPx: Float, dyPx: Float) {
        if (bitmap !== bmp || dx != dxPx || dy != dyPx) invalidate()
        bmp = bitmap; dx = dxPx; dy = dyPx
    }

    fun clear() {
        if (bmp != null) invalidate()
        bmp = null
    }

    override fun onDraw(canvas: Canvas) {
        val b = bmp ?: return
        val w = width.toFloat()
        val h = w * b.height / b.width
        val top = (height - h) / 2f + dy
        dst.set(dx, top, dx + w, top + h)
        canvas.drawBitmap(b, null, dst, paint)
        canvas.drawRect(dst, border)
    }
}
