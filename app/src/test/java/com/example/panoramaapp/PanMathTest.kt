package com.example.panoramaapp

import org.junit.Assert.assertEquals
import org.junit.Test

class PanMathTest {
    @Test fun `plain difference`() = assertEquals(0.5f, PanMath.angleDiff(1.0f, 0.5f), 1e-6f)

    @Test fun `wraps across seam clockwise`() {
        assertEquals(-0.2832f, PanMath.angleDiff(3.0f, -3.0f), 1e-3f)
    }

    @Test fun `wraps across seam counter-clockwise`() {
        assertEquals(0.2832f, PanMath.angleDiff(-3.0f, 3.0f), 1e-3f)
    }

    @Test fun `half turn is zero`() {
        assertEquals(0f, PanMath.angleDiff(Math.PI.toFloat(), -Math.PI.toFloat()), 1e-4f)
    }
}
