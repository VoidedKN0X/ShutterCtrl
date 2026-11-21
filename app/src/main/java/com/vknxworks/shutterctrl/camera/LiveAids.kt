package com.vknxworks.shutterctrl.camera

/** Live-view aids worked out on the phone from the camera's live-view frames. Pure Kotlin, unit-tested. */
object LiveAids {
    /** A brightness histogram (BT.601 luma) of ARGB [pixels] with [bins] bins, darkest first. */
    fun histogram(pixels: IntArray, bins: Int = 64): IntArray {
        val out = IntArray(bins)
        for (p in pixels) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[((r * 77 + g * 150 + b * 29) shr 8) * bins / 256]++
        }
        return out
    }

    /** Magnification steps of the live-view picture (on the phone). */
    val MAGNIFY = listOf(1, 2, 4)

    /** The next magnification after [current]. */
    fun nextMagnify(current: Int): Int = MAGNIFY[(MAGNIFY.indexOf(current).coerceAtLeast(0) + 1) % MAGNIFY.size]

    /**
     * The magnified picture is scaled [zoom] times around a fixed point, [pivot] (0..1 of the picture), which stays
     * where it is on screen, so the frame is always filled. A tap at [tap] (0..1 of the frame) is this point of the
     * picture; it becomes the new pivot, so you magnify around what you tapped.
     */
    fun pictureAt(tap: Float, pivot: Float, zoom: Int): Float =
        (pivot + (tap - pivot) / zoom.coerceAtLeast(1)).coerceIn(0f, 1f)
}
