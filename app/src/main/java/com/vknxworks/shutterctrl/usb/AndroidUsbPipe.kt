package com.vknxworks.shutterctrl.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.vknxworks.shutterctrl.ptp.PtpUsb
import com.vknxworks.shutterctrl.ptp.UsbOpenException
import com.vknxworks.shutterctrl.ptp.UsbPipe

/**
 * The camera's PTP interface through Android's USB host API: bulk-in, bulk-out and interrupt-in endpoints,
 * and the class requests on endpoint 0.
 *
 * Closing waits for transfers that are still running on other threads before the connection is released,
 * so a transfer never runs on a closed connection.
 */
class AndroidUsbPipe private constructor(
    private val manager: UsbManager,
    private val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val epEvent: UsbEndpoint?,
) : UsbPipe {

    override val maxPacketSize: Int = minOf(epIn.maxPacketSize, epOut.maxPacketSize).coerceAtLeast(8)
    override val interruptPacketSize: Int = epEvent?.maxPacketSize ?: 0

    private val lock = Any()
    private var users = 0
    private var closing = false
    private var released = false
    @Volatile private var gone = false

    override val isAttached: Boolean get() = !gone && !closing

    /** Runs one transfer unless the pipe is closing; the last transfer out releases the connection. */
    private inline fun use(block: () -> Int): Int {
        synchronized(lock) {
            if (closing) return -1
            users++
        }
        try {
            val n = block()
            // -1 is a timeout or an error; tell them apart by whether the camera is still attached.
            if (n < 0 && !manager.deviceList.containsKey(device.deviceName)) gone = true
            return n
        } finally {
            synchronized(lock) {
                users--
                if (closing && users == 0) release()
            }
        }
    }

    override fun bulkOut(b: ByteArray, off: Int, len: Int, timeoutMs: Int): Int = use { conn.bulkTransfer(epOut, b, off, len, timeoutMs) }

    override fun bulkIn(b: ByteArray, off: Int, len: Int, timeoutMs: Int): Int = use { conn.bulkTransfer(epIn, b, off, len, timeoutMs) }

    override fun interruptIn(b: ByteArray, len: Int, timeoutMs: Int): Int {
        val ep = epEvent ?: return -1
        return use { conn.bulkTransfer(ep, b, 0, len, timeoutMs) }
    }

    override fun cancelRequest(transactionId: Int): Boolean {
        val data = PtpUsb.cancelRequestData(transactionId)
        return use { conn.controlTransfer(PtpUsb.REQUEST_TYPE_OUT, PtpUsb.REQ_CANCEL, 0, iface.id, data, data.size, CONTROL_TIMEOUT_MS) } >= 0
    }

    override fun deviceStatus(): PtpUsb.DeviceStatus? {
        val b = ByteArray(64)
        val n = use { conn.controlTransfer(PtpUsb.REQUEST_TYPE_IN, PtpUsb.REQ_GET_DEVICE_STATUS, 0, iface.id, b, b.size, CONTROL_TIMEOUT_MS) }
        return PtpUsb.parseDeviceStatus(b, n)
    }

    override fun deviceReset(): Boolean =
        use { conn.controlTransfer(PtpUsb.REQUEST_TYPE_OUT, PtpUsb.REQ_DEVICE_RESET, 0, iface.id, null, 0, CONTROL_TIMEOUT_MS) } >= 0

    override fun close() {
        synchronized(lock) {
            if (closing) return
            closing = true
            if (users == 0) release()
        }
    }

    private fun release() {
        if (released) return
        released = true
        runCatching { conn.releaseInterface(iface) }
        runCatching { conn.close() }
    }

    companion object {
        private const val CONTROL_TIMEOUT_MS = 2_000

        /** The camera's PTP interface: Still Image class 6, subclass 1, protocol 1 (or any class 6 interface). */
        fun ptpInterface(device: UsbDevice): UsbInterface? {
            val all = (0 until device.interfaceCount).map { device.getInterface(it) }
            return all.firstOrNull {
                it.interfaceClass == PtpUsb.CLASS_STILL_IMAGE && it.interfaceSubclass == PtpUsb.SUBCLASS_STILL_IMAGE &&
                    it.interfaceProtocol == PtpUsb.PROTOCOL_PTP
            } ?: all.firstOrNull { it.interfaceClass == PtpUsb.CLASS_STILL_IMAGE }
        }

        /** Opens the camera's PTP interface. Needs the user's USB permission for [device]. */
        fun open(manager: UsbManager, device: UsbDevice): AndroidUsbPipe {
            if (!manager.hasPermission(device)) throw UsbOpenException("No permission for this USB device")
            val iface = ptpInterface(device) ?: throw UsbOpenException("This USB device has no camera (PTP) interface")
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            var epEvent: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(i)
                when {
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN -> if (epIn == null) epIn = ep
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT -> if (epOut == null) epOut = ep
                    ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN -> if (epEvent == null) epEvent = ep
                }
            }
            if (epIn == null || epOut == null) throw UsbOpenException("The camera's USB interface has no bulk endpoints")
            val conn = manager.openDevice(device) ?: throw UsbOpenException("Couldn't open the USB device")
            // force = true: take the interface from the system's own MTP/PTP handler (e.g. the Files app).
            if (!conn.claimInterface(iface, true)) {
                conn.close()
                throw UsbOpenException("Couldn't claim the camera's USB interface")
            }
            return AndroidUsbPipe(manager, device, conn, iface, epIn, epOut, epEvent)
        }
    }
}
