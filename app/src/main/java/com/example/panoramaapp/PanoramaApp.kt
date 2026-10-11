package com.example.panoramaapp

import android.app.Application

class PanoramaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        LocalCrashLogger.install(this)
        LocalStats.init(this)
        DeviceProfile.init(this)
        StitchManager.init(this)
    }

    /** Sacrifice "Add more" frames under memory pressure; never touch the active session. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && !FrameStore.sessionActive) {
            FrameStore.dropKept()
        }
    }
}
