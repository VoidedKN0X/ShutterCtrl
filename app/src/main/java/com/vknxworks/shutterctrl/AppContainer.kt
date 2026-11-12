package com.vknxworks.shutterctrl

import android.content.Context
import com.vknxworks.shutterctrl.camera.CameraSession
import com.vknxworks.shutterctrl.camera.IntervalService
import com.vknxworks.shutterctrl.data.lock.LockVault
import com.vknxworks.shutterctrl.data.settings.AppSettings
import com.vknxworks.shutterctrl.data.settings.SettingsRepository
import com.vknxworks.shutterctrl.net.CameraNetwork
import com.vknxworks.shutterctrl.ui.lock.AppLock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext

/** Manual dependency container; one per process. */
class AppContainer(val context: Context) {
    /** Background work that outlives a screen. A failure there must never close the app. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, _ -> })

    val settings = SettingsRepository(context, appScope)

    /** The phone's Wi-Fi, so camera sockets go to the camera even on its own access point. */
    val network = CameraNetwork(context).also { it.start() }

    /** The one camera connection. */
    val session = CameraSession(context, appScope, network)

    /** The app lock's PIN and fingerprint key (the shared Ctrl series lock). */
    val vault = LockVault(context, "shutterctrl")

    init {
        AppLock.onLocked = { onLocked() }
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
