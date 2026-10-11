package com.example.panoramaapp

/** Compile-time capture/stitching constants. Fully local — no remote config. */
object Tuning {
    @Volatile var blurThreshold = 60.0     // variance-of-Laplacian blur gate
    @Volatile var captureBand = 0.3f       // auto-fire zone near the guided position
    @Volatile var draftWidth = 640         // live-draft stitch width
}
