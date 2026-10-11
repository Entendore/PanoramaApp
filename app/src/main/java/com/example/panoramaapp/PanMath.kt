package com.example.panoramaapp

import kotlin.math.abs

object PanMath {
    /** Wrap-safe angle delta in [-π, π] (handles the ±180° yaw seam). */
    fun angleDiff(a: Float, b: Float): Float {
        var d = a - b
        val twoPi = (2.0 * Math.PI).toFloat()
        while (d > Math.PI) d -= twoPi
        while (d < -Math.PI) d += twoPi
        return d
    }

    fun absDiffDeg(a: Float, b: Float): Double =
        Math.toDegrees(abs(angleDiff(a, b)).toDouble())
}
