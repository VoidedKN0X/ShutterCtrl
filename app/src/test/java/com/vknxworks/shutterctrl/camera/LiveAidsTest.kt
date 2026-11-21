package com.vknxworks.shutterctrl.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveAidsTest {
    private fun grey(v: Int) = (0xFF shl 24) or (v shl 16) or (v shl 8) or v

    @Test
    fun histogram() {
        assertEquals(listOf(2, 0, 1, 1), LiveAids.histogram(intArrayOf(grey(0), grey(10), grey(200), grey(128)), bins = 4).toList())
    }

    @Test
    fun magnifySteps() {
        assertEquals(2, LiveAids.nextMagnify(1))
        assertEquals(4, LiveAids.nextMagnify(2))
        assertEquals(1, LiveAids.nextMagnify(4))
        assertEquals(2, LiveAids.nextMagnify(3))
    }

    @Test
    fun tapToPicture() {
        assertEquals(0.8f, LiveAids.pictureAt(0.8f, 0.5f, 1), 0.0001f)
        // Magnified 2× around the middle: the frame's right edge is 3/4 across the picture.
        assertEquals(0.75f, LiveAids.pictureAt(1f, 0.5f, 2), 0.0001f)
        assertEquals(0.5f, LiveAids.pictureAt(0.5f, 0.5f, 4), 0.0001f)
        assertEquals(0f, LiveAids.pictureAt(-1f, 0f, 2), 0.0001f)
    }
}
