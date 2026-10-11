package com.example.panoramaapp

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import org.opencv.stitching.Stitcher

/**
 * Local-only replacement for analytics. Counters live in a private prefs file,
 * shown in Settings → Diagnostics. Nothing leaves the device.
 */
object LocalStats {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("pano_stats", Context.MODE_PRIVATE)
    }

    @Synchronized fun sessionStarted(autoMode: Boolean, vertical: Boolean) {
        prefs.edit()
            .putInt("sessions", prefs.getInt("sessions", 0) + 1)
            .putBoolean("last_auto", autoMode)
            .putBoolean("last_vertical", vertical)
            .apply()
    }

    @Synchronized fun stitchResult(frameCount: Int, scansMode: Boolean, code: Int, durationMs: Long) {
        val ok = code == Stitcher.OK
        prefs.edit()
            .putInt("attempts", prefs.getInt("attempts", 0) + 1)
            .putInt("successes", prefs.getInt("successes", 0) + if (ok) 1 else 0)
            .putLong("total_ms", prefs.getLong("total_ms", 0) + durationMs)
            .putInt("last_frames", frameCount)
            .putInt("last_code", code)
            .putLong("last_ms", durationMs)
            .putBoolean("last_scans", scansMode)
            .apply()
    }

    fun diagnostics(): String {
        val attempts = prefs.getInt("attempts", 0)
        val successes = prefs.getInt("successes", 0)
        val avgMs = if (attempts > 0) prefs.getLong("total_ms", 0) / attempts else 0
        val rate = if (attempts > 0) successes * 100 / attempts else 0
        return buildString {
            appendLine("PanoramaApp diagnostics")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
            appendLine("Sessions: ${prefs.getInt("sessions", 0)}")
            appendLine("Stitch attempts: $attempts  Successes: $successes ($rate%)")
            appendLine("Avg stitch time: ${"%.1f".format(avgMs / 1000.0)} s")
            appendLine("Last stitch: ${prefs.getInt("last_frames", 0)} frames, " +
                    "code ${prefs.getInt("last_code", 0)}, ${prefs.getLong("last_ms", 0)} ms")
        }
    }
}
