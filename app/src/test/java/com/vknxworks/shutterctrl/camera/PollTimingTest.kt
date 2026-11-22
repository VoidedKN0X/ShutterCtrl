package com.vknxworks.shutterctrl.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class PollTimingTest {
    @Test fun foregroundIsUnchanged() {
        assertEquals(500L, PollTiming.delayMs(background = false, liveView = false, busy = false, quickEvents = false))
        assertEquals(1000L, PollTiming.delayMs(background = false, liveView = true, busy = false, quickEvents = false))
        assertEquals(500L, PollTiming.delayMs(background = false, liveView = false, busy = true, quickEvents = true))
    }

    @Test fun idleBackgroundPollsSlower() {
        assertEquals(2000L, PollTiming.delayMs(background = true, liveView = false, busy = false, quickEvents = false))
        assertEquals(2000L, PollTiming.delayMs(background = true, liveView = true, busy = false, quickEvents = false))
    }

    @Test fun seriesAndAutoDownloadKeepTheQuickRate() {
        assertEquals(500L, PollTiming.delayMs(background = true, liveView = false, busy = true, quickEvents = false))
        assertEquals(500L, PollTiming.delayMs(background = true, liveView = false, busy = false, quickEvents = true))
        // A focus stack in the background keeps its live view, and the live-view rate.
        assertEquals(1000L, PollTiming.delayMs(background = true, liveView = true, busy = true, quickEvents = false))
    }
}
