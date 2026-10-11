package com.example.panoramaapp

import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes uncaught exceptions to app-private storage (last 5 kept).
 * No network, ever. Reachable via `adb shell run-as` on debug builds.
 */
object LocalCrashLogger {
    private const val MAX_FILES = 5
    private lateinit var dir: File

    fun install(ctx: android.content.Context) {
        dir = File(ctx.filesDir, "crashes").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(thread, e) }
            previous?.uncaughtException(thread, e)
        }
    }

    @Synchronized private fun write(thread: Thread, e: Throwable) {
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        File(dir, "crash_${System.currentTimeMillis()}.txt").writeText(
            buildString {
                appendLine("PanoramaApp crash — $stamp")
                appendLine("Thread: ${thread.name}")
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})")
                appendLine("App: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME}")
                appendLine()
                appendLine(sw)
                appendLine()
                runCatching { append(LocalStats.diagnostics()) }
            }
        )
        dir.listFiles()?.sortedByDescending { it.name }?.drop(MAX_FILES)?.forEach { it.delete() }
    }

    fun latest(): File? = dir.listFiles()?.maxByOrNull { it.name }
}
