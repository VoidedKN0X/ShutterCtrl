package com.vknxworks.shutterctrl.ptp

import java.io.ByteArrayOutputStream
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A simulated Canon camera behind a [UsbPipe], packet by packet: what the camera sends is cut into
 * [maxPacketSize] packets, a container that fills its last packet is followed by a zero-length packet
 * (unless [sendZlp] is false), and a bulk-in transfer collects packets until a short one, the way a USB
 * host controller does.
 */
class FakeUsbCamera(
    override val maxPacketSize: Int = 512,
    override val interruptPacketSize: Int = 8,
    private val sendZlp: Boolean = true,
) : UsbPipe {
    /** Objects on the "card": handle to bytes. */
    val objects = HashMap<Int, ByteArray>()
    var deviceInfo: ByteArray = ByteArray(0)

    /** Every command the camera received: (code, transaction id, params). */
    val commands = ArrayList<Triple<Int, Int, IntArray>>()
    /** Data phases the camera received, by operation code. */
    val dataIn = HashMap<Int, ByteArray>()
    /** Lengths of every bulk-out call, to check zero-length packets. */
    val outCalls = ArrayList<Int>()
    var cancels = ArrayList<Int>()
    var resets = 0
    var status = Rc.OK

    @Volatile private var attached = true
    @Volatile private var closed = false
    override val isAttached: Boolean get() = attached && !closed

    private val packets = ArrayDeque<ByteArray>()
    private val interrupt = ConcurrentLinkedQueue<ByteArray>()
    private val inbound = ByteArrayOutputStream()
    private var pendingCommand: PtpUsb.Header? = null
    private var pendingParams = IntArray(0)

    /** Operations that have a data phase from the phone. */
    private val dataOutOps = setOf(Op.EOS_SET_DEVICE_PROP_VALUE_EX, Op.SEND_OBJECT_INFO, Op.SEND_OBJECT, Op.EOS_UPDATE_FIRMWARE)

    fun unplug() {
        attached = false
    }

    /** Queues an event on the interrupt endpoint, cut into interrupt-sized packets. */
    fun event(code: Int, vararg params: Int) {
        val c = PtpUsb.container(PtpUsb.TYPE_EVENT, code, 0, params)
        c.toList().chunked(interruptPacketSize).forEach { interrupt.add(it.toByteArray()) }
    }

    // ---------- Camera side ----------

    private fun queue(container: ByteArray) {
        var off = 0
        while (container.size - off >= maxPacketSize) {
            packets.add(container.copyOfRange(off, off + maxPacketSize))
            off += maxPacketSize
        }
        if (off < container.size) packets.add(container.copyOfRange(off, container.size))
        else if (sendZlp) packets.add(ByteArray(0))
    }

    private fun respond(code: Int, tid: Int, vararg params: Int) = queue(PtpUsb.container(PtpUsb.TYPE_RESPONSE, code, tid, params))

    private fun sendData(code: Int, tid: Int, data: ByteArray) = queue(PtpUsb.dataHeader(code, tid, data.size.toLong()) + data)

    private fun handle(h: PtpUsb.Header, params: IntArray, data: ByteArray?) {
        val tid = h.transactionId
        when (h.code) {
            Op.GET_DEVICE_INFO -> { sendData(h.code, tid, deviceInfo); respond(Rc.OK, tid) }
            Op.GET_OBJECT -> {
                val o = objects[params[0]]
                if (o == null) respond(0x2009, tid) else { sendData(h.code, tid, o); respond(Rc.OK, tid) }
            }
            else -> {
                if (data != null) dataIn[h.code] = data
                respond(Rc.OK, tid)
            }
        }
    }

    /** Reassembles what the phone sent into containers. */
    private fun received() {
        while (true) {
            val bytes = inbound.toByteArray()
            if (bytes.size < PtpUsb.HEADER_SIZE) return
            val h = PtpUsb.parseHeader(bytes)
            val len = h.length.toInt()
            if (bytes.size < len) return
            inbound.reset()
            inbound.write(bytes, len, bytes.size - len)
            val payload = bytes.copyOfRange(PtpUsb.HEADER_SIZE, len)
            when (h.type) {
                PtpUsb.TYPE_COMMAND -> {
                    val params = PtpUsb.params(payload, 0, payload.size)
                    commands += Triple(h.code, h.transactionId, params)
                    if (h.code in dataOutOps) {
                        pendingCommand = h; pendingParams = params
                    } else {
                        handle(h, params, null)
                    }
                }
                PtpUsb.TYPE_DATA -> {
                    val cmd = pendingCommand ?: throw AssertionError("data without a command")
                    pendingCommand = null
                    handle(cmd, pendingParams, payload)
                }
            }
        }
    }

    // ---------- UsbPipe ----------

    override fun bulkOut(b: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        if (!isAttached) return -1
        outCalls += len
        inbound.write(b, off, len)
        received()
        return len
    }

    override fun bulkIn(b: ByteArray, off: Int, len: Int, timeoutMs: Int): Int {
        if (!isAttached) return -1
        if (len % maxPacketSize != 0) throw AssertionError("bulk-in length $len isn't a whole number of packets")
        if (packets.isEmpty()) return -1
        var got = 0
        while (got < len && packets.isNotEmpty()) {
            val p = packets.poll()
            p.copyInto(b, off + got)
            got += p.size
            if (p.size < maxPacketSize) break
        }
        return got
    }

    override fun interruptIn(b: ByteArray, len: Int, timeoutMs: Int): Int {
        if (!isAttached) return -1
        val p = interrupt.poll()
        if (p == null) {
            Thread.sleep(minOf(timeoutMs, 10).toLong())
            return -1
        }
        p.copyInto(b)
        return p.size
    }

    override fun cancelRequest(transactionId: Int): Boolean {
        cancels += transactionId
        packets.clear()
        return isAttached
    }

    override fun deviceStatus(): PtpUsb.DeviceStatus? = if (isAttached) PtpUsb.DeviceStatus(status, IntArray(0)) else null

    override fun deviceReset(): Boolean {
        resets++
        status = Rc.OK
        packets.clear()
        return true
    }

    override fun close() {
        closed = true
    }
}
