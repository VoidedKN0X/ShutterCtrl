package com.vknxworks.shutterctrl

import android.content.Context
import com.vknxworks.shutterctrl.ble.BleRemote
import com.vknxworks.shutterctrl.camera.AutoDownload
import com.vknxworks.shutterctrl.camera.CameraSession
import com.vknxworks.shutterctrl.camera.Downloads
import com.vknxworks.shutterctrl.camera.IntervalService
import com.vknxworks.shutterctrl.data.lock.LockVault
import com.vknxworks.shutterctrl.data.settings.AppSettings
import com.vknxworks.shutterctrl.data.settings.SettingsRepository
import com.vknxworks.shutterctrl.geo.LocationLogger
import com.vknxworks.shutterctrl.net.CameraNetwork
import com.vknxworks.shutterctrl.ui.lock.AppLock
import com.vknxworks.shutterctrl.usb.UsbCameras
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    /** Background work that outlives a screen. A failure there must never close the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(context, appScope)

    /** The phone's Wi-Fi, so camera sockets go to the camera even on its own access point. */
    val network = CameraNetwork(context).also { it.start() }

    /** Canon cameras on a USB cable (only listed; nothing is opened until you tap one). */
    val usb = UsbCameras(context)

    /** Geotag downloads: the phone's short location log, only while the option is on. */
    val geo = LocationLogger(context)

    /** The one camera connection, over Wi-Fi or USB. */
    val session = CameraSession(context, appScope, network, usb, geotag = { uri, mime -> geo.tag(uri, mime) }).also { usb.start() }

    /** One download at a time, from the Card tab or by themselves while you shoot (auto-download). */
    val downloads = Downloads(session, appScope)

    /** The camera as a Bluetooth remote (only when you pair or connect it). */
    val ble = BleRemote(context, appScope)

    /** The app lock's PIN and fingerprint key (the shared Ctrl series lock). */
    val vault = LockVault(context, "shutterctrl")

    init {
        AppLock.onLocked = { onLocked() }
        appScope.launch { settings.settings.map { it.geotag }.distinctUntilChanged().collect { geo.setEnabled(it) } }
        // With auto-download on, new photos are picked up as quickly in the background as in front.
        session.wantsQuickEvents = { settings.settings.value.autoDownload != AutoDownload.OFF }
        // Auto-download: every new photo the camera reports that matches the choice is queued.
        appScope.launch {
            session.newObjects.collect { info -> if (settings.settings.value.autoDownload.wants(info)) downloads.enqueue(info) }
        }
    }

    suspend fun enableLock(pin: String) {
        AppLock.markUnlocked()
        withContext(Dispatchers.Default) { vault.setUp(pin) }
    }

    fun disableLock() {
        vault.disable()
    }

    /**
     * "Forgot PIN?": the PIN can't be recovered, so ShutterCtrl disconnects, resets its settings (including
     * this phone's pairing identity) and turns the lock off. Photos you downloaded are in your library and
     * aren't touched.
     */
    suspend fun forgetEverything() {
        IntervalService.stop(context)
        ble.disconnect()
        session.disconnect()
        settings.update { AppSettings() }
        vault.disable()
        AppLock.markUnlocked()
    }

    fun onLocked() {
        vault.close()
    }

    fun onUnlocked() {
        AppLock.markUnlocked()
    }
}

val Context.container: AppContainer
    get() = (applicationContext as ShutterCtrlApp).container
