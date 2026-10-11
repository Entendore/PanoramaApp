package com.example.panoramaapp

import android.graphics.Bitmap
import android.util.Log
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.core.Size as CvSize
import org.opencv.imgproc.Imgproc
import org.opencv.stitching.Stitcher

data class StitchSettings(val scansMode: Boolean, val widthCap: Int)

sealed interface StitchOutcome {
    data class Success(val pano: Mat, val frameCount: Int) : StitchOutcome
    data class Failure(val code: Int) : StitchOutcome
}

/** Pure stitching logic — no Android UI, no lifecycle. Faked in UI tests. */
interface StitchingEngine {
    fun stitch(frames: List<Mat>, settings: StitchSettings, frameCount: Int = frames.size): StitchOutcome
    fun isBlurry(mat: Mat, threshold: Double): Boolean
    fun autoCrop(mat: Mat): Mat
}

class OpenCvStitchingEngine : StitchingEngine {

    override fun stitch(frames: List<Mat>, settings: StitchSettings, frameCount: Int): StitchOutcome = try {
        val mode = if (settings.scansMode) Stitcher.SCANS else Stitcher.PANORAMA
        val stitcher = Stitcher.create(mode)
        val pano = Mat()
        val status = stitcher.stitch(frames, pano)
        if (status == Stitcher.OK) StitchOutcome.Success(pano, frameCount)
        else { pano.release(); StitchOutcome.Failure(status) }
    } catch (t: Throwable) {
        Log.e("StitchingEngine", "stitch crashed", t)
        StitchOutcome.Failure(-1)
    }

    /** Motion-blur gate: variance of the Laplacian. Sharp scenes score ≫ 100. */
    override fun isBlurry(mat: Mat, threshold: Double): Boolean {
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        val lap = Mat()
        Imgproc.Laplacian(gray, lap, CvType.CV_64F)
        val mean = MatOfDouble()
        val std = MatOfDouble()
        Core.meanStdDev(lap, mean, std)
        val variance = std.get(0, 0)[0] * std.get(0, 0)[0]
        gray.release(); lap.release(); mean.release(); std.release()
        return variance < threshold
    }

    /**
     * Crops the ragged black border the stitcher leaves behind:
     * largest axis-aligned rectangle of non-black pixels, O(w·h).
     * Bails out (returns input) unless ≥50% of area survives — protects dark scenes.
     */
    override fun autoCrop(mat: Mat): Mat {
        val gray = Mat()
        Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.threshold(gray, gray, 12.0, 255.0, Imgproc.THRESH_BINARY)
        Imgproc.medianBlur(gray, gray, 3)

        val w = gray.cols()
        val h = gray.rows()
        val px = ByteArray(w * h)
        gray.get(0, 0, px)
        gray.release()

        val rect = findLargestRect(px, w, h) ?: return mat
        val (l, t, rw, rh) = rect
        if (rw <= 0 || rh <= 0 || rw * rh < w * h * 0.5) return mat
        if (l == 0 && t == 0 && rw == w && rh == h) return mat
        return Mat(mat, Rect(l, t, rw, rh)).clone()
    }

    companion object {
        fun toBitmap(mat: Mat): Bitmap =
            Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888).also {
                Utils.matToBitmap(mat, it)
            }
    }
}

/** Resize so width ≤ targetW (never upscales); releases the input when resizing. */
internal fun downscale(src: Mat, targetW: Int): Mat {
    if (src.cols() <= targetW) return src
    val scale = targetW.toDouble() / src.cols()
    val out = Mat()
    Imgproc.resize(src, out, CvSize(src.cols() * scale, src.rows() * scale))
    src.release()
    return out
}

internal fun downscaleClone(src: Mat, targetW: Int): Mat = downscale(src.clone(), targetW)

/**
 * Largest rectangle of non-zero bytes in a row-major w×h mask.
 * Histogram/stack method, O(w·h). Pure Kotlin — unit-tested directly.
 * Returns [left, top, width, height] or null if the mask is all zeros.
 */
internal fun findLargestRect(px: ByteArray, w: Int, h: Int): IntArray? {
    val heights = IntArray(w)
    val stack = IntArray(w + 1)
    var best = intArrayOf(0, 0, 0, 0)
    var bestArea = 0

    for (row in 0 until h) {
        for (col in 0 until w) {
            heights[col] = if (px[row * w + col].toInt() != 0) heights[col] + 1 else 0
        }
        var sp = 0
        for (col in 0..w) {
            val cur = if (col == w) 0 else heights[col]
            while (sp > 0 && heights[stack[sp - 1]] >= cur) {
                val top = stack[--sp]
                val left = if (sp == 0) 0 else stack[sp - 1] + 1
                val area = heights[top] * (col - left)
                if (area > bestArea) {
                    bestArea = area
                    best = intArrayOf(left, row - heights[top] + 1, col - left, heights[top])
                }
            }
            if (col < w) stack[sp++] = col
        }
    }
    return if (best[2] > 0) best else null
}
