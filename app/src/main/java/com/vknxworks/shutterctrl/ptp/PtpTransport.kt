package com.vknxworks.shutterctrl.ptp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream

/** The outcome of one PTP transaction: the response code and parameters, and the data the camera sent (if any). */
class PtpResult(val code: Int, val params: IntArray, val data: ByteArray?)

/**
 * One way of reaching the camera: PTP/IP over TCP ([PtpIpClient]) or PTP over a USB cable ([PtpUsbClient]).
 * Operations, datasets and events are the same on both; only the framing differs. Blocking; call from a
 * background thread. One transaction at a time.
 */
interface PtpTransport : Closeable {
    /** Called on the transport's event thread for every PTP event (code, params). Canon EOS uses GetEvent polling instead. */
    var onEvent: ((Int, IntArray) -> Unit)?

    /** Called once when the connection fails or closes (null when [close] was called on purpose). */
    var onClosed: ((Throwable?) -> Unit)?

    /** The name the camera gave itself during set-up, or "" when the transport has none. */
    val cameraName: String

    val isOpen: Boolean

    /**
     * A transaction whose incoming data goes to [sink] in pieces (downloads), with [progress] getting
     * (bytes so far, total or -1).
     */
    fun transactTo(
        op: Int,
        params: IntArray,
        dataOut: ByteArray?,
        sink: (ByteArray, Int, Int) -> Unit,
        progress: ((Long, Long) -> Unit)?,
    ): PtpResult

    /**
     * A transaction that sends [total] bytes read from [source] (SendObject of a large file) without
     * holding the file in memory. [progress] gets (bytes sent, total).
     */
    fun transactStreamOut(op: Int, params: IntArray, total: Long, source: InputStream, progress: ((Long, Long) -> Unit)?): PtpResult

    /** A transaction with optional data out; incoming data is collected in memory. */
    fun transact(op: Int, params: IntArray = IntArray(0), dataOut: ByteArray? = null): PtpResult {
        val buf = ByteArrayOutputStream()
        var got = false
        val r = transactTo(op, params, dataOut, { b, off, len -> got = true; buf.write(b, off, len) }, null)
        return PtpResult(r.code, r.params, if (got) buf.toByteArray() else null)
    }
}
