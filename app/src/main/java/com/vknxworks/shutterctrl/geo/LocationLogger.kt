package com.vknxworks.shutterctrl.geo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.vknxworks.shutterctrl.data.crypto.DataKeyStore
import com.vknxworks.shutterctrl.data.crypto.KeystoreWrapper
import com.vknxworks.shutterctrl.ui.common.hasPermission
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * "Geotag downloads" (off until turned on): while it's on and ShutterCtrl is open, the phone's position is
 * written to a short log (the last 24 hours, in ShutterCtrl's private no-backup storage, encrypted at rest:
 * see [LocationLogFile]). Downloaded JPEG photos then get the position nearest to their capture time. Turning
 * the option off stops the updates and deletes the log. Positions come from Android's own LocationManager;
 * nothing is sent anywhere.
 *
 * The log file and its key are only touched on this class's own background thread, never on the main thread.
 */
class LocationLogger(private val context: Context) {
    private val lm = context.getSystemService(LocationManager::class.java)
    private val log = LocationLogFile(context.noBackupFilesDir, DataKeyStore(context.filesDir, KeystoreWrapper(KEY_ALIAS)))
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "ShutterCtrl-geo") }
    val track = LocationTrack()
    private var listening = false
    @Volatile private var enabled = false
    private var foreground = false
    private var loaded = false
    /** Goes up every time the option is turned off, so a log that is still being read is not added after that. */
    private var generation = 0

    private val listener = LocationListener { loc -> record(loc) }

    val permitted: Boolean
        get() = context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) || context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** Only approximate location was allowed (the positions may be off by a kilometre or more). */
    val approximateOnly: Boolean
        get() = !context.hasPermission(Manifest.permission.ACCESS_FINE_LOCATION) && context.hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)

    /** The setting changed. Turning it off forgets every position. */
    @Synchronized
    fun setEnabled(on: Boolean) {
        enabled = on
        if (!on) {
            stop()
            generation++
            track.clear()
            io.execute { log.delete() }
        }
        update()
    }

    /** ShutterCtrl came to the screen or left it. */
    @Synchronized
    fun setForeground(fg: Boolean) {
        foreground = fg
        update()
    }

    /** Call after the location permission was granted. */
    @Synchronized
    fun permissionChanged() = update()

    @Synchronized
    private fun update() {
        if (enabled && foreground && permitted) start() else stop()
    }

    @SuppressLint("MissingPermission")
    private fun start() {
        if (listening || lm == null) return
        if (!loaded) {
            loaded = true
            val startedIn = generation
            io.execute {
                val saved = runCatching { log.read() }.getOrNull()
                if (saved != null) {
                    synchronized(this@LocationLogger) {
                        if (enabled && startedIn == generation) track.merge(saved, System.currentTimeMillis())
                    }
                }
            }
        }
        val request = LocationRequest.Builder(30_000L).setMinUpdateDistanceMeters(10f).setMinUpdateIntervalMillis(10_000L).build()
        val providers = if (lm.hasProvider(LocationManager.FUSED_PROVIDER)) listOf(LocationManager.FUSED_PROVIDER)
        else listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.hasProvider(it) }
        for (p in providers) {
            runCatching { lm.requestLocationUpdates(p, request, io, listener) }
            runCatching { lm.getLastKnownLocation(p)?.let { if (System.currentTimeMillis() - it.time < 10 * 60_000L) record(it) } }
        }
        listening = true
    }

    private fun stop() {
        if (!listening) return
        runCatching { lm?.removeUpdates(listener) }
        listening = false
    }

    private fun record(loc: Location) {
        val now = System.currentTimeMillis()
        if (!enabled) return
        track.add(
            Fix(loc.time.takeIf { it > 0 } ?: now, loc.latitude, loc.longitude, loc.altitude.takeIf { loc.hasAltitude() }, loc.accuracy.takeIf { loc.hasAccuracy() }),
            now,
        )
        io.execute { runCatching { if (enabled) log.write(track.encode()) } }
    }

    /**
     * Geotags a downloaded photo (JPEG only: Android can't write EXIF into RAW files) in place, while it's
     * still pending in the media library. True when a position was written.
     */
    fun tag(uri: Uri, mime: String): Boolean {
        if (!enabled || mime != "image/jpeg") return false
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                GeoExif.tag(ExifInterface(pfd.fileDescriptor), track, ZoneId.systemDefault()) != null
            } ?: false
        }.getOrDefault(false)
    }

    private companion object {
        /** The Android Keystore key that wraps the data key (see data/crypto). */
        const val KEY_ALIAS = "shutterctrl_data_at_rest"
    }
}
