package com.vknxworks.shutterctrl.data.settings

import androidx.annotation.StringRes
import com.vknxworks.shutterctrl.R
import com.vknxworks.shutterctrl.camera.AutoDownload
import com.vknxworks.shutterctrl.net.LocalAddress
import com.vknxworks.shutterctrl.ptp.Bracketing
import com.vknxworks.shutterctrl.ptp.FocusStack
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ShutterCtrl's settings as a JSON file you keep: shooting, intervalometer, bracketing, focus stacking, bulb,
 * live view, auto-download and the camera's last address. Never in the file: this phone's PTP/IP identity (the
 * camera trusts it after pairing), the paired Bluetooth camera, geotagging (it turns on location) and the app
 * lock and privacy settings, so a restore can't pair, track or unlock anything.
 */
@Serializable
data class SettingsBackup(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val appVersion: String? = null,
    val createdAt: String? = null,
    val settings: BackupSettings = BackupSettings(),
) {
    companion object {
        const val FORMAT = "shutterctrl-settings"
        const val VERSION = 1
    }
}

/** Every field is optional, so files from older or newer versions restore what they can. */
@Serializable
data class BackupSettings(
    val lastHost: String? = null,
    val selfTimer: Int? = null,
    val intervalShots: Int? = null,
    val intervalSec: Int? = null,
    val keepScreenOn: Boolean? = null,
    val bracketShots: Int? = null,
    val bracketStepThirds: Int? = null,
    val stackShots: Int? = null,
    val stackStep: Int? = null,
    val stackDrives: Int? = null,
    val stackTowardFar: Boolean? = null,
    val autoDownload: String? = null,
    val bulbSeconds: Int? = null,
    val liveHistogram: Boolean? = null,
    val liveGrid: Boolean? = null,
)

/** Thrown for a file that isn't a ShutterCtrl settings file, or one from a newer, incompatible version. */
class BackupFormatException(val newer: Boolean) : Exception()

object SettingsBackups {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun export(s: AppSettings, appVersion: String, now: String): String =
        json.encodeToString(
            SettingsBackup.serializer(),
            SettingsBackup(
                appVersion = appVersion,
                createdAt = now,
                settings = BackupSettings(
                    lastHost = s.lastHost.ifBlank { null },
                    selfTimer = s.selfTimer,
                    intervalShots = s.intervalShots,
                    intervalSec = s.intervalSec,
                    keepScreenOn = s.keepScreenOn,
                    bracketShots = s.bracketShots,
                    bracketStepThirds = s.bracketStepThirds,
                    stackShots = s.stackShots,
                    stackStep = s.stackStep,
                    stackDrives = s.stackDrives,
                    stackTowardFar = s.stackTowardFar,
                    autoDownload = s.autoDownload.name,
                    bulbSeconds = s.bulbSeconds,
                    liveHistogram = s.liveHistogram,
                    liveGrid = s.liveGrid,
                ),
            ),
        )

    /** Reads and checks a settings file. */
    fun parse(text: String): SettingsBackup {
        val b = runCatching { json.decodeFromString(SettingsBackup.serializer(), text) }.getOrNull()
            ?: throw BackupFormatException(newer = false)
        if (b.format != SettingsBackup.FORMAT) throw BackupFormatException(newer = false)
        if (b.version > SettingsBackup.VERSION) throw BackupFormatException(newer = true)
        return b
    }

    /**
     * [cur] with the settings from [b]. A missing or invalid value keeps the current one; an address that isn't
     * on a local network is ignored (ShutterCtrl only ever talks to local addresses).
     */
    fun applied(cur: AppSettings, b: BackupSettings): AppSettings = cur.copy(
        lastHost = b.lastHost?.trim()?.takeIf { LocalAddress.parse(it) != null } ?: cur.lastHost,
        selfTimer = b.selfTimer?.takeIf { it in listOf(0, 2, 10) } ?: cur.selfTimer,
        intervalShots = b.intervalShots?.takeIf { it in 1..9999 } ?: cur.intervalShots,
        intervalSec = b.intervalSec?.takeIf { it in 2..3600 } ?: cur.intervalSec,
        keepScreenOn = b.keepScreenOn ?: cur.keepScreenOn,
        bracketShots = b.bracketShots?.takeIf { it in Bracketing.SHOT_COUNTS } ?: cur.bracketShots,
        bracketStepThirds = b.bracketStepThirds?.takeIf { it in Bracketing.STEP_THIRDS } ?: cur.bracketStepThirds,
        stackShots = b.stackShots?.takeIf { it in FocusStack.SHOT_COUNTS } ?: cur.stackShots,
        stackStep = b.stackStep?.takeIf { it in 1..3 } ?: cur.stackStep,
        stackDrives = b.stackDrives?.takeIf { it in 1..20 } ?: cur.stackDrives,
        stackTowardFar = b.stackTowardFar ?: cur.stackTowardFar,
        autoDownload = b.autoDownload?.let { n -> AutoDownload.entries.firstOrNull { it.name == n } } ?: cur.autoDownload,
        bulbSeconds = b.bulbSeconds?.takeIf { it in BULB_CHOICES } ?: cur.bulbSeconds,
        liveHistogram = b.liveHistogram ?: cur.liveHistogram,
        liveGrid = b.liveGrid ?: cur.liveGrid,
    )

    /** The names (string resources) of the settings that differ between [a] and [b], shown before a restore. */
    fun changed(a: AppSettings, b: AppSettings): List<Int> = NAMES.filter { (get, _) -> get(a) != get(b) }.map { it.second }

    private val NAMES: List<Pair<(AppSettings) -> Any?, Int>> = listOf(
        name(R.string.backup_item_last_host) { it.lastHost },
        name(R.string.self_timer) { it.selfTimer },
        name(R.string.backup_item_interval) { it.intervalShots to it.intervalSec },
        name(R.string.keep_screen_on) { it.keepScreenOn },
        name(R.string.backup_item_bracket) { it.bracketShots to it.bracketStepThirds },
        name(R.string.backup_item_stack) { listOf(it.stackShots, it.stackStep, it.stackDrives, it.stackTowardFar) },
        name(R.string.auto_download) { it.autoDownload },
        name(R.string.bulb_time) { it.bulbSeconds },
        name(R.string.live_histogram) { it.liveHistogram },
        name(R.string.live_grid) { it.liveGrid },
    )

    private fun name(@StringRes res: Int, get: (AppSettings) -> Any?): Pair<(AppSettings) -> Any?, Int> = get to res
}
