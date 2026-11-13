package com.vknxworks.shutterctrl.ptp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream

/** Whatever a camera (or something pretending to be one) sends, parsing fails cleanly with a PtpException. */
class HardeningTest {
    private inline fun expectPtp(block: () -> Unit) {
        try {
            block()
            fail("expected a PtpException")
        } catch (_: PtpException) {
        }
    }

    @Test fun shortPtpIpPayloadsAreRefused() {
        expectPtp { PtpIp.parseResponse(ByteArray(5)) }
        expectPtp { PtpIp.parseStartData(ByteArray(11)) }
        expectPtp { PtpIp.parseInitAck(ByteArray(19)) }
        assertEquals(0, PtpIp.parseResponse(ByteArray(6)).params.size)
    }

    @Test fun truncatedDatasetsAreRefused() {
        expectPtp { DeviceInfo.parse(ByteArray(10)) }
        expectPtp { ObjectInfo.parse(1, ByteArray(20)) }
        expectPtp { parseU32Array(ByteArray(2)) }
        // A count far larger than the data.
        expectPtp { parseU32Array(leBuffer(ByteArray(8)).putInt(0x7FFFFFFF).array()) }
    }

    @Test fun eventRecordsWithHugeSizesStopTheParse() {
        // One good record, then a record whose size would wrap around when added to the position.
        val b = leBuffer(ByteArray(16 + 8))
        b.putInt(16).putInt(Ev.PROP_VALUE_CHANGED).putInt(0xD101).putInt(5)
        b.putInt(Int.MAX_VALUE).putInt(Ev.PROP_VALUE_CHANGED)
        val ev = CanonEventParser.parse(b.array())
        assertEquals(mapOf(0xD101 to 5L), ev.props)
    }

    @Test fun viewFinderRecordWithHugeLengthFallsBackToTheMarkerSearch() {
        val bytes = leBuffer(ByteArray(16)).putInt(Int.MAX_VALUE - 2).putInt(1).put(0xFF.toByte()).put(0xD8.toByte()).put(1).put(2).array()
        val jpeg = ViewFinder.extractJpeg(bytes)
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0, 0, 0, 0), jpeg)
        assertNull(ViewFinder.extractJpeg(ByteArray(4)))
    }

    @Test fun packetLengthOutsideTheLimitsIsRefused() {
        fun stream(len: Int): InputStream = leBuffer(ByteArray(8)).putInt(len).putInt(PtpIp.DATA).array().inputStream()
        expectPtp { PtpIp.read(stream(4)) }
        expectPtp { PtpIp.read(stream(PtpIp.MAX_PACKET + 1)) }
        expectPtp { PtpIp.read(stream(-1)) }
    }

    @Test fun inMemoryAnswersAreCapped() {
        val huge = object : PtpTransport {
            override var onEvent: ((Int, IntArray) -> Unit)? = null
            override var onClosed: ((Throwable?) -> Unit)? = null
            override val cameraName = ""
            override val isOpen = true
            override fun transactTo(op: Int, params: IntArray, dataOut: ByteArray?, sink: (ByteArray, Int, Int) -> Unit, progress: ((Long, Long) -> Unit)?): PtpResult {
                val chunk = ByteArray(4 * 1024 * 1024)
                repeat(PtpTransport.MAX_IN_MEMORY / chunk.size + 2) { sink(chunk, 0, chunk.size) }
                return PtpResult(Rc.OK, IntArray(0), null)
            }
            override fun transactStreamOut(op: Int, params: IntArray, total: Long, source: InputStream, progress: ((Long, Long) -> Unit)?) = throw UnsupportedOperationException()
            override fun close() {}
        }
        expectPtp { huge.transact(Op.GET_THUMB) }
    }

    @Test fun cameraFileNamesCantLeaveTheFolder() {
        assertEquals("IMG_0001.CR3", ObjectInfo.safeName("IMG_0001.CR3", 1))
        assertEquals("evil.jpg", ObjectInfo.safeName("../../Android/data/evil.jpg", 1))
        assertEquals("x.jpg", ObjectInfo.safeName("a\\b\\x.jpg", 1))
        assertEquals("hidden", ObjectInfo.safeName("..hidden", 1))
        assertEquals("ab.jpg", ObjectInfo.safeName("a\u0000b.jpg", 1))
        assertEquals("ShutterCtrl_0000002A", ObjectInfo.safeName("../", 42))
        assertTrue(ObjectInfo.safeName("A".repeat(1000), 1).length <= 200)
    }
}
