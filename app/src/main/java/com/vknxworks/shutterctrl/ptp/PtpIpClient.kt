package com.vknxworks.shutterctrl.ptp

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.SocketFactory

/**
 * The PTP/IP transport: the command socket and the event socket. Blocking; call from a background thread.
 * One transaction at a time ([transactTo] is synchronized). The socket factory decides which network the
 * sockets use (the camera's Wi-Fi), see `net/CameraNetwork.kt`.
 */
class PtpIpClient(
    private val socketFactory: SocketFactory,
    private val host: InetAddress,
    private val guid: ByteArray,
    private val name: String,
) : PtpTransport {

    private var command: Socket? = null
    private var event: Socket? = null
    private lateinit var cmdIn: InputStream
    private lateinit var cmdOut: OutputStream
    private var transactionId = 0
    @Volatile private var closed = false
    private var eventThread: Thread? = null

    override var onEvent: ((Int, IntArray) -> Unit)? = null

    override var onClosed: ((Throwable?) -> Unit)? = null

    @Volatile private var ackName: String = ""
    override val cameraName: String get() = ackName

    /**
     * Opens both channels. The camera may ask its owner to accept a new phone first, so the
     * Init Command Ack wait is long.
     */
    fun connect(confirmTimeoutMs: Int = 90_000) {
        val c = socketFactory.createSocket()
        command = c
        c.tcpNoDelay = true
        c.connect(InetSocketAddress(host, PtpIp.PORT), 8_000)
        cmdIn = BufferedInputStream(c.getInputStream(), 256 * 1024)
        cmdOut = c.getOutputStream()
        c.soTimeout = confirmTimeoutMs
        cmdOut.write(PtpIp.initCommandRequest(guid, name)); cmdOut.flush()
        val ack = PtpIp.read(cmdIn)
        val conn = when (ack.type) {
            PtpIp.INIT_COMMAND_ACK -> PtpIp.parseInitAck(ack.payload).also { ackName = it.name }.connectionNumber
            PtpIp.INIT_FAIL -> throw PtpException("Camera refused the connection (reason ${PtpIp.parseInitFail(ack.payload)})")
            else -> throw PtpException("Unexpected packet ${ack.type} during set-up")
        }
        c.soTimeout = 30_000

        val e = socketFactory.createSocket()
        event = e
        e.tcpNoDelay = true
        e.connect(InetSocketAddress(host, PtpIp.PORT), 8_000)
        e.soTimeout = 15_000
        val eOut = e.getOutputStream()
        val eIn = BufferedInputStream(e.getInputStream())
        eOut.write(PtpIp.initEventRequest(conn)); eOut.flush()
        val eAck = PtpIp.read(eIn)
        if (eAck.type == PtpIp.INIT_FAIL) throw PtpException("Camera refused the event channel")
        if (eAck.type != PtpIp.INIT_EVENT_ACK) throw PtpException("Unexpected packet ${eAck.type} on the event channel")
        e.soTimeout = 0
        eventThread = Thread({ eventLoop(eIn, eOut) }, "ptpip-events").apply { isDaemon = true; start() }
    }

    private fun eventLoop(input: InputStream, output: OutputStream) {
        try {
            while (!closed) {
                val p = PtpIp.read(input)
                when (p.type) {
                    PtpIp.EVENT -> PtpIp.parseEvent(p.payload).let { onEvent?.invoke(it.code, it.params) }
                    PtpIp.PROBE_REQUEST -> synchronized(output) { output.write(PtpIp.probeResponse()); output.flush() }
                }
            }
        } catch (t: Throwable) {
            if (!closed) fail(t)
        }
    }

    @Synchronized
    override fun transactTo(
        op: Int,
        params: IntArray,
        dataOut: ByteArray?,
        sink: (ByteArray, Int, Int) -> Unit,
        progress: ((Long, Long) -> Unit)?,
    ): PtpResult {
        check(!closed) { "Not connected" }
        val tid = ++transactionId
        try {
            cmdOut.write(PtpIp.operationRequest(if (dataOut != null) PtpIp.DATA_OUT else PtpIp.DATA_NONE_OR_IN, op, tid, params))
            if (dataOut != null) {
                cmdOut.write(PtpIp.startData(tid, dataOut.size.toLong()))
                var off = 0
                val step = 512 * 1024
                while (dataOut.size - off > step) {
                    cmdOut.write(PtpIp.data(tid, dataOut, off, step))
                    off += step
                }
                cmdOut.write(PtpIp.endData(tid, dataOut, off, dataOut.size - off))
            }
            cmdOut.flush()
            var total = -1L
            var received = 0L
            while (true) {
                val p = broken { PtpIp.read(cmdIn) }
                when (p.type) {
                    PtpIp.START_DATA -> total = broken { PtpIp.parseStartData(p.payload) }.totalLength
                    PtpIp.DATA, PtpIp.END_DATA -> {
                        val len = p.payload.size - 4
                        if (len > 0) {
                            try {
                                sink(p.payload, 4, len)
                            } catch (t: Throwable) {
                                // The rest of this data is still on its way: the stream can't be used any more.
                                fail(t)
                                throw t
                            }
                        }
                        received += len.coerceAtLeast(0)
                        progress?.invoke(received, total)
                    }
                    PtpIp.OPERATION_RESPONSE -> {
                        val r = broken { PtpIp.parseResponse(p.payload) }
                        if (r.transactionId != tid) continue
                        return PtpResult(r.code, r.params, null)
                    }
                    PtpIp.PROBE_REQUEST -> { cmdOut.write(PtpIp.probeResponse()); cmdOut.flush() }
                    PtpIp.CANCEL -> throw PtpException("The camera cancelled the transfer")
                }
            }
        } catch (t: SocketTimeoutException) {
            fail(t); throw PtpException("The camera stopped answering")
        } catch (t: java.io.IOException) {
            fail(t); throw PtpException("The connection to the camera was lost")
        }
    }

    /**
     * A transaction that sends [total] bytes read from [source] (SendObject of a large file) without
     * holding the file in memory. [progress] gets (bytes sent, total).
     */
    @Synchronized
    override fun transactStreamOut(op: Int, params: IntArray, total: Long, source: InputStream, progress: ((Long, Long) -> Unit)?): PtpResult {
        check(!closed) { "Not connected" }
        val tid = ++transactionId
        try {
            cmdOut.write(PtpIp.operationRequest(PtpIp.DATA_OUT, op, tid, params))
            cmdOut.write(PtpIp.startData(tid, total))
            val buf = ByteArray(512 * 1024)
            var sent = 0L
            while (true) {
                val want = minOf(buf.size.toLong(), total - sent).toInt()
                var n = 0
                while (n < want) {
                    val r = source.read(buf, n, want - n)
                    if (r < 0) break
                    n += r
                }
                if (n < want) throw PtpException("The file ended early")
                sent += n
                if (sent >= total) {
                    cmdOut.write(PtpIp.endData(tid, buf, 0, n))
                    break
                }
                cmdOut.write(PtpIp.data(tid, buf, 0, n))
                progress?.invoke(sent, total)
            }
            cmdOut.flush()
            progress?.invoke(sent, total)
            while (true) {
                val p = broken { PtpIp.read(cmdIn) }
                when (p.type) {
                    PtpIp.OPERATION_RESPONSE -> {
                        val r = broken { PtpIp.parseResponse(p.payload) }
                        if (r.transactionId == tid) return PtpResult(r.code, r.params, null)
                    }
                    PtpIp.PROBE_REQUEST -> { cmdOut.write(PtpIp.probeResponse()); cmdOut.flush() }
                }
            }
        } catch (t: SocketTimeoutException) {
            fail(t); throw PtpException("The camera stopped answering")
        } catch (t: java.io.IOException) {
            fail(t); throw PtpException("The connection to the camera was lost")
        }
    }

    /** A packet that can't be parsed means the stream is out of step: close the connection, then throw. */
    private inline fun <T> broken(parse: () -> T): T = try {
        parse()
    } catch (e: PtpException) {
        fail(e)
        throw e
    }

    private fun fail(t: Throwable?) {
        if (closed) return
        closeQuietly()
        onClosed?.invoke(t)
    }

    private fun closeQuietly() {
        closed = true
        runCatching { command?.close() }
        runCatching { event?.close() }
    }

    override fun close() {
        if (closed) return
        closeQuietly()
        onClosed?.invoke(null)
    }

    override val isOpen get() = !closed
}
