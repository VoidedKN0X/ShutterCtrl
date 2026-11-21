package com.vknxworks.shutterctrl.ptp

import kotlin.math.abs
import kotlin.math.pow

/**
 * Canon EOS exposure codes to readable values. Canon codes are APEX values times 8 with an offset,
 * which is how libgphoto2's tables line up too; results are snapped to the nominal values printed on cameras.
 */
object ExposureValues {
    private val apertures = doubleArrayOf(
        1.0, 1.1, 1.2, 1.4, 1.6, 1.8, 2.0, 2.2, 2.5, 2.8, 3.2, 3.5, 4.0, 4.5, 5.0, 5.6, 6.3, 7.1, 8.0, 9.0,
        10.0, 11.0, 13.0, 14.0, 16.0, 18.0, 20.0, 22.0, 25.0, 29.0, 32.0, 36.0, 40.0, 45.0, 51.0, 57.0, 64.0, 72.0, 80.0, 91.0,
    )

    // Shutter speeds in seconds.
    private val speeds = doubleArrayOf(
        30.0, 25.0, 20.0, 15.0, 13.0, 10.0, 8.0, 6.0, 5.0, 4.0, 3.2, 3.0, 2.5, 2.0, 1.6, 1.5, 1.3, 1.0, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3,
        1 / 4.0, 1 / 5.0, 1 / 6.0, 1 / 8.0, 1 / 10.0, 1 / 13.0, 1 / 15.0, 1 / 20.0, 1 / 25.0, 1 / 30.0, 1 / 40.0, 1 / 45.0,
        1 / 50.0, 1 / 60.0, 1 / 80.0, 1 / 90.0, 1 / 100.0, 1 / 125.0, 1 / 160.0, 1 / 180.0, 1 / 200.0, 1 / 250.0, 1 / 320.0,
        1 / 350.0, 1 / 400.0, 1 / 500.0, 1 / 640.0, 1 / 750.0, 1 / 800.0, 1 / 1000.0, 1 / 1250.0, 1 / 1500.0, 1 / 1600.0,
        1 / 2000.0, 1 / 2500.0, 1 / 3000.0, 1 / 3200.0, 1 / 4000.0, 1 / 5000.0, 1 / 6000.0, 1 / 6400.0, 1 / 8000.0,
        1 / 10000.0, 1 / 12800.0, 1 / 16000.0, 1 / 20000.0, 1 / 25600.0, 1 / 32000.0, 1 / 40000.0, 1 / 51200.0, 1 / 64000.0,
    )

    private val isos = intArrayOf(
        50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
        8000, 10000, 12800, 16000, 20000, 25600, 32000, 40000, 51200, 64000, 80000, 102400, 204800, 409600, 819200,
    )

    private fun nearest(values: DoubleArray, x: Double): Double = values.minBy { abs(kotlin.math.ln(it) - kotlin.math.ln(x)) }

    /** f-number: code 0x08 is f/1.0, every 8 is one stop (Av = code/8 - 1). */
    fun aperture(code: Long): String {
        if (code == 0L || code == 0xFFFFFFFFL) return "--"
        val f = 2.0.pow((code / 8.0 - 1.0) / 2.0)
        val n = nearest(apertures, f)
        return "f/" + if (n < 10) "%.1f".format(java.util.Locale.ROOT, n) else n.toInt().toString()
    }

    /** Canon's shutter-speed code for Bulb. */
    const val BULB_CODE = 0x0CL

    /** Shutter speed: code 0x38 is 1 s, every 8 is one stop (Tv = code/8 - 7). 0x0C is Bulb, 0 is Auto. */
    fun shutter(code: Long): String {
        if (code == 0L || code == 0xFFFFFFFFL) return "AUTO"
        if (code == 0x0CL) return "BULB"
        val t = 2.0.pow(-(code / 8.0 - 7.0))
        val n = nearest(speeds, t)
        return when {
            n >= 0.3 -> if (n == n.toInt().toDouble()) "${n.toInt()}\"" else "%s\"".format(java.util.Locale.ROOT, n.toString())
            else -> "1/${Math.round(1 / n)}"
        }
    }

    /** ISO: code 0x48 is ISO 100, every 8 is one stop; 0 is Auto. */
    fun iso(code: Long): String {
        if (code == 0L || code == 0xFFFFFFFFL) return "AUTO"
        val v = 100.0 * 2.0.pow((code - 0x48) / 8.0)
        return isos.minBy { abs(kotlin.math.ln(it.toDouble()) - kotlin.math.ln(v)) }.toString()
    }

    /** Exposure compensation: a signed byte in eighths of a stop (3 = 1/3, 4 = 1/2, 5 = 2/3). */
    fun expComp(code: Long): String {
        val v = code.toInt().toByte().toInt()
        if (v == 0) return "0"
        val sign = if (v < 0) "−" else "+"
        val a = abs(v)
        val whole = a / 8
        val frac = when (a % 8) {
            0 -> ""
            3 -> "⅓"
            4 -> "½"
            5 -> "⅔"
            else -> "." + (a % 8)
        }
        val wholeText = if (whole == 0 && frac.isNotEmpty()) "" else whole.toString()
        return sign + wholeText + frac
    }

    /**
     * Battery (0xD111) as libgphoto2 reads it: 0 low, 1 half, 2 full, 4 three quarters, 5 a quarter.
     * Returns a percentage, or null when the value is unknown.
     */
    fun batteryPercent(code: Long): Int? = when (code) {
        0L -> 10
        1L -> 50
        2L -> 100
        4L -> 75
        5L -> 25
        else -> null
    }
}
