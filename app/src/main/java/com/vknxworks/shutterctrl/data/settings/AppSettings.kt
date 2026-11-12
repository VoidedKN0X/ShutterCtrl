package com.vknxworks.shutterctrl.data.settings

import android.content.Context
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vknxworks.shutterctrl.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException
import java.security.SecureRandom

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "shutterctrl")

/** How long ShutterCtrl can be in the background before the app lock asks again (-1: only after a restart). */
enum class LockTimeout(@StringRes val label: Int, val millis: Long) {
    IMMEDIATELY(R.string.lock_again_immediately, 0L),
    MINUTE_1(R.string.lock_again_1m, 60_000L),
    MINUTES_5(R.string.lock_again_5m, 5 * 60_000L),
    MINUTES_15(R.string.lock_again_15m, 15 * 60_000L),
    HOUR_1(R.string.lock_again_1h, 60 * 60_000L),
    RESTART(R.string.lock_again_restart, -1L),
}

data class AppSettings(
    /** The camera's address last used (only a private or link-local IP). */
    val lastHost: String = "",
    /** This phone's PTP/IP identity (16 random bytes, hex). The camera remembers it after pairing. */
    val guid: String = "",
    /** Self-timer in seconds (0, 2 or 10). */
    val selfTimer: Int = 0,
    val intervalShots: Int = 60,
    val intervalSec: Int = 10,
    val keepScreenOn: Boolean = true,
    val lockTimeout: LockTimeout = LockTimeout.IMMEDIATELY,
    val hideScreenshots: Boolean = false,
    val hideRecents: Boolean = false,
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val lastHost = stringPreferencesKey("last_host")
        val guid = stringPreferencesKey("guid")
        val selfTimer = intPreferencesKey("self_timer")
        val intervalShots = intPreferencesKey("interval_shots")
        val intervalSec = intPreferencesKey("interval_sec")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val lockTimeout = stringPreferencesKey("lock_timeout")
        val hideScreenshots = booleanPreferencesKey("hide_screenshots")
        val hideRecents = booleanPreferencesKey("hide_recents")
    }

    private val prefs: Flow<Preferences> = context.appDataStore.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: StateFlow<AppSettings> = prefs
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private fun Preferences.toSettings() = AppSettings(
        lastHost = this[K.lastHost].orEmpty(),
        guid = this[K.guid]?.takeIf { it.matches(Regex("[0-9a-f]{32}")) }.orEmpty(),
        selfTimer = (this[K.selfTimer] ?: 0).takeIf { it in listOf(0, 2, 10) } ?: 0,
        intervalShots = (this[K.intervalShots] ?: 60).coerceIn(1, 9999),
        intervalSec = (this[K.intervalSec] ?: 10).coerceIn(2, 3600),
        keepScreenOn = this[K.keepScreenOn] ?: true,
        lockTimeout = enumOr(this[K.lockTimeout], LockTimeout.IMMEDIATELY),
        hideScreenshots = this[K.hideScreenshots] ?: false,
        hideRecents = this[K.hideRecents] ?: false,
    )

    /** Saves a change. If the phone's storage fails, the change is dropped instead of closing the app. */
    suspend fun update(transform: (AppSettings) -> AppSettings) {
        try {
            context.appDataStore.edit { p ->
                val s = transform(p.toSettings())
                p[K.lastHost] = s.lastHost
                if (s.guid.isNotEmpty()) p[K.guid] = s.guid else p.remove(K.guid)
                p[K.selfTimer] = s.selfTimer
                p[K.intervalShots] = s.intervalShots
                p[K.intervalSec] = s.intervalSec
                p[K.keepScreenOn] = s.keepScreenOn
                p[K.lockTimeout] = s.lockTimeout.name
                p[K.hideScreenshots] = s.hideScreenshots
                p[K.hideRecents] = s.hideRecents
            }
        } catch (e: IOException) {
            // Nothing to do: the settings stay as they were.
        }
    }

    /** This phone's PTP/IP GUID, created on first use. */
    suspend fun guid(): ByteArray {
        var hex = prefs.first().toSettings().guid
        if (hex.isEmpty()) {
            hex = newGuid()
            update { it.copy(guid = hex) }
        }
        return ByteArray(16) { i -> hex.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    companion object {
        fun newGuid(): String = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    }
}

private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
    name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default
