package com.example.panoramaapp

import android.app.ActivityManager
import android.content.Context

/**
 * Device capability tiers, computed once at app start. A 2 GB phone gets a
 * smaller stitch width, no live drafts, fewer frames and a lower viewer decode
 * cap — instead of an OOM crash three frames into a session.
 */
object DeviceProfile {
    var lowRam = false; private set
    var memoryClassMb = 256; private set

    fun init(ctx: Context) {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        lowRam = am.isLowRamDevice
        memoryClassMb = am.memoryClass
    }

    val maxFrames get() = if (lowRam) 8 else 12
    val maxWidth get() = if (lowRam) 800 else 1600
    val draftsEnabled get() = !lowRam && memoryClassMb >= 192
    val viewerMaxDim get() = if (lowRam) 1280 else 2048
    val ghostWidth get() = if (lowRam) 480 else 720
}
