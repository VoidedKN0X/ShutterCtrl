package com.vknxworks.shutterctrl.geo

import androidx.exifinterface.media.ExifInterface
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** One position from the phone: [time] in epoch milliseconds. */
data class Fix(val time: Long, val lat: Double, val lon: Double, val alt: Double? = null, val accuracy: Float? = null) {
    fun encode(): String = listOf(time, lat, lon, alt ?: "", accuracy ?: "").joinToString(",")

    companion object {
        fun decode(line: String): Fix? {
            val p = line.split(',')
            if (p.size != 5) return null
            val t = p[0].toLongOrNull() ?: return null
            val lat = p[1].toDoubleOrNull()?.takeIf { it in -90.0..90.0 } ?: return null
            val lon = p[2].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return null
            return Fix(t, lat, lon, p[3].toDoubleOrNull(), p[4].toFloatOrNull())
        }
    }
}

/**
 * The short location log: fixes from the last [maxAgeMs] (at most [maxFixes]), kept only while geotagging is
 * on. A photo gets the fix nearest to its capture time, if one is within [toleranceMs].
 */
class LocationTrack(
    private val maxAgeMs: Long = 24 * 60 * 60_000L,
    private val maxFixes: Int = 5_000,
    val toleranceMs: Long = 10 * 60_000L,
) {
    private val fixes = ArrayList<Fix>()

    val size: Int @Synchronized get() = fixes.size

    @Synchronized
    fun add(fix: Fix, now: Long = fix.time) {
        // Keep the list in time order; a late fix from a slower provider is inserted in place.
        val i = fixes.indexOfLast { it.time <= fix.time } + 1
        fixes.add(i, fix)
        prune(now)
    }

    @Synchronized
    fun prune(now: Long) {
        fixes.removeAll { now - it.time > maxAgeMs }
        while (fixes.size > maxFixes) fixes.removeAt(0)
    }

    /** The fix nearest in time to [captureTime], or null when none is within the tolerance. */
    @Synchronized
    fun match(captureTime: Long): Fix? =
        fixes.minByOrNull { abs(it.time - captureTime) }?.takeIf { abs(it.time - captureTime) <= toleranceMs }

    @Synchronized
    fun clear() = fixes.clear()

    @Synchronized
    fun encode(): String = fixes.joinToString("\n") { it.encode() }

    @Synchronized
    fun load(text: String, now: Long) {
        fixes.clear()
        text.lineSequence().mapNotNull { Fix.decode(it.trim()) }.sortedBy { it.time }.forEach { fixes += it }
        prune(now)
    }
}

/** Writing the phone's position into a downloaded photo's EXIF (androidx.exifinterface; JPEG files). */
object GeoExif {
    private val EXIF_TIME = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)

    /**
     * When the photo was taken, from EXIF DateTimeOriginal (camera clock, local time). With an
     * OffsetTimeOriginal ("+02:00") that offset is used; otherwise the phone's time zone [zone].
     */
    fun captureTime(dateTime: String?, offset: String?, zone: ZoneId): Long? {
        val dt = runCatching { LocalDateTime.parse(dateTime?.trim().orEmpty(), EXIF_TIME) }.getOrNull() ?: return null
        val off = offset?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        return if (off != null) dt.toInstant(off).toEpochMilli() else dt.atZone(zone).toInstant().toEpochMilli()
    }

    fun captureTime(exif: ExifInterface, zone: ZoneId): Long? = captureTime(
        exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
        exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME),
        zone,
    )

    /** Whether the photo already has a position (a camera with its own GPS): it's then left alone. */
    fun hasPosition(exif: ExifInterface): Boolean = exif.latLong != null

    /**
     * Sets the GPS tags for [fix] (position, altitude, the fix's UTC time, its accuracy) in [exif]. Call
     * saveAttributes() afterwards.
     */
    fun apply(exif: ExifInterface, fix: Fix) {
        exif.setLatLong(fix.lat, fix.lon)
        fix.alt?.let { exif.setAltitude(it) }
        val utc = java.time.Instant.ofEpochMilli(fix.time).atOffset(ZoneOffset.UTC)
        exif.setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "%04d:%02d:%02d".format(Locale.ROOT, utc.year, utc.monthValue, utc.dayOfMonth))
        // ExifInterface takes the GPS time as "hh:mm:ss" and stores it as three rationals.
        exif.setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "%02d:%02d:%02d".format(Locale.ROOT, utc.hour, utc.minute, utc.second))
        fix.accuracy?.takeIf { it > 0f }?.let {
            exif.setAttribute(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, "%d/100".format(Locale.ROOT, Math.round(it * 100.0)))
        }
    }

    /**
     * Geotags one photo: finds its capture time, looks it up in [track], and writes the position.
     * Returns the fix that was written, or null (no capture time, no fix close enough, or it already had one).
     */
    fun tag(exif: ExifInterface, track: LocationTrack, zone: ZoneId): Fix? {
        if (hasPosition(exif)) return null
        val t = captureTime(exif, zone) ?: return null
        val fix = track.match(t) ?: return null
        apply(exif, fix)
        exif.saveAttributes()
        return fix
    }
}
