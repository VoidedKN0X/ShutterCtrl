package com.vknxworks.shutterctrl.ptp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP over USB (the USB Still Image Capture Device class, PIMA 15740 annex D). Every container is
 * length (u32 LE, including the 12-byte header), type (u16: 1 command, 2 data, 3 response, 4 event),
 * code (u16), transaction id (u32), then parameters or data. Pure Kotlin so it can be unit-tested on the JVM.
 */
object PtpUsb {
    const val TYPE_COMMAND = 1
    const val TYPE_DATA = 2
    const val TYPE_RESPONSE = 3
    const val TYPE_EVENT = 4

    const val HEADER_SIZE = 12

    /** Length field of a data container larger than 4 GiB: read until the transfer ends with a short packet. */
    const val UNKNOWN_LENGTH = 0xFFFFFFFFL

    /**
     * Commands, responses and events carry at most 5 parameters (32 bytes); a little slack for cameras that
     * pad them. Anything longer is a broken stream.
     */
    const val MAX_SMALL_CONTAINER = 64

    // Interface class of a PTP camera: Still Image, subclass 1, protocol 1.
    const val CLASS_STILL_IMAGE = 6
    const val SUBCLASS_STILL_IMAGE = 1
    const val PROTOCOL_PTP = 1

    const val VENDOR_CANON = 0x04A9

    // Class-specific control requests (bRequest).
    const val REQ_CANCEL = 0x64
    const val REQ_GET_EXTENDED_EVENT_DATA = 0x65
    const val REQ_DEVICE_RESET = 0x66
    const val REQ_GET_DEVICE_STATUS = 0x67

    /** bmRequestType: class request to the interface, host to device / device to host. */
    const val REQUEST_TYPE_OUT = 0x21
    const val REQUEST_TYPE_IN = 0xA1

    /** Cancel request data: Cancellation code 0x4001, then the transaction id. */
    const val CANCEL_CODE = 0x4001

    class Header(val length: Long, val type: Int, val code: Int, val transactionId: Int) {
        /** Bytes after the header, or -1 when the length isn't known (data over 4 GiB). */
        val payloadLength: Long get() = if (length == UNKNOWN_LENGTH) -1 else length - HEADER_SIZE
    }

    /** A command, response or event container. */
    fun container(type: Int, code: Int, transactionId: Int, params: IntArray = IntArray(0)): ByteArray {
        require(params.size <= 5) { "PTP allows at most 5 parameters" }
        val len = HEADER_SIZE + 4 * params.size
        val b = le(len).putInt(len).putShort(type.toShort()).putShort(code.toShort()).putInt(transactionId)
        params.forEach { b.putInt(it) }
        return b.array()
    }

    fun command(code: Int, transactionId: Int, params: IntArray = IntArray(0)): ByteArray =
        container(TYPE_COMMAND, code, transactionId, params)

    /** The 12-byte header of a data container carrying [payloadLength] bytes. */
    fun dataHeader(code: Int, transactionId: Int, payloadLength: Long): ByteArray {
        val total = HEADER_SIZE + payloadLength
        val field = if (total >= UNKNOWN_LENGTH) -1 else total.toInt()
        return le(HEADER_SIZE).putInt(field).putShort(TYPE_DATA.toShort()).putShort(code.toShort()).putInt(transactionId).array()
    }

    fun parseHeader(b: ByteArray, off: Int = 0): Header {
        val bb = ByteBuffer.wrap(b, off, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.int.toLong() and 0xFFFFFFFFL
        val type = bb.short.toInt() and 0xFFFF
        val code = bb.short.toInt() and 0xFFFF
        val tid = bb.int
        if (length < HEADER_SIZE) throw PtpException("Bad USB container length $length")
        if (type !in TYPE_COMMAND..TYPE_EVENT) throw PtpException("Bad USB container type $type")
        if (type != TYPE_DATA && length > MAX_SMALL_CONTAINER) throw PtpException("USB container too long ($length) for type $type")
        return Header(length, type, code, tid)
    }

    /** The u32 parameters after a header. */
    fun params(b: ByteArray, off: Int, len: Int): IntArray {
        val bb = ByteBuffer.wrap(b, off, len).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(len / 4) { bb.int }
    }

    /**
     * A transfer that is an exact multiple of the endpoint's packet size has no short packet to end it,
     * so the sender adds a zero-length packet.
     */
    fun needsZeroLengthPacket(totalLength: Long, maxPacketSize: Int): Boolean =
        maxPacketSize > 0 && totalLength > 0 && totalLength % maxPacketSize == 0L

    fun cancelRequestData(transactionId: Int): ByteArray =
        le(6).putShort(CANCEL_CODE.toShort()).putInt(transactionId).array()

    class DeviceStatus(val code: Int, val params: IntArray)

    /** Get Device Status answer: wLength (u16), code (u16), then u32 parameters. */
    fun parseDeviceStatus(b: ByteArray, n: Int): DeviceStatus? {
        if (n < 4) return null
        val bb = ByteBuffer.wrap(b, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        val wLength = bb.short.toInt() and 0xFFFF
        val code = bb.short.toInt() and 0xFFFF
        val count = ((minOf(wLength, n) - 4).coerceAtLeast(0)) / 4
        return DeviceStatus(code, IntArray(count) { bb.int })
    }

    private fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
}

class PtpEvent(val code: Int, val transactionId: Int, val params: IntArray)

/**
 * Puts interrupt-endpoint transfers back together into event containers: an event can be longer than one
 * packet on slow endpoints. Anything that doesn't look like an event is dropped so the stream resyncs.
 */
class UsbEventAssembler {
    private val pending = ByteArrayOutputStream()

    fun feed(b: ByteArray, n: Int): List<PtpEvent> {
        if (n <= 0) return emptyList()
        pending.write(b, 0, n)
        val out = ArrayList<PtpEvent>()
        while (true) {
            val bytes = pending.toByteArray()
            if (bytes.size < PtpUsb.HEADER_SIZE) break
            val h = runCatching { PtpUsb.parseHeader(bytes) }.getOrNull()
            if (h == null || h.type != PtpUsb.TYPE_EVENT) {
                pending.reset()
                break
            }
            val len = h.length.toInt()
            if (bytes.size < len) break
            out += PtpEvent(h.code, h.transactionId, PtpUsb.params(bytes, PtpUsb.HEADER_SIZE, len - PtpUsb.HEADER_SIZE))
            pending.reset()
            if (bytes.size > len) pending.write(bytes, len, bytes.size - len)
        }
        return out
    }
}

/**
 * The USB endpoints of one camera, as [PtpUsbClient] needs them. The Android implementation is
 * `usb/AndroidUsbPipe.kt`; the unit tests use a simulated camera.
 */
interface UsbPipe : Closeable {
    /** Packet size of the bulk endpoints (512 on high-speed USB). */
    val maxPacketSize: Int

    /** Packet size of the interrupt endpoint, or 0 when the camera has none. */
    val interruptPacketSize: Int

    /** False once the cable was pulled or the pipe was closed. */
    val isAttached: Boolean

    /** Sends [len] bytes (0 sends a zero-length packet). Returns the bytes sent, or -1. */
    fun bulkOut(b: ByteArray, off: Int, len: Int, timeoutMs: Int): Int

    /**
     * One bulk-in transfer of at most [len] bytes (a multiple of [maxPacketSize]). It ends early at a short or
     * zero-length packet. Returns the bytes read (0 for a zero-length packet), or -1 on timeout or error.
     */
    fun bulkIn(b: ByteArray, off: Int, len: Int, timeoutMs: Int): Int

    /** One interrupt-in transfer (events). Returns the bytes read, or -1 on timeout or error. */
    fun interruptIn(b: ByteArray, len: Int, timeoutMs: Int): Int

    /** Class request Cancel (0x64) for [transactionId]. */
    fun cancelRequest(transactionId: Int): Boolean

    /** Class request Get Device Status (0x67), or null when the camera doesn't answer it. */
    fun deviceStatus(): PtpUsb.DeviceStatus?

    /** Class request Device Reset (0x66): the camera drops any half-done transaction and its session. */
    fun deviceReset(): Boolean
}
