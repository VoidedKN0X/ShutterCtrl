package com.vknxworks.shutterctrl.ptp

import com.vknxworks.shutterctrl.camera.FirmwareRules
import com.vknxworks.shutterctrl.camera.FirmwareRules.Block
import com.vknxworks.shutterctrl.camera.FirmwareRules.Route
import com.vknxworks.shutterctrl.net.LocalAddress
import com.vknxworks.shutterctrl.net.Ssdp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class RulesTest {
    @Test fun localAddresses() {
        listOf("10.0.0.1", "172.16.5.4", "172.31.255.255", "192.168.1.2", "169.254.10.1", "fe80::1", "fd12::3").forEach {
            assertTrue(it, LocalAddress.isAllowed(InetAddress.getByName(it)))
        }
        listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.0.1", "1.1.1.1", "2001:4860::8888", "127.0.0.1").forEach {
            assertFalse(it, LocalAddress.isAllowed(InetAddress.getByName(it)))
        }
        assertNotNull(LocalAddress.parse(" 192.168.1.2 "))
        assertNull(LocalAddress.parse("8.8.8.8"))
        assertNull(LocalAddress.parse("camera.local"))
        assertNull(LocalAddress.parse("192.168.1.300"))
        assertNull(LocalAddress.parse(""))
    }

    @Test fun firmwareRules() {
        val ok = FirmwareRules.check("CCF27102.FIR", 40_000_000, 0x80000453, 0x80000453, 2, Route.CANON_TRANSFER)
        assertTrue(ok.blocks.isEmpty())
        assertTrue(ok.modelConfirmed)

        val bad = FirmwareRules.check("photo.jpg", 100, 0x80000453, 0x80000454, 1, Route.NONE)
        assertTrue(Block.NOT_FIR in bad.blocks)
        assertTrue(Block.BAD_SIZE in bad.blocks)
        assertTrue(Block.BATTERY_LOW in bad.blocks)
        assertTrue(Block.MODEL_MISMATCH in bad.blocks)
        assertTrue(Block.NO_ROUTE in bad.blocks)

        val unknown = FirmwareRules.check("CCF27102.FIR", 40_000_000, 0x80000453, null, null, Route.COPY_TO_CARD)
        assertEquals(listOf(Block.BATTERY_UNKNOWN), unknown.blocks)
        assertFalse(unknown.modelConfirmed)

        val longName = FirmwareRules.check("a_very_long_firmware_file_name_here.FIR", 40_000_000, null, null, 2, Route.CANON_TRANSFER)
        assertEquals(listOf(Block.NAME_TOO_LONG), longName.blocks)

        assertEquals(0x80000453L, FirmwareRules.firModelId(byteArrayOf(0x53, 0x04, 0x00, 0x80.toByte())))
        assertEquals(Route.CANON_TRANSFER, FirmwareRules.route(true, true))
        assertEquals(Route.COPY_TO_CARD, FirmwareRules.route(false, true))
    }

    @Test fun exposureValues() {
        assertEquals("f/1.0", ExposureValues.aperture(0x08))
        assertEquals("f/2.8", ExposureValues.aperture(0x20))
        assertEquals("f/5.6", ExposureValues.aperture(0x30))
        assertEquals("f/8.0", ExposureValues.aperture(0x38))
        assertEquals("f/16", ExposureValues.aperture(0x48))
        assertEquals("1\"", ExposureValues.shutter(0x38))
        assertEquals("30\"", ExposureValues.shutter(0x10))
        assertEquals("1/125", ExposureValues.shutter(0x70))
        assertEquals("1/4000", ExposureValues.shutter(0x98))
        assertEquals("BULB", ExposureValues.shutter(0x0C))
        assertEquals("100", ExposureValues.iso(0x48))
        assertEquals("400", ExposureValues.iso(0x58))
        assertEquals("AUTO", ExposureValues.iso(0))
        assertEquals("0", ExposureValues.expComp(0))
        assertEquals("+1", ExposureValues.expComp(0x08))
        assertEquals("−⅓", ExposureValues.expComp(0xFD))
        assertEquals("+1⅔", ExposureValues.expComp(0x0D))
        assertEquals(100, ExposureValues.batteryPercent(2))
        assertNull(ExposureValues.batteryPercent(9))
    }

    @Test fun ssdp() {
        val msg = String(Ssdp.mSearch("ssdp:all"))
        assertTrue(msg.startsWith("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"))
        assertTrue(msg.endsWith("ST: ssdp:all\r\n\r\n"))
        val h = Ssdp.parseResponse("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.2:49152/upnp/CameraDevDesc.xml\r\nServer: Camera OS/1.0 UPnP/1.0 Canon Device Discovery/1.0\r\n\r\n")
        assertEquals("http://192.168.1.2:49152/upnp/CameraDevDesc.xml", h?.get("location"))
        assertNull(Ssdp.parseResponse("HTTP/1.1 404 Not Found\r\n\r\n"))
        val d = Ssdp.parseDescription("<root><device><friendlyName>EOS R6 &amp; me</friendlyName><manufacturer>Canon</manufacturer><modelName>Canon EOS R6</modelName></device></root>")
        assertEquals("EOS R6 & me", d.friendlyName)
        assertEquals("Canon", d.manufacturer)
        assertEquals("Canon EOS R6", d.model)
        assertTrue(d.isCamera)
        val printer = Ssdp.parseDescription("<root><device><deviceType>urn:schemas-upnp-org:device:Printer:1</deviceType><manufacturer>CANON INC.</manufacturer><modelName>TS5300 series</modelName></device></root>")
        assertFalse(printer.isCamera)
    }
}
