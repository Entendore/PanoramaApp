package com.example.panoramaapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/** ImageView with pinch-zoom, drag-to-pan and double-tap zoom. 1x = fit-center. */
class ZoomableImageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatImageView(context, attrs) {

    private val v = FloatArray(9)
    private val base = Matrix()
    private val current = Matrix()
    private var baseScale = 1f
    private var lastX = 0f
    private var lastY = 0f

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                current.postScale(detector.scaleFactor, detector.scaleFactor,
                    detector.focusX, detector.focusY)
                clamp()
                imageMatrix = current
                return true
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                current.getValues(v)
                val zoom = v[Matrix.MSCALE_X] / baseScale
                if (zoom > 1.2f) {
                    current.set(base)
                } else {
                    current.postScale(2.5f / zoom, 2.5f / zoom, e.x, e.y)
                }
                clamp()
                imageMatrix = current
                return true
            }
        })

    override fun setImageBitmap(bm: Bitmap?) {
        super.setImageBitmap(bm)
        if (bm == null) {
            base.reset(); current.reset()
            scaleType = ScaleType.FIT_CENTER
        } else {
            scaleType = ScaleType.MATRIX
            post { initBaseMatrix() }
        }
    }

    private fun initBaseMatrix() {
        val d = drawable ?: return
        val w = width.toFloat(); val h = height.toFloat()
        if (w == 0f || h == 0f) { post { initBaseMatrix() }; return }
        val s = min(w / d.intrinsicWidth, h / d.intrinsicHeight)
        baseScale = s
        base.reset()
        base.postScale(s, s)
        base.postTranslate((w - d.intrinsicWidth * s) / 2f, (h - d.intrinsicHeight * s) / 2f)
        current.set(base)
        imageMatrix = current
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    current.getValues(v)
                    if (v[Matrix.MSCALE_X] / baseScale > 1f) {
                        current.postTranslate(event.x - lastX, event.y - lastY)
                        clamp()
                        imageMatrix = current
                    }
                }
                lastX = event.x; lastY = event.y
            }
        }
        return true
    }

    /** Keep the image covering the view while zoomed; snap back to fit below 1x. */
    private fun clamp() {
        val d = drawable ?: return
        current.getValues(v)
        val scale = v[Matrix.MSCALE_X]
        if (scale / baseScale < 1f) { current.set(base); return }
        if (scale / baseScale > 8f) {
            val corr = 8f * baseScale / scale
            current.postScale(corr, corr, width / 2f, height / 2f)
            current.getValues(v)
        }
        val cw = d.intrinsicWidth * v[Matrix.MSCALE_X]
        val ch = d.intrinsicHeight * v[Matrix.MSCALE_X]
        var tx = v[Matrix.MTRANS_X]
        var ty = v[Matrix.MTRANS_Y]
        tx = if (cw <= width) (width - cw) / 2f else tx.coerceIn(width - cw, 0f)
        ty = if (ch <= height) (height - ch) / 2f else ty.coerceIn(height - ch, 0f)
        v[Matrix.MTRANS_X] = tx
        v[Matrix.MTRANS_Y] = ty
        current.setValues(v)
    }
}
