package com.vknxworks.shutterctrl.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class CanonBleTest {
    @Test
    fun uuids() {
        assertEquals("00050000-0000-1000-0000-d8492fffa821", CanonBle.SERVICE.toString())
        assertEquals("00050002-0000-1000-0000-d8492fffa821", CanonBle.PAIR.toString())
        assertEquals("00050003-0000-1000-0000-d8492fffa821", CanonBle.CONTROL.toString())
    }

    @Test
    fun pairIsPrefixThreeThenAsciiName() {
        // eos-remote-web: [3, 'W', 'e', 'b'].
        assertArrayEquals(byteArrayOf(3, 'W'.code.toByte(), 'e'.code.toByte(), 'b'.code.toByte()), CanonBle.pairCommand("Web"))
        assertArrayEquals(byteArrayOf(3, 'a'.code.toByte(), '?'.code.toByte()), CanonBle.pairCommand("aé"))
        assertEquals(21, CanonBle.pairCommand("x".repeat(40)).size)
    }

    @Test
    fun buttons() {
        // furble CanonEOSRemote and eos-remote-web: SHUTTER|CTRL = 0x8C, FOCUS|CTRL = 0x4C, CTRL = 0x0C.
        assertArrayEquals(byteArrayOf(0x8C.toByte()), CanonBle.pressRelease)
        assertArrayEquals(byteArrayOf(0x4C), CanonBle.pressFocus)
        assertArrayEquals(byteArrayOf(0x0C), CanonBle.releaseAll)
    }
}
