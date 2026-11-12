package com.vknxworks.shutterctrl.ptp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PtpUsbTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun deviceInfo(): ByteArray {
        fun s(t: String) = PtpStrings.encode(t)
        fun u16a(vararg v: Int): ByteArray {
            val b = PtpIp.le(4 + 2 * v.size).putInt(v.size)
            v.forEach { b.putShort(it.toShort()) }
            return b.array()
        }
        return PtpIp.le(8).putShort(100).putInt(11).putShort(100).array() +
            s("") + hex("0000") + u16a(Op.GET_DEVICE_INFO, Op.OPEN_SESSION, Op.GET_OBJECT, Op.EOS_GET_EVENT) + u16a(0xC189) + u16a(0xD101) + u16a() + u16a(0x3801) +
            s("Canon Inc.") + s("Canon EOS R6") + s("1-8.1.0") + s("123456")
    }

    private fun pattern(n: Int) = ByteArray(n) { (it * 31 + 7).toByte() }

    private fun camera(mps: Int = 512, zlp: Boolean = true) = FakeUsbCamera(maxPacketSize = mps, sendZlp = zlp).apply { deviceInfo = deviceInfo() }

    private fun download(c: PtpUsbClient, handle: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val r = c.transactTo(Op.GET_OBJECT, intArrayOf(handle), null, { b, off, len -> out.write(b, off, len) }, null)
        assertEquals(Rc.OK, r.code)
        return out.toByteArray()
    }

    // ---------- Framing ----------

    @Test fun commandContainerLayout() {
        assertArrayEquals(
            hex("14000000 0100 2891 07000000 01000000 00000000"),
            PtpUsb.command(Op.EOS_REMOTE_RELEASE_ON, 7, intArrayOf(1, 0)),
        )
        assertArrayEquals(hex("0C000000 0100 0110 00000000"), PtpUsb.command(Op.GET_DEVICE_INFO, 0))
    }

    @Test fun dataHeaderLayout() {
        assertArrayEquals(hex("10000000 0200 1091 05000000"), PtpUsb.dataHeader(Op.EOS_SET_DEVICE_PROP_VALUE_EX, 5, 4))
        // Over 4 GiB the length field is 0xFFFFFFFF.
        assertArrayEquals(hex("FFFFFFFF 0200 0D10 01000000"), PtpUsb.dataHeader(Op.SEND_OBJECT, 1, 5L shl 30))
    }

    @Test fun parsesHeadersAndRejectsBadOnes() {
        val h = PtpUsb.parseHeader(hex("10000000 0300 0120 09000000 78563412"))
        assertEquals(16L, h.length)
        assertEquals(PtpUsb.TYPE_RESPONSE, h.type)
        assertEquals(Rc.OK, h.code)
        assertEquals(9, h.transactionId)
        assertArrayEquals(intArrayOf(0x12345678), PtpUsb.params(hex("10000000 0300 0120 09000000 78563412"), 12, 4))
        assertEquals(-1L, PtpUsb.parseHeader(hex("FFFFFFFF 0200 0910 01000000")).payloadLength)
        for (bad in listOf("08000000 0300 0120 01000000", "0C000000 0700 0120 01000000", "00010000 0300 0120 01000000")) {
            try {
                PtpUsb.parseHeader(hex(bad)); fail("accepted $bad")
            } catch (_: PtpException) {
            }
        }
    }

    @Test fun zeroLengthPacketRule() {
        assertTrue(PtpUsb.needsZeroLengthPacket(512, 512))
        assertTrue(PtpUsb.needsZeroLengthPacket(1024, 512))
        assertFalse(PtpUsb.needsZeroLengthPacket(513, 512))
        assertFalse(PtpUsb.needsZeroLengthPacket(12, 512))
        assertTrue(PtpUsb.needsZeroLengthPacket(64, 64))
    }

    @Test fun classRequestData() {
        assertArrayEquals(hex("0140 2A000000"), PtpUsb.cancelRequestData(42))
        val st = PtpUsb.parseDeviceStatus(hex("0800 1920 05000000"), 8)
        assertNotNull(st)
        assertEquals(Rc.DEVICE_BUSY, st!!.code)
        assertArrayEquals(intArrayOf(5), st.params)
        assertNull(PtpUsb.parseDeviceStatus(hex("04"), 1))
    }

    @Test fun eventSplitOverPackets() {
        val asm = UsbEventAssembler()
        val e = PtpUsb.container(PtpUsb.TYPE_EVENT, 0x4002, 0, intArrayOf(0x55))
        assertTrue(asm.feed(e.copyOfRange(0, 8), 8).isEmpty())
        val got = asm.feed(e.copyOfRange(8, 16), 8)
        assertEquals(1, got.size)
        assertEquals(0x4002, got[0].code)
        assertArrayEquals(intArrayOf(0x55), got[0].params)
        // Garbage is dropped and the next event still comes through.
        assertTrue(asm.feed(hex("0C000000 0900 0000 00000000"), 12).isEmpty())
        val two = PtpUsb.container(PtpUsb.TYPE_EVENT, 0x4006, 0) + PtpUsb.container(PtpUsb.TYPE_EVENT, 0x400C, 0, intArrayOf(1))
        assertEquals(listOf(0x4006, 0x400C), asm.feed(two, two.size).map { it.code })
    }

    // ---------- A whole session over the simulated camera ----------

    @Test fun fullSessionOverUsb() {
        val cam = camera()
        val big = pattern(1_000_000) // four 256 KiB transfers
        cam.objects[0x10] = big
        val client = PtpUsbClient(cam, "EOS")
        val events = CountDownLatch(1)
        val eventCode = AtomicInteger()
        client.onEvent = { code, _ -> eventCode.set(code); events.countDown() }
        client.start()

        val di = client.transact(Op.GET_DEVICE_INFO)
        assertEquals(Rc.OK, di.code)
        val info = DeviceInfo.parse(di.data!!)
        assertEquals("Canon EOS R6", info.model)
        assertEquals(Rc.OK, client.transact(Op.OPEN_SESSION, intArrayOf(1)).code)

        var lastProgress = 0L
        var total = 0L
        val out = ByteArrayOutputStream()
        val r = client.transactTo(Op.GET_OBJECT, intArrayOf(0x10), null, { b, off, len -> out.write(b, off, len) }) { got, t -> lastProgress = got; total = t }
        assertEquals(Rc.OK, r.code)
        assertArrayEquals(big, out.toByteArray())
        assertEquals(big.size.toLong(), lastProgress)
        assertEquals(big.size.toLong(), total)

        cam.event(0x4002, 0x10) // ObjectAdded, split into 8-byte interrupt packets
        assertTrue(events.await(5, TimeUnit.SECONDS))
        assertEquals(0x4002, eventCode.get())

        // GetDeviceInfo and OpenSession use transaction id 0; the session's transactions count from 1.
        assertEquals(listOf(0, 0, 1), cam.commands.map { it.second })
        client.close()
        assertFalse(client.isOpen)
    }

    @Test fun dataFillingItsLastPacketIsFollowedByZeroLengthPacket() {
        val cam = camera()
        cam.objects[1] = pattern(3 * 512 - 12) // container exactly 3 packets: the camera adds a ZLP
        cam.objects[2] = pattern(PtpUsbClient.CHUNK - 12) // container exactly one full transfer: the ZLP is its own transfer
        cam.objects[3] = pattern(100)
        val client = PtpUsbClient(cam)
        client.transact(Op.OPEN_SESSION, intArrayOf(1))
        assertArrayEquals(cam.objects[1], download(client, 1))
        assertArrayEquals(cam.objects[2], download(client, 2))
        assertArrayEquals(cam.objects[3], download(client, 3))
    }

    @Test fun cameraWithoutZeroLengthPacketStillWorks() {
        // The response then arrives in the same transfer as the end of the data.
        val cam = camera(zlp = false)
        cam.objects[1] = pattern(2 * 512 - 12)
        cam.objects[2] = pattern(5000)
        val client = PtpUsbClient(cam)
        client.transact(Op.OPEN_SESSION, intArrayOf(1))
        assertArrayEquals(cam.objects[1], download(client, 1))
        assertArrayEquals(cam.objects[2], download(client, 2))
    }

    @Test fun fullSpeedPacketsSplitTheContainers() {
        val cam = camera(mps = 64)
        cam.objects[7] = pattern(64 * 10 - 12)
        val client = PtpUsbClient(cam)
        client.start()
        assertEquals("Canon EOS R6", DeviceInfo.parse(client.transact(Op.GET_DEVICE_INFO).data!!).model)
        client.transact(Op.OPEN_SESSION, intArrayOf(1))
        assertArrayEquals(cam.objects[7], download(client, 7))
        client.close()
    }

    @Test fun dataOutAddsZeroLengthPacketAndStreamsLargeFiles() {
        val cam = camera()
        val client = PtpUsbClient(cam)
        client.transact(Op.OPEN_SESSION, intArrayOf(1))

        val exact = pattern(512 - 12)
        cam.outCalls.clear()
        assertEquals(Rc.OK, client.transact(Op.EOS_SET_DEVICE_PROP_VALUE_EX, dataOut = exact).code)
        assertArrayEquals(exact, cam.dataIn[Op.EOS_SET_DEVICE_PROP_VALUE_EX])
        // Command (12 bytes), data (one full packet), then the zero-length packet.
        assertEquals(listOf(12, 512, 0), cam.outCalls)

        val file = pattern(700_000)
        cam.outCalls.clear()
        var sent = 0L
        val r = client.transactStreamOut(Op.SEND_OBJECT, IntArray(0), file.size.toLong(), ByteArrayInputStream(file)) { s, _ -> sent = s }
        assertEquals(Rc.OK, r.code)
        assertArrayEquals(file, cam.dataIn[Op.SEND_OBJECT])
        assertEquals(file.size.toLong(), sent)
        // Every transfer but the last is a whole number of packets, so the camera sees one continuous data phase.
        val data = cam.outCalls.drop(1)
        assertTrue(data.dropLast(1).all { it % 512 == 0 })
    }

    @Test fun failedSinkCancelsButKeepsTheSession() {
        val cam = camera()
        cam.objects[1] = pattern(600_000)
        cam.objects[2] = pattern(10)
        val client = PtpUsbClient(cam)
        client.transact(Op.OPEN_SESSION, intArrayOf(1))
        try {
            client.transactTo(Op.GET_OBJECT, intArrayOf(1), null, { _, _, _ -> throw IOException("disk full") }, null)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertEquals(listOf(1), cam.cancels)
        assertTrue(client.isOpen)
        assertArrayEquals(cam.objects[2], download(client, 2))
    }

    @Test fun busyCameraIsResetAtStart() {
        val cam = camera()
        cam.status = Rc.DEVICE_BUSY
        PtpUsbClient(cam).start()
        assertEquals(1, cam.resets)
    }

    // ---------- Detach ----------

    @Test fun unpluggedCableEndsTheConnection() {
        val cam = camera()
        cam.objects[1] = pattern(10)
        val client = PtpUsbClient(cam)
        val closedWith = ArrayList<Throwable?>()
        client.onClosed = { closedWith += it }
        client.transact(Op.OPEN_SESSION, intArrayOf(1))
        cam.unplug()
        try {
            client.transact(Op.GET_OBJECT, intArrayOf(1)); fail("expected UsbDetachedException")
        } catch (_: UsbDetachedException) {
        }
        assertFalse(client.isOpen)
        assertEquals(1, closedWith.size)
        assertTrue(closedWith[0] is UsbDetachedException)
        try {
            client.transact(Op.GET_OBJECT, intArrayOf(1)); fail("expected PtpException")
        } catch (e: PtpException) {
            assertEquals("Not connected", e.message)
        }
        // Closing afterwards doesn't report a second time.
        client.close()
        assertEquals(1, closedWith.size)
    }

    @Test fun detachReportedByTheSystemClosesOnce() {
        val cam = camera()
        val client = PtpUsbClient(cam)
        val closedWith = ArrayList<Throwable?>()
        client.onClosed = { closedWith += it }
        client.start()
        client.detached()
        client.detached()
        assertFalse(client.isOpen)
        assertEquals(1, closedWith.size)
        assertTrue(closedWith[0] is UsbDetachedException)
        assertFalse(cam.isAttached) // the pipe was closed
    }

    @Test fun eventThreadNoticesUnplug() {
        val cam = camera()
        val client = PtpUsbClient(cam)
        val closed = CountDownLatch(1)
        client.onClosed = { if (it is UsbDetachedException) closed.countDown() }
        client.start()
        cam.unplug()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertFalse(client.isOpen)
    }
}
