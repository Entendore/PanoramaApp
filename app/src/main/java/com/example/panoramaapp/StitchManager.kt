package com.example.panoramaapp

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import org.opencv.stitching.Stitcher

sealed interface StitchState {
    data object Idle : StitchState
    data class Running(val frameCount: Int) : StitchState
    data class Success(val bitmap: Bitmap, val uri: Uri?, val frameCount: Int) : StitchState
    data class Failure(val code: Int, val frameCount: Int) : StitchState
}

/**
 * Process-scoped stitching coordinator. Final stitches run in an app-scope
 * coroutine: leaving the app, rotating, or an incoming call cannot cancel them.
 * The UI renders [state]; ownership of result bitmaps lives here.
 */
object StitchManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val engine: StitchingEngine = OpenCvStitchingEngine()
    private val mutex = Mutex()

    private val _state = MutableStateFlow<StitchState>(StitchState.Idle)
    val state: StateFlow<StitchState> = _state

    private lateinit var appContext: Context

    fun init(ctx: Context) { appContext = ctx.applicationContext }

    /**
     * Takes ownership of [frames]/[pans]. On success they move to FrameStore's
     * kept set (for "Add more"); on failure they're restored to the active
     * session. Either way the caller never has to clean up.
     */
    fun submitFinal(frames: List<Mat>, pans: List<Float>, settings: StitchSettings) {
        if (_state.value is StitchState.Running) {
            frames.forEach { it.release() }
            return
        }
        (_state.value as? StitchState.Success)?.bitmap?.recycle()
        _state.value = StitchState.Running(frames.size)

        scope.launch {
            val t0 = System.currentTimeMillis()
            val outcome = mutex.withLock { engine.stitch(frames, settings) }
            val durationMs = System.currentTimeMillis() - t0

            when (outcome) {
                is StitchOutcome.Success -> {
                    val cropped = engine.autoCrop(outcome.pano)
                    if (cropped !== outcome.pano) outcome.pano.release()
                    val bmp = OpenCvStitchingEngine.toBitmap(cropped)
                    cropped.release()

                    FrameStore.keep(frames, pans)
                    val uri = withContext(Dispatchers.IO) { save(bmp) }
                    LocalStats.stitchResult(outcome.frameCount, settings.scansMode, Stitcher.OK, durationMs)

                    _state.value = StitchState.Success(bmp, uri, outcome.frameCount)
                }
                is StitchOutcome.Failure -> {
                    LocalStats.stitchResult(frames.size, settings.scansMode, outcome.code, durationMs)
                    FrameStore.restore(frames, pans)
                    _state.value = StitchState.Failure(outcome.code, frames.size)
                }
            }
        }
    }

    /** Live drafts share the same mutex so they never collide with a final stitch. */
    suspend fun stitchDraft(frames: List<Mat>, settings: StitchSettings): Mat? =
        mutex.withLock {
            when (val o = engine.stitch(frames, settings)) {
                is StitchOutcome.Success -> o.pano
                is StitchOutcome.Failure -> null
            }
        }

    /** Called by the UI when the user leaves the result screen. */
    fun resetToIdle() {
        (_state.value as? StitchState.Success)?.bitmap?.recycle()
        _state.value = StitchState.Idle
    }

    /**
     * Saves synchronously on an IO dispatcher. Detects disk-full: a failed
     * compress() or a failed stream open deletes the orphaned MediaStore row
     * and returns null instead of leaving a 0-byte file behind.
     */
    private fun save(bmp: Bitmap): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PANO_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/PanoramaApp")
            }
        }
        return try {
            val resolver = appContext.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            val ok = resolver.openOutputStream(uri)?.use {
                bmp.compress(Bitmap.CompressFormat.JPEG, 95, it)
            } ?: false
            if (!ok) { resolver.delete(uri, null, null); return null }
            uri
        } catch (e: Exception) {
            null
        }
    }
}
