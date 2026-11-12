package com.vknxworks.shutterctrl.ptp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class PtpIpTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun initCommandRequestLayout() {
        val guid = ByteArray(16) { it.toByte() }
        val p = PtpIp.initCommandRequest(guid, "AB")
        // 8 header + 16 guid + "AB\0" UTF-16LE (6) + version (4) = 34
        assertEquals(34, p.size)
        assertArrayEquals(hex("22000000 01000000"), p.copyOfRange(0, 8))
        assertArrayEquals(guid, p.copyOfRange(8, 24))
        assertArrayEquals(hex("4100 4200 0000"), p.copyOfRange(24, 30))
        assertArrayEquals(hex("00000100"), p.copyOfRange(30, 34))
    }

    @Test fun initEventRequestLayout() {
        assertArrayEquals(hex("0C000000 03000000 2A000000"), PtpIp.initEventRequest(42))
    }

    @Test fun operationRequestLayout() {
        val p = PtpIp.operationRequest(PtpIp.DATA_NONE_OR_IN, Op.EOS_REMOTE_RELEASE_ON, 7, intArrayOf(1, 0))
        assertArrayEquals(
            hex("1A000000 06000000 01000000 2891 07000000 01000000 00000000"),
            p,
        )
    }

    @Test fun dataPhasePackets() {
        assertArrayEquals(hex("14000000 09000000 05000000 0001000000000000"), PtpIp.startData(5, 256))
        assertArrayEquals(hex("0E000000 0A000000 05000000 AABB"), PtpIp.data(5, hex("AABB")))
        assertArrayEquals(hex("0D000000 0C000000 05000000 CC"), PtpIp.endData(5, hex("00CC00"), 1, 1))
        assertArrayEquals(hex("08000000 0E000000"), PtpIp.probeResponse())
    }

    @Test fun readsPacketsAndAck() {
        val ack = hex("08000000 02000000") // placeholder header, rebuilt below
        assertEquals(8, ack.size)
        val payload = hex("03000000") + ByteArray(16) { 0x11 } + PtpIp.utf16z("EOS") + hex("00000100")
        val bytes = PtpIp.packet(PtpIp.INIT_COMMAND_ACK, payload) + PtpIp.packet(PtpIp.PROBE_REQUEST)
        val input = ByteArrayInputStream(bytes)
        val p1 = PtpIp.read(input)
        assertEquals(PtpIp.INIT_COMMAND_ACK, p1.type)
        val a = PtpIp.parseInitAck(p1.payload)
        assertEquals(3, a.connectionNumber)
        assertEquals("EOS", a.name)
        assertEquals(PtpIp.PROBE_REQUEST, PtpIp.read(input).type)
        try {
            PtpIp.read(input); throw AssertionError("expected EOF")
        } catch (_: EOFException) {
        }
    }

    @Test fun parsesResponseAndStartData() {
        val r = PtpIp.parseResponse(hex("0120 09000000 78563412"))
        assertEquals(Rc.OK, r.code)
        assertEquals(9, r.transactionId)
        assertArrayEquals(intArrayOf(0x12345678), r.params)
        val s = PtpIp.parseStartData(hex("09000000 0000000001000000"))
        assertEquals(9, s.transactionId)
        assertEquals(1L shl 32, s.totalLength)
        assertEquals(5, PtpIp.parseInitFail(hex("05000000")))
    }

    @Test fun rejectsBadLength() {
        try {
            PtpIp.read(ByteArrayInputStream(hex("04000000 07000000")))
            throw AssertionError("expected PtpException")
        } catch (_: PtpException) {
        }
    }

    @Test fun deviceInfo() {
        fun s(t: String) = PtpStrings.encode(t)
        fun u16a(vararg v: Int): ByteArray {
            val b = PtpIp.le(4 + 2 * v.size).putInt(v.size)
            v.forEach { b.putShort(it.toShort()) }
            return b.array()
        }
        val data = PtpIp.le(8).putShort(100).putInt(11).putShort(100).array().copyOf(8) +
            s("") + hex("0000") + u16a(0x1001, 0x9116, 0x911F) + u16a(0xC189) + u16a(0xD101) + u16a() + u16a(0x3801) +
            s("Canon Inc.") + s("Canon EOS R6") + s("1-8.1.0") + s("123456")
        val di = DeviceInfo.parse(data)
        assertEquals("Canon EOS R6", di.model)
        assertEquals("Canon Inc.", di.manufacturer)
        assertEquals("1-8.1.0", di.deviceVersion)
        assertTrue(di.supports(Op.EOS_UPDATE_FIRMWARE))
        assertEquals(11L, di.vendorExtensionId)
    }

    @Test fun objectInfoRoundTrip() {
        val bytes = ObjectInfo.forSend(0x00010001, 1234, "CCF27102.FIR")
        val oi = ObjectInfo.parse(5, bytes)
        assertEquals(0x00010001, oi.storageId)
        assertEquals(0x3000, oi.format)
        assertEquals(1234L, oi.size)
        assertEquals("CCF27102.FIR", oi.fileName)
        assertEquals(0, oi.parent)
    }

    @Test fun objectInfoKinds() {
        val raw = ObjectInfo(1, 1, Fmt.CANON_CR3, 10, 0, 0, 0, 0, 0, "IMG_0001.CR3", "")
        assertTrue(raw.isRaw)
        assertEquals("image/x-canon-cr3", raw.mime)
        val jpg = ObjectInfo(2, 1, Fmt.JPEG, 10, 0, 0, 0, 0, 0, "IMG_0001.JPG", "")
        assertTrue(jpg.isJpeg && !jpg.isRaw)
    }

    @Test fun canonEvents() {
        val b = PtpIp.le(16 + 28 + 12 + 8)
        b.putInt(16).putInt(Ev.PROP_VALUE_CHANGED).putInt(Prop.AV).putInt(0x30)
        b.putInt(28).putInt(Ev.AVAIL_LIST_CHANGED).putInt(Prop.ISO).putInt(3).putInt(3).putInt(0x48).putInt(0x50)
        // Count says 3 but only 2 values fit: never read past the record.
        b.putInt(12).putInt(Ev.OBJECT_ADDED_EX).putInt(0x2A)
        b.putInt(8).putInt(0)
        val ev = CanonEventParser.parse(b.array())
        assertEquals(0x30L, ev.props[Prop.AV])
        assertEquals(listOf(0x48L, 0x50L), ev.allowed[Prop.ISO])
        assertEquals(listOf(0x2A), ev.addedObjects)
    }

    @Test fun canonEventsTruncatedIsSafe() {
        val ev = CanonEventParser.parse(hex("FF000000 89C10000 01D1"))
        assertTrue(ev.props.isEmpty())
    }

    @Test fun viewFinderJpeg() {
        val jpeg = hex("FFD8 0102 FFD9")
        val rec = PtpIp.le(8).putInt(8 + jpeg.size).putInt(1).array() + jpeg
        val other = PtpIp.le(12).putInt(12).putInt(4).putInt(0).array()
        assertArrayEquals(jpeg, ViewFinder.extractJpeg(other + rec))
        assertNull(ViewFinder.extractJpeg(hex("0C000000 04000000 00000000")))
    }

    @Test fun propValueAndFirmwareChunk() {
        assertArrayEquals(hex("0C000000 01D10000 30000000"), CanonData.propValue(Prop.AV, 0x30))
        val chunk = CanonData.firmwareChunk("A.FIR", hex("DEADBEEF"), 3)
        assertEquals(32 + 3, chunk.size)
        assertArrayEquals("A.FIR".toByteArray(), chunk.copyOfRange(0, 5))
        assertTrue(chunk.copyOfRange(5, 32).all { it == 0.toByte() })
        assertArrayEquals(hex("DEADBE"), chunk.copyOfRange(32, 35))
        assertEquals(0x200000, CanonData.FIRMWARE_CHUNK + 32)
    }

    @Test fun u32Array() {
        assertArrayEquals(intArrayOf(0x00010001, 0x00020001), parseU32Array(hex("02000000 01000100 01000200")))
    }

    @Test fun ptpStrings() {
        assertArrayEquals(hex("00"), PtpStrings.encode(""))
        assertArrayEquals(hex("03 4100 4200 0000"), PtpStrings.encode("AB"))
        assertEquals("AB", leBuffer(PtpStrings.encode("AB")).ptpString())
    }
}
