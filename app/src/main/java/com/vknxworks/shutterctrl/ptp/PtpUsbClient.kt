package com.vknxworks.shutterctrl.ptp

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The USB transport: PTP containers over the camera's bulk-out and bulk-in endpoints, events on its
 * interrupt endpoint. Blocking; call from a background thread. One transaction at a time.
 *
 * Bulk-in is read as a stream of transfers, so a container split over several transfers, several
 * containers in one transfer, and zero-length packets after a container that fills its last packet are
 * all handled the same way.
 */
class PtpUsbClient(
    private val pipe: UsbPipe,
    override val cameraName: String = "",
    /** One bulk transfer's timeout. */
    private val transferTimeoutMs: Int = 5_000,
    /** How long the camera may stay silent while we wait for data or a response. */
    private val answerTimeoutMs: Long = 60_000,
) : PtpTransport {

    override var onEvent: ((Int, IntArray) -> Unit)? = null
    override var onClosed: ((Throwable?) -> Unit)? = null

    private val closeLock = Any()
    @Volatile private var closed = false
    override val isOpen get() = !closed

    private var lastTid = 0
    private var sessionOpen = false

    /** Transfer size: large, and a whole number of packets so only the last transfer of a container is short. */
    private val chunk = (CHUNK / pipe.maxPacketSize.coerceAtLeast(1)).coerceAtLeast(1) * pipe.maxPacketSize.coerceAtLeast(1)
    private val inBuf = ByteArray(chunk)
    private var inPos = 0
    private var inEnd = 0
    private val outBuf = ByteArray(chunk)
    private var eventThread: Thread? = null

    /** Something on the phone's side failed mid-transaction (the file, the storage): cancel, keep the session. */
    private class LocalFailure(cause: Throwable) : Exception(cause)

    /**
     * Checks the camera is idle (Get Device Status) and resets it if a previous app left a transaction
     * half done, then starts listening for events.
     */
    fun start() {
        val status = runCatching { pipe.deviceStatus() }.getOrNull()
        if (status != null && status.code != Rc.OK) runCatching { pipe.deviceReset() }
        if (pipe.interruptPacketSize > 0) {
            eventThread = Thread({ eventLoop() }, "ptpusb-events").apply { isDaemon = true; start() }
        }
    }

    private fun eventLoop() {
        val size = pipe.interruptPacketSize
        val b = ByteArray(size)
        val assembler = UsbEventAssembler()
        while (!closed) {
            val n = pipe.interruptIn(b, size, 500)
            if (closed) return
            if (n > 0) {
                for (e in assembler.feed(b, n)) runCatching { onEvent?.invoke(e.code, e.params) }
            } else if (!pipe.isAttached) {
                fail(UsbDetachedException())
                return
            }
        }
    }

    // ---------- Transactions ----------

    @Synchronized
    override fun transactTo(
        op: Int,
        params: IntArray,
        dataOut: ByteArray?,
        sink: (ByteArray, Int, Int) -> Unit,
        progress: ((Long, Long) -> Unit)?,
    ): PtpResult = transaction(op) { tid ->
        send(PtpUsb.command(op, tid, params))
        if (dataOut != null) sendData(op, tid, dataOut.size.toLong(), ByteArrayInputStream(dataOut), null)
        receive(tid, sink, progress)
    }

    @Synchronized
    override fun transactStreamOut(op: Int, params: IntArray, total: Long, source: InputStream, progress: ((Long, Long) -> Unit)?): PtpResult =
        transaction(op) { tid ->
            send(PtpUsb.command(op, tid, params))
            sendData(op, tid, total, source, progress)
            receive(tid, { _, _, _ -> }, null)
        }

    private inline fun transaction(op: Int, block: (Int) -> PtpResult): PtpResult {
        if (closed) throw PtpException("Not connected")
        // GetDeviceInfo before a session and OpenSession itself use transaction id 0, as the standard says.
        val tid = if (!sessionOpen && (op == Op.OPEN_SESSION || op == Op.GET_DEVICE_INFO)) 0 else ++lastTid
        try {
            val r = block(tid)
            if (op == Op.OPEN_SESSION && (r.code == Rc.OK || r.code == Rc.SESSION_ALREADY_OPEN)) {
                sessionOpen = true
                lastTid = 0
            }
            if (op == Op.CLOSE_SESSION) {
                sessionOpen = false
                lastTid = 0
            }
            return r
        } catch (e: LocalFailure) {
            cancel(tid)
            throw e.cause ?: e
        } catch (e: Exception) {
            // The stream is out of step (or gone): this connection can't be trusted any more.
            if (!closed) fail(e)
            throw if (e is PtpException) e else PtpException("The connection to the camera was lost")
        }
    }

    // ---------- Out ----------

    private fun send(container: ByteArray) {
        writeFully(container, 0, container.size)
        if (PtpUsb.needsZeroLengthPacket(container.size.toLong(), pipe.maxPacketSize)) zeroLengthPacket()
    }

    /** Data phase out: header and data in whole-packet transfers, then a zero-length packet if needed. */
    private fun sendData(op: Int, tid: Int, size: Long, source: InputStream, progress: ((Long, Long) -> Unit)?) {
        val buf = outBuf
        PtpUsb.dataHeader(op, tid, size).copyInto(buf)
        var filled = PtpUsb.HEADER_SIZE
        var sent = 0L
        while (true) {
            val want = minOf((buf.size - filled).toLong(), size - sent).toInt()
            var n = 0
            try {
                while (n < want) {
                    val r = source.read(buf, filled + n, want - n)
                    if (r < 0) break
                    n += r
                }
            } catch (e: java.io.IOException) {
                throw LocalFailure(e)
            }
            if (n < want) throw LocalFailure(PtpException("The file ended early"))
            filled += n
            sent += n
            writeFully(buf, 0, filled)
            progress?.invoke(sent, size)
            if (sent >= size) break
            filled = 0
        }
        if (PtpUsb.needsZeroLengthPacket(PtpUsb.HEADER_SIZE + size, pipe.maxPacketSize)) zeroLengthPacket()
    }

    private fun writeFully(b: ByteArray, off: Int, len: Int) {
        var o = off
        var left = len
        while (left > 0) {
            if (closed) throw PtpException("Not connected")
            val n = pipe.bulkOut(b, o, left, OUT_TIMEOUT_MS)
            if (n <= 0) gone()
            o += n
            left -= n
        }
    }

    private fun zeroLengthPacket() {
        if (pipe.bulkOut(outBuf, 0, 0, OUT_TIMEOUT_MS) < 0) gone()
    }

    // ---------- In ----------

    private fun receive(tid: Int, sink: (ByteArray, Int, Int) -> Unit, progress: ((Long, Long) -> Unit)?): PtpResult {
        while (true) {
            val h = PtpUsb.parseHeader(readSmall(PtpUsb.HEADER_SIZE))
            when (h.type) {
                PtpUsb.TYPE_DATA -> readData(h, sink, progress)
                PtpUsb.TYPE_RESPONSE -> {
                    val p = readSmall(h.length.toInt() - PtpUsb.HEADER_SIZE)
                    // A late response to an earlier, cancelled transaction: skip it.
                    if (h.transactionId != tid) continue
                    return PtpResult(h.code, PtpUsb.params(p, 0, p.size), null)
                }
                else -> readSmall(h.length.toInt() - PtpUsb.HEADER_SIZE) // an event or command on bulk-in: not ours
            }
        }
    }

    private fun readData(h: PtpUsb.Header, sink: (ByteArray, Int, Int) -> Unit, progress: ((Long, Long) -> Unit)?) {
        val total = h.payloadLength
        var received = 0L
        if (total >= 0) {
            while (received < total) {
                if (inPos == inEnd) fill()
                val k = minOf((inEnd - inPos).toLong(), total - received).toInt()
                deliver(sink, k)
                received += k
                progress?.invoke(received, total)
            }
        } else {
            // Over 4 GiB: the data ends with the first transfer that isn't full.
            while (true) {
                val k = inEnd - inPos
                if (k > 0) {
                    deliver(sink, k)
                    received += k
                    progress?.invoke(received, -1)
                }
                if (lastShort) break
                if (!readTransfer()) break
            }
        }
    }

    private fun deliver(sink: (ByteArray, Int, Int) -> Unit, k: Int) {
        try {
            sink(inBuf, inPos, k)
        } catch (t: Throwable) {
            throw LocalFailure(t)
        }
        inPos += k
    }

    private fun readSmall(n: Int): ByteArray {
        val out = ByteArray(n)
        var o = 0
        while (o < n) {
            if (inPos == inEnd) fill()
            val k = minOf(n - o, inEnd - inPos)
            inBuf.copyInto(out, o, inPos, inPos + k)
            inPos += k
            o += k
        }
        return out
    }

    /** The last transfer ended with a short packet (not a full buffer). */
    private var lastShort = true

    /** Reads transfers until one has bytes (zero-length packets are skipped). */
    private fun fill() {
        while (!readTransfer()) {
            // zero-length packet: the end of an earlier container, keep reading
        }
    }

    /** One bulk-in transfer into the buffer; false when it was a zero-length packet. */
    private fun readTransfer(): Boolean {
        val deadline = System.nanoTime() + answerTimeoutMs * 1_000_000
        while (true) {
            if (closed) throw PtpException("Not connected")
            val n = pipe.bulkIn(inBuf, 0, inBuf.size, transferTimeoutMs)
            if (n >= 0) {
                inPos = 0
                inEnd = n
                lastShort = n < inBuf.size
                return n > 0
            }
            if (!pipe.isAttached) gone()
            if (System.nanoTime() > deadline) {
                val e = PtpException("The camera stopped answering")
                fail(e)
                throw e
            }
        }
    }

    /**
     * Cancel (class request 0x64), then throw away what the camera still sends for that transaction until
     * Get Device Status says it's ready again. The session stays open.
     */
    private fun cancel(tid: Int) {
        if (closed) return
        runCatching { pipe.cancelRequest(tid) }
        val until = System.nanoTime() + 5_000_000_000L
        while (!closed && System.nanoTime() < until) {
            val status = runCatching { pipe.deviceStatus() }.getOrNull()
            val n = pipe.bulkIn(inBuf, 0, inBuf.size, 200)
            if (n < 0 && (status == null || status.code == Rc.OK)) break
            if (!pipe.isAttached) break
        }
        inPos = 0
        inEnd = 0
    }

    // ---------- Closing ----------

    private fun gone(): Nothing {
        if (closed) throw PtpException("Not connected")
        val e = if (!pipe.isAttached) UsbDetachedException() else PtpException("The connection to the camera was lost")
        fail(e)
        throw e
    }

    /** The cable was pulled: end the connection now (in-flight calls fail with [UsbDetachedException]). */
    fun detached() = fail(UsbDetachedException())

    private fun fail(t: Throwable) {
        synchronized(closeLock) {
            if (closed) return
            closed = true
        }
        runCatching { pipe.close() }
        onClosed?.invoke(t)
    }

    override fun close() {
        synchronized(closeLock) {
            if (closed) return
            closed = true
        }
        runCatching { pipe.close() }
        onClosed?.invoke(null)
    }

    companion object {
        /** 256 KiB per transfer: a whole number of packets at every USB speed. */
        const val CHUNK = 256 * 1024
        private const val OUT_TIMEOUT_MS = 30_000
    }
}
