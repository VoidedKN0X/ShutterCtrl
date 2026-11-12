package com.vknxworks.shutterctrl.ptp

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PTP/IP framing (CIPA DC-005). Every packet is length (u32 LE, including these 8 bytes), type (u32 LE),
 * then the payload. Pure Kotlin so it can be unit-tested on the JVM.
 */
object PtpIp {
    const val PORT = 15740
    const val PROTOCOL_VERSION = 0x00010000

    const val INIT_COMMAND_REQUEST = 1
    const val INIT_COMMAND_ACK = 2
    const val INIT_EVENT_REQUEST = 3
    const val INIT_EVENT_ACK = 4
    const val INIT_FAIL = 5
    const val OPERATION_REQUEST = 6
    const val OPERATION_RESPONSE = 7
    const val EVENT = 8
    const val START_DATA = 9
    const val DATA = 10
    const val CANCEL = 11
    const val END_DATA = 12
    const val PROBE_REQUEST = 13
    const val PROBE_RESPONSE = 14

    /** Data phase of an operation request. */
    const val DATA_NONE_OR_IN = 1
    const val DATA_OUT = 2

    /** Upper bound for one packet we accept (a camera never sends more in one piece). */
    const val MAX_PACKET = 64 * 1024 * 1024

    class Packet(val type: Int, val payload: ByteArray)

    fun packet(type: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val b = le(8 + payload.size)
        b.putInt(8 + payload.size).putInt(type).put(payload)
        return b.array()
    }

    fun initCommandRequest(guid: ByteArray, name: String): ByteArray {
        require(guid.size == 16) { "GUID must be 16 bytes" }
        val nameBytes = utf16z(name)
        val p = le(16 + nameBytes.size + 4).put(guid).put(nameBytes).putInt(PROTOCOL_VERSION)
        return packet(INIT_COMMAND_REQUEST, p.array())
    }

    fun initEventRequest(connectionNumber: Int): ByteArray = packet(INIT_EVENT_REQUEST, le(4).putInt(connectionNumber).array())

    fun operationRequest(dataPhase: Int, opcode: Int, transactionId: Int, params: IntArray = IntArray(0)): ByteArray {
        require(params.size <= 5) { "PTP allows at most 5 parameters" }
        val p = le(4 + 2 + 4 + 4 * params.size).putInt(dataPhase).putShort(opcode.toShort()).putInt(transactionId)
        params.forEach { p.putInt(it) }
        return packet(OPERATION_REQUEST, p.array())
    }

    fun startData(transactionId: Int, totalLength: Long): ByteArray = packet(START_DATA, le(12).putInt(transactionId).putLong(totalLength).array())

    fun data(transactionId: Int, bytes: ByteArray, offset: Int = 0, length: Int = bytes.size): ByteArray =
        packet(DATA, le(4 + length).putInt(transactionId).put(bytes, offset, length).array())

    fun endData(transactionId: Int, bytes: ByteArray = ByteArray(0), offset: Int = 0, length: Int = bytes.size): ByteArray =
        packet(END_DATA, le(4 + length).putInt(transactionId).put(bytes, offset, length).array())

    fun probeResponse(): ByteArray = packet(PROBE_RESPONSE)

    /** Reads one packet; throws [EOFException] when the camera closed the connection. */
    fun read(input: InputStream): Packet {
        val head = readFully(input, 8)
        val bb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN)
        val length = bb.int
        val type = bb.int
        if (length < 8 || length > MAX_PACKET) throw PtpException("Bad PTP/IP packet length $length")
        return Packet(type, readFully(input, length - 8))
    }

    fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw EOFException("Connection closed")
            off += r
        }
        return out
    }

    // ---------- Parsed payloads ----------

    class InitAck(val connectionNumber: Int, val guid: ByteArray, val name: String)

    fun parseInitAck(payload: ByteArray): InitAck {
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val conn = bb.int
        val guid = ByteArray(16).also { bb.get(it) }
        return InitAck(conn, guid, readUtf16z(bb))
    }

    fun parseInitFail(payload: ByteArray): Int = if (payload.size >= 4) ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).int else 0

    class Response(val code: Int, val transactionId: Int, val params: IntArray)

    fun parseResponse(payload: ByteArray): Response {
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val code = bb.short.toInt() and 0xFFFF
        val tid = bb.int
        val params = IntArray(bb.remaining() / 4) { bb.int }
        return Response(code, tid, params)
    }

    /** An event on the event channel has the same layout as a response. */
    fun parseEvent(payload: ByteArray): Response = parseResponse(payload)

    class StartData(val transactionId: Int, val totalLength: Long)

    fun parseStartData(payload: ByteArray): StartData {
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return StartData(bb.int, bb.long)
    }

    /** Data/End Data: transaction id, then the bytes. */
    fun dataTransactionId(payload: ByteArray): Int = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int

    // ---------- Helpers ----------

    fun le(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    fun utf16z(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(s.toByteArray(Charsets.UTF_16LE))
        out.write(0); out.write(0)
        return out.toByteArray()
    }

    fun readUtf16z(bb: ByteBuffer): String {
        val sb = StringBuilder()
        while (bb.remaining() >= 2) {
            val c = bb.short.toInt().toChar()
            if (c == '\u0000') break
            sb.append(c)
        }
        return sb.toString()
    }
}
