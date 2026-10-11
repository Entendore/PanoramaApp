package com.example.panoramaapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LargestRectTest {

    private fun mask(w: Int, h: Int, black: (x: Int, y: Int) -> Boolean): ByteArray =
        ByteArray(w * h) { i -> if (black(i % w, i / w)) 0 else 1 }

    @Test fun `full mask returns whole rect`() {
        val r = findLargestRect(mask(10, 10) { _, _ -> false }, 10, 10)!!
        assertEquals(listOf(0, 0, 10, 10), r.toList())
    }

    @Test fun `empty mask returns null`() {
        assertNull(findLargestRect(mask(10, 10) { _, _ -> true }, 10, 10))
    }

    @Test fun `black left columns excluded`() {
        val r = findLargestRect(mask(10, 10) { x, _ -> x < 2 }, 10, 10)!!
        assertEquals(listOf(2, 0, 8, 10), r.toList())
    }

    @Test fun `black top rows excluded`() {
        val r = findLargestRect(mask(10, 10) { _, y -> y < 3 }, 10, 10)!!
        assertEquals(listOf(0, 3, 10, 7), r.toList())
    }

    @Test fun `isolated square found exactly`() {
        val m = mask(10, 10) { x, y -> !(x in 2..6 && y in 3..7) }
        val r = findLargestRect(m, 10, 10)!!
        assertEquals(listOf(2, 3, 5, 5), r.toList())
    }
}
