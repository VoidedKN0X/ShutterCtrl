package com.vknxworks.shutterctrl.camera

/** How long [CameraSession] waits between two GetEvent polls. Plain Kotlin, unit-tested. */
object PollTiming {
    const val FOREGROUND_MS = 500L
    const val LIVE_VIEW_MS = 1000L
    const val BACKGROUND_MS = 2000L

    /**
     * Every 500 ms (1 s while live view runs) as before. In the background every 2 s, unless something there needs
     * the camera's events quickly: a running intervalometer, bracket, focus stack or bulb exposure ([busy]), or
     * auto-download ([quickEvents]).
     */
    fun delayMs(background: Boolean, liveView: Boolean, busy: Boolean, quickEvents: Boolean): Long = when {
        background && !busy && !quickEvents -> BACKGROUND_MS
        liveView -> LIVE_VIEW_MS
        else -> FOREGROUND_MS
    }
}
