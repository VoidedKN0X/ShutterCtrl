package com.vknxworks.shutterctrl.geo

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class GeoTagTest {
    @get:Rule val tmp = TemporaryFolder()

    private val brussels = ZoneId.of("Europe/Brussels")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0, zone: ZoneId = brussels) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    /** A small real JPEG (test resource blank.jpg, 64 × 48), with DateTimeOriginal set like a camera would. */
    private fun jpeg(dateTimeOriginal: String?, offset: String? = null): File {
        val f = tmp.newFile("photo.jpg")
        javaClass.getResourceAsStream("/blank.jpg")!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        if (dateTimeOriginal != null) {
            ExifInterface(f.absolutePath).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTimeOriginal)
                if (offset != null) setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, offset)
                setAttribute(ExifInterface.TAG_MAKE, "Canon")
                saveAttributes()
            }
        }
        return f
    }

    @Test
    fun captureTimeUsesTheOffsetOrThePhoneZone() {
        assertEquals(at(2026, 7, 14, 15, 30), GeoExif.captureTime("2026:07:14 15:30:00", null, brussels))
        assertEquals(at(2026, 7, 14, 15, 30, zone = ZoneId.of("+09:00")), GeoExif.captureTime("2026:07:14 15:30:00", "+09:00", brussels))
        assertNull(GeoExif.captureTime("0000:00:00 00:00:00", null, brussels))
        assertNull(GeoExif.captureTime("", null, brussels))
        assertNull(GeoExif.captureTime(null, null, brussels))
    }

    @Test
    fun trackMatchesTheNearestFixWithinTolerance() {
        val t = LocationTrack(toleranceMs = 10 * 60_000L)
        val base = at(2026, 7, 14, 15, 0)
        t.add(Fix(base, 50.0, 4.0), now = base)
        t.add(Fix(base + 20 * 60_000L, 51.0, 5.0), now = base + 20 * 60_000L)
        t.add(Fix(base + 5 * 60_000L, 50.5, 4.5), now = base + 20 * 60_000L) // late, inserted in order
        assertEquals(50.5, t.match(base + 7 * 60_000L)!!.lat, 0.0)
        assertEquals(51.0, t.match(base + 16 * 60_000L)!!.lat, 0.0)
        assertNull(t.match(base + 40 * 60_000L))
        assertNull(t.match(base - 11 * 60_000L))
    }

    @Test
    fun trackForgetsOldFixesAndRoundTrips() {
        val t = LocationTrack(maxAgeMs = 60 * 60_000L, maxFixes = 3)
        val base = 1_000_000_000_000L
        for (i in 0 until 5) t.add(Fix(base + i * 60_000L, 10.0 + i, 20.0, 100.0, 5f), now = base + i * 60_000L)
        assertEquals(3, t.size)
        t.prune(base + 2 * 60 * 60_000L)
        assertEquals(0, t.size)

        val u = LocationTrack()
        u.add(Fix(base, 50.85, 4.35, 56.5, 4.5f))
        u.add(Fix(base + 1, -33.9, 151.2))
        val copy = LocationTrack()
        copy.load(u.encode() + "\ngarbage\n1,2", now = base)
        assertEquals(2, copy.size)
        assertEquals(Fix(base, 50.85, 4.35, 56.5, 4.5f), copy.match(base))
    }

    @Test
    fun fixDecodingRejectsNonsense() {
        assertNull(Fix.decode("1,95,0,,"))
        assertNull(Fix.decode("1,0,190,,"))
        assertNull(Fix.decode("x,0,0,,"))
        assertEquals(Fix(5, 1.5, -2.5), Fix.decode("5,1.5,-2.5,,"))
    }

    @Test
    fun writesGpsIntoAJpeg() {
        val f = jpeg("2026:07:14 15:30:00")
        val track = LocationTrack()
        val fixTime = at(2026, 7, 14, 15, 27, 12)
        track.add(Fix(fixTime, 50.846777, -4.352439, 56.0, 4.5f), now = fixTime)

        val written = GeoExif.tag(ExifInterface(f.absolutePath), track, brussels)
        assertNotNull(written)

        val back = ExifInterface(f.absolutePath)
        val ll = back.latLong!!
        assertEquals(50.846777, ll[0], 1e-5)
        assertEquals(-4.352439, ll[1], 1e-5)
        assertEquals("N", back.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
        assertEquals("W", back.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF))
        assertEquals(56.0, back.getAltitude(0.0), 0.01)
        // The fix's own time, in UTC (Brussels is UTC+2 in July).
        assertEquals("2026:07:14", back.getAttribute(ExifInterface.TAG_GPS_DATESTAMP))
        assertEquals("13:27:12", back.getAttribute(ExifInterface.TAG_GPS_TIMESTAMP))
        assertEquals(4.5, back.getAttributeDouble(ExifInterface.TAG_GPS_H_POSITIONING_ERROR, 0.0), 0.01)
        // Everything else is still there.
        assertEquals("2026:07:14 15:30:00", back.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals("Canon", back.getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun southernHemisphereAndNoAltitude() {
        val f = jpeg("2026:01:02 03:04:05", offset = "+11:00")
        val track = LocationTrack()
        val t = at(2026, 1, 2, 3, 4, 0, ZoneId.of("+11:00"))
        track.add(Fix(t, -33.8568, 151.2153), now = t)
        assertNotNull(GeoExif.tag(ExifInterface(f.absolutePath), track, brussels))
        val back = ExifInterface(f.absolutePath)
        assertEquals(-33.8568, back.latLong!![0], 1e-5)
        assertEquals("S", back.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF))
        assertEquals("E", back.getAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF))
        assertNull(back.getAttribute(ExifInterface.TAG_GPS_ALTITUDE))
    }

    @Test
    fun leavesPhotosAloneWhenItShould() {
        val track = LocationTrack()
        val t = at(2026, 7, 14, 15, 30)
        track.add(Fix(t, 1.0, 2.0), now = t)
        // No capture time.
        assertNull(GeoExif.tag(ExifInterface(jpeg(null).absolutePath), track, brussels))
        tmp.delete(); tmp.create()
        // No fix close enough.
        assertNull(GeoExif.tag(ExifInterface(jpeg("2026:07:14 18:00:00").absolutePath), track, brussels))
        tmp.delete(); tmp.create()
        // The camera already wrote a position: kept.
        val f = jpeg("2026:07:14 15:30:00")
        ExifInterface(f.absolutePath).apply { setLatLong(10.0, 20.0); saveAttributes() }
        assertNull(GeoExif.tag(ExifInterface(f.absolutePath), track, brussels))
        assertEquals(10.0, ExifInterface(f.absolutePath).latLong!![0], 1e-5)
    }
}
