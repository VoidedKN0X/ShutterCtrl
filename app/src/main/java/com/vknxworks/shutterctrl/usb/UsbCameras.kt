package com.vknxworks.shutterctrl.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.vknxworks.shutterctrl.ptp.PtpUsb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Canon cameras attached by USB cable. The list always comes from [UsbManager] itself; attach/detach
 * broadcasts and the "camera plugged in" activity intent only make ShutterCtrl look again, their extras
 * are never trusted.
 */
class UsbCameras(private val context: Context) {

    class Camera(val device: UsbDevice, val name: String) {
        val key: String get() = device.deviceName
    }

    private val manager: UsbManager? = context.getSystemService(UsbManager::class.java)

    private val _cameras = MutableStateFlow<List<Camera>>(emptyList())
    val cameras: StateFlow<List<Camera>> = _cameras

    /** Called (main thread) after every change with the keys of the cameras still attached. */
    var onChanged: ((Set<String>) -> Unit)? = null

    private var pending: Pair<String, (Boolean) -> Unit>? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                ACTION_PERMISSION -> {
                    // The PendingIntent is immutable, so the answer isn't in the extras: ask UsbManager.
                    val (key, result) = pending ?: return
                    pending = null
                    val m = manager ?: return result(false)
                    val d = m.deviceList[key]
                    result(d != null && m.hasPermission(d))
                }
                else -> refresh()
            }
        }
    }

    fun start() {
        if (manager == null) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        // Not exported: only the system (attach/detach) and ShutterCtrl's own PendingIntent reach it.
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() {
        val m = manager ?: return
        val list = runCatching { m.deviceList.values.toList() }.getOrDefault(emptyList())
            .filter { isCanonCamera(it) }
            .map { d -> Camera(d, runCatching { d.productName }.getOrNull()?.trim().orEmpty()) }
        _cameras.value = list
        onChanged?.invoke(list.mapTo(HashSet()) { it.key })
    }

    fun isAttached(key: String): Boolean = manager?.deviceList?.containsKey(key) == true

    /** Opens [camera]'s PTP interface (permission must be granted). Blocking. */
    fun open(camera: Camera): AndroidUsbPipe {
        val m = manager ?: throw com.vknxworks.shutterctrl.ptp.UsbOpenException("This phone has no USB host support")
        return AndroidUsbPipe.open(m, camera.device)
    }

    /**
     * Asks the user for access to [camera] (Android's own dialog), unless ShutterCtrl already has it.
     * [result] runs on the main thread.
     */
    fun requestPermission(camera: Camera, result: (Boolean) -> Unit) {
        val m = manager ?: return result(false)
        if (m.hasPermission(camera.device)) return result(true)
        pending?.second?.invoke(false)
        pending = camera.key to result
        val intent = Intent(ACTION_PERMISSION).setPackage(context.packageName)
        val pi = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        m.requestPermission(camera.device, pi)
    }

    companion object {
        private const val ACTION_PERMISSION = "com.vknxworks.shutterctrl.USB_PERMISSION"

        fun isCanonCamera(d: UsbDevice): Boolean =
            d.vendorId == PtpUsb.VENDOR_CANON && AndroidUsbPipe.ptpInterface(d) != null
    }
}
