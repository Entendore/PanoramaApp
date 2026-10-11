package com.example.panoramaapp

import android.graphics.Bitmap
import org.opencv.core.Mat

/**
 * Owns all capture Mats at process scope, so a configuration change or
 * activity death can never drop a session. All access is synchronized —
 * the analyzer thread writes, UI/stitch coroutines read.
 */
object FrameStore {

    /** Last captured frame, downscaled for the ghost overlay, + its pan angle. */
    data class GhostState(val bmp: Bitmap, val pan: Float)

    @Volatile var sessionActive = false
        private set
    @Volatile var ghostState: GhostState? = null
        private set

    private val frames = ArrayList<Mat>()
    private val pans = ArrayList<Float>()
    private val kept = ArrayList<Mat>()
    private val keptPans = ArrayList<Float>()

    val activeCount: Int get() = synchronized(this) { frames.size }
    val hasKept: Boolean get() = synchronized(this) { kept.isNotEmpty() }
    val lastPan: Float? get() = synchronized(this) { pans.lastOrNull() }

    fun startSession() = synchronized(this) {
        releaseLocked(frames, pans)
        clearGhostLocked()
        sessionActive = true
    }

    fun endSession() = synchronized(this) {
        releaseLocked(frames, pans)
        releaseLocked(kept, keptPans)
        clearGhostLocked()
        sessionActive = false
    }

    fun add(mat: Mat, pan: Float): Int = synchronized(this) {
        frames.add(mat); pans.add(pan); frames.size
    }

    fun undoLast(): Int = synchronized(this) {
        if (frames.isNotEmpty()) { frames.removeAt(frames.size - 1).release(); pans.removeAt(pans.size - 1) }
        frames.size
    }

    /** Moves active frames out for stitching (ownership transfers to caller). */
    fun takeForStitch(): Pair<List<Mat>, List<Float>>? = synchronized(this) {
        if (frames.size < 2) null
        else {
            val f = ArrayList(frames); val p = ArrayList(pans)
            frames.clear(); pans.clear()
            f to p
        }
    }

    /** Stitch failed — frames return to the active session for retry. */
    fun restore(f: List<Mat>, p: List<Float>) = synchronized(this) {
        frames.addAll(f); pans.addAll(p)
    }

    /** Stitch succeeded — frames move to kept storage for "Add more". */
    fun keep(f: List<Mat>, p: List<Float>) = synchronized(this) {
        releaseLocked(kept, keptPans)
        kept.addAll(f); keptPans.addAll(p)
    }

    fun dropKept() = synchronized(this) { releaseLocked(kept, keptPans) }

    /** "Add more": kept frames become the active session again. */
    fun resumeKept() = synchronized(this) {
        releaseLocked(frames, pans)
        frames.addAll(kept); pans.addAll(keptPans)
        kept.clear(); keptPans.clear()
        sessionActive = true
    }

    fun snapshotForDraft(targetW: Int): List<Mat> = synchronized(this) {
        frames.map { downscaleClone(it, targetW) }
    }

    // ---- Ghost ----
    // Old ghost bitmaps are intentionally NOT recycle()d: the UI thread may be
    // mid-draw on them. Dropping the reference lets GC reclaim safely.

    fun setGhost(bmp: Bitmap, pan: Float) { ghostState = GhostState(bmp, pan) }

    fun clearGhost() = synchronized(this) { clearGhostLocked() }

    /** After undo: regenerate the ghost from the new last frame. */
    fun rebuildGhost(converter: (Mat) -> Bitmap?) = synchronized(this) {
        val last = frames.lastOrNull()
        ghostState = if (last != null) converter(last)?.let { GhostState(it, pans.last()) } else null
    }

    fun releaseAll() = synchronized(this) {
        releaseLocked(frames, pans)
        releaseLocked(kept, keptPans)
        clearGhostLocked()
        sessionActive = false
    }

    private fun clearGhostLocked() { ghostState = null }

    private fun releaseLocked(mats: MutableList<Mat>, values: MutableList<Float>) {
        mats.forEach { it.release() }
        mats.clear(); values.clear()
    }
}
