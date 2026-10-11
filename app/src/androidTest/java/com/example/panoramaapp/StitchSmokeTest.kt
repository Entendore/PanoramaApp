package com.example.panoramaapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Rect as CvRect
import org.opencv.imgproc.Imgproc

@RunWith(AndroidJUnit4::class)
class StitchSmokeTest {

    @Test fun `synthetic overlapping frames stitch successfully`() {
        assertTrue(OpenCVLoader.initLocal())
        val engine = OpenCvStitchingEngine()

        val scene = texturedBitmap(1600, 500, seed = 42L)
        val sceneMat = Mat()
        Utils.bitmapToMat(scene, sceneMat)
        val rgb = Mat()
        Imgproc.cvtColor(sceneMat, rgb, Imgproc.COLOR_RGBA2RGB)
        sceneMat.release()
        scene.recycle()

        val f1 = Mat(rgb, CvRect(0, 0, 800, 500)).clone()
        val f2 = Mat(rgb, CvRect(560, 0, 800, 500)).clone()
        rgb.release()

        val outcome = engine.stitch(listOf(f1, f2), StitchSettings(false, 960))
        f1.release(); f2.release()

        assertTrue("Expected Success, got: $outcome", outcome is StitchOutcome.Success)
        (outcome as StitchOutcome.Success).pano.release()
    }

    private fun texturedBitmap(w: Int, h: Int, seed: Long): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint()
        val rng = Random(seed)
        canvas.drawColor(Color.LTGRAY)
        repeat(500) {
            paint.color = Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256))
            canvas.drawCircle(rng.nextFloat() * w, rng.nextFloat() * h,
                5f + rng.nextFloat() * 25f, paint)
        }
        return bmp
    }
}
