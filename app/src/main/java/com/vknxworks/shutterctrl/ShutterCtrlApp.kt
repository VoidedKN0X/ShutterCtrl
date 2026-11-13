package com.vknxworks.shutterctrl

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.vknxworks.shutterctrl.ui.lock.AppLock

class ShutterCtrlApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Debug builds only: 127.0.0.1 for testing against a simulated camera over `adb reverse`.
        com.vknxworks.shutterctrl.net.LocalAddress.allowLoopback = BuildConfig.DEBUG
        container = AppContainer(this)
        // The app lock: locks after the chosen time away ("Lock again").
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppLock.onForeground(container.vault.enabled, container.settings.settings.value.lockTimeout.millis)
                container.geo.setForeground(true)
            }

            override fun onStop(owner: LifecycleOwner) {
                AppLock.onBackground(container.vault.enabled, container.settings.settings.value.lockTimeout.millis)
                container.geo.setForeground(false)
            }
        })
    }
}
