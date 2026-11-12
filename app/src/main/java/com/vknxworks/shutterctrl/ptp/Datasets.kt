package com.vknxworks.shutterctrl.ptp

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** PTP datasets and Canon EOS data blocks. Pure Kotlin, unit-tested. */

private fun ByteBuffer.u8(): Int = get().toInt() and 0xFF
private fun ByteBuffer.u16(): Int = short.toInt() and 0xFFFF
private fun ByteBuffer.u32(): Long = int.toLong() and 0xFFFFFFFFL

/** A PTP string: u8 count of UTF-16LE characters including the terminating 0, then the characters. */
fun ByteBuffer.ptpString(): String {
    val n = u8()
    if (n == 0) return ""
    val sb = StringBuilder()
    repeat(n) {
        val c = short.toInt().toChar()
        if (c != '\u0000') sb.append(c)
    }
    return sb.toString()
}

object PtpStrings {
    /** Encodes a PTP string; an empty string is the single byte 0. */
    fun encode(s: String): ByteArray {
        if (s.isEmpty()) return byteArrayOf(0)
        val chars = s.take(254)
        val b = leBuffer(ByteArray(1 + 2 * (chars.length + 1)))
        b.put((chars.length + 1).toByte())
        chars.forEach { b.putShort(it.code.toShort()) }
        b.putShort(0)
        return b.array()
    }
}

private fun ByteBuffer.u16Array(): IntArray {
    val n = int
    if (n < 0 || n > remaining() / 2) throw BufferUnderflowException()
    return IntArray(n) { u16() }
}

fun leBuffer(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

data class DeviceInfo(
    val vendorExtensionId: Long,
    val vendorExtensionDesc: String,
    val operations: Set<Int>,
    val events: Set<Int>,
    val properties: Set<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
) {
    fun supports(op: Int) = op in operations

    companion object {
        fun parse(bytes: ByteArray): DeviceInfo {
            val b = leBuffer(bytes)
            b.u16() // standard version
            val vendorId = b.u32()
            b.u16() // vendor extension version
            val vendorDesc = b.ptpString()
            b.u16() // functional mode
            val ops = b.u16Array().toSet()
            val events = b.u16Array().toSet()
            val props = b.u16Array().toSet()
            b.u16Array() // capture formats
            b.u16Array() // image formats
            val manufacturer = b.ptpString()
            val model = b.ptpString()
            val version = b.ptpString()
            // The serial number follows; ShutterCtrl doesn't read or keep it.
            return DeviceInfo(vendorId, vendorDesc, ops, events, props, manufacturer, model, version)
        }
    }
}

data class ObjectInfo(
    val handle: Int,
    val storageId: Int,
    val format: Int,
    val size: Long,
    val thumbFormat: Int,
    val thumbSize: Long,
    val width: Long,
    val height: Long,
    val parent: Int,
    val fileName: String,
    val captureDate: String,
) {
    val isFolder get() = format == Fmt.ASSOCIATION
    val extension get() = fileName.substringAfterLast('.', "").uppercase()
    val isRaw get() = format == Fmt.CANON_CR2 || format == Fmt.CANON_CR3 || format == Fmt.CANON_CRW || extension in setOf("CR2", "CR3", "CRW")
    val isJpeg get() = format == Fmt.JPEG || extension in setOf("JPG", "JPEG")
    val isImage get() = isRaw || isJpeg || extension in setOf("HIF", "HEIF", "HEIC")

    /** MIME type for MediaStore. */
    val mime: String
        get() = when (extension) {
            "JPG", "JPEG" -> "image/jpeg"
            "CR2" -> "image/x-canon-cr2"
            "CR3" -> "image/x-canon-cr3"
            "CRW" -> "image/x-canon-crw"
            "HIF", "HEIF", "HEIC" -> "image/heif"
            "MP4" -> "video/mp4"
            "MOV" -> "video/quicktime"
            else -> "application/octet-stream"
        }

    companion object {
        /** ObjectInfo dataset for SendObjectInfo: a plain file (format 0x3000) of [size] bytes named [fileName]. */
        fun forSend(storageId: Int, size: Long, fileName: String): ByteArray {
            val name = PtpStrings.encode(fileName)
            val empty = PtpStrings.encode("")
            val b = leBuffer(ByteArray(52 + name.size + 3 * empty.size))
            b.putInt(storageId).putShort(0x3000.toShort()).putShort(0).putInt(size.coerceAtMost(0xFFFFFFFFL).toInt())
            b.putShort(0).putInt(0).putInt(0).putInt(0) // thumb format, size, width, height
            b.putInt(0).putInt(0).putInt(0) // image width, height, bit depth
            b.putInt(0).putShort(0).putInt(0).putInt(0) // parent, association type, association desc, sequence
            b.put(name).put(empty).put(empty).put(empty) // file name, capture date, modification date, keywords
            return b.array()
        }

        fun parse(handle: Int, bytes: ByteArray): ObjectInfo {
            val b = leBuffer(bytes)
            val storage = b.int
            val format = b.u16()
            b.u16() // protection
            val size = b.u32()
            val thumbFormat = b.u16()
            val thumbSize = b.u32()
            b.u32(); b.u32() // thumb width, height
            val w = b.u32()
            val h = b.u32()
            b.u32() // bit depth
            val parent = b.int
            b.u16() // association type
            b.u32() // association desc
            b.u32() // sequence number
            val name = b.ptpString()
            val date = if (b.hasRemaining()) b.ptpString() else ""
            return ObjectInfo(handle, storage, format, size, thumbFormat, thumbSize, w, h, parent, name, date)
        }
    }
}

/** u32 count, then u32 values (GetStorageIDs, GetObjectHandles). */
fun parseU32Array(bytes: ByteArray): IntArray {
    val b = leBuffer(bytes)
    val n = b.int
    if (n < 0 || n > b.remaining() / 4) throw PtpException("Bad array length $n")
    return IntArray(n) { b.int }
}

/** What one Canon GetEvent call reported. */
data class CanonEvents(
    val props: Map<Int, Long> = emptyMap(),
    val allowed: Map<Int, List<Long>> = emptyMap(),
    val addedObjects: List<Int> = emptyList(),
)

object CanonEventParser {
    /**
     * GetEvent data is a run of records: size (u32, including itself), type (u32), body. A record with
     * size 8 and type 0 ends the list.
     */
    fun parse(bytes: ByteArray): CanonEvents {
        val props = HashMap<Int, Long>()
        val allowed = HashMap<Int, List<Long>>()
        val added = ArrayList<Int>()
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val b = leBuffer(bytes).position(pos) as ByteBuffer
            val size = b.int
            val type = b.int
            if (size < 8 || pos + size > bytes.size) break
            if (size == 8 && type == 0) break
            val bodyLen = size - 8
            when (type) {
                Ev.PROP_VALUE_CHANGED -> if (bodyLen >= 8) {
                    val prop = b.int
                    props[prop] = b.u32()
                }
                Ev.AVAIL_LIST_CHANGED -> if (bodyLen >= 12) {
                    val prop = b.int
                    b.int // data type
                    val count = b.int
                    val fit = (bodyLen - 12) / 4
                    allowed[prop] = List(count.coerceIn(0, fit)) { b.u32() }
                }
                Ev.OBJECT_ADDED_EX, Ev.OBJECT_ADDED_EX64 -> if (bodyLen >= 4) added += b.int
            }
            pos += size
        }
        return CanonEvents(props, allowed, added)
    }
}

object ViewFinder {
    /**
     * GetViewFinderData returns records of length (u32, including the 8-byte header), type (u32), data.
     * The live view image is the JPEG record. Falls back to searching for a JPEG start marker.
     */
    fun extractJpeg(bytes: ByteArray): ByteArray? {
        var pos = 0
        while (pos + 8 <= bytes.size) {
            val b = leBuffer(bytes).position(pos) as ByteBuffer
            val len = b.int
            if (len < 8 || pos + len > bytes.size) break
            if (len > 10 && bytes[pos + 8] == 0xFF.toByte() && bytes[pos + 9] == 0xD8.toByte()) {
                return bytes.copyOfRange(pos + 8, pos + len)
            }
            pos += len
        }
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte()) return bytes.copyOfRange(i, bytes.size)
        }
        return null
    }
}

object CanonData {
    /** SetDevicePropValueEx data: size (u32, 12), property (u32), value (u32). */
    fun propValue(prop: Int, value: Long): ByteArray =
        leBuffer(ByteArray(12)).putInt(12).putInt(prop).putInt(value.toInt()).array()

    /**
     * One chunk of Canon's firmware transfer (operation 0x911F), as the Canon app builds it: a 32-byte
     * field with the file name (ASCII, zero-padded), then the chunk of the file.
     */
    fun firmwareChunk(fileName: String, chunk: ByteArray, length: Int = chunk.size): ByteArray {
        val name = fileName.toByteArray(Charsets.US_ASCII)
        require(name.size < FIRMWARE_NAME_FIELD) { "File name too long" }
        val out = ByteArray(FIRMWARE_NAME_FIELD + length)
        System.arraycopy(name, 0, out, 0, name.size)
        System.arraycopy(chunk, 0, out, FIRMWARE_NAME_FIELD, length)
        return out
    }

    const val FIRMWARE_NAME_FIELD = 32

    /** The Canon app's send buffer is 2 MiB; its firmware chunks are that minus the 32-byte name field. */
    const val FIRMWARE_CHUNK = 0x200000 - FIRMWARE_NAME_FIELD
}
