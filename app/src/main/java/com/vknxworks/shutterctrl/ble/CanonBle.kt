package com.vknxworks.shutterctrl.ble

import java.util.UUID

/**
 * Canon's Bluetooth remote protocol, the one the BR-E1 wireless remote uses. Pure Kotlin, unit-tested.
 *
 * Only what two independent open-source implementations both send is used here:
 * - furble (github.com/gkoh/furble, `CanonEOSRemote`), reported working on the EOS M6, RP and R6 Mark II;
 * - eos-remote-web (github.com/RReverser/eos-remote-web), on the EOS 250D, after Ian Douglas Scott's
 *   reverse engineering of the EOS 800D (T7i) in 2017.
 *
 * Service 00050000-…-d8492fffa821. Pairing: write 0x03 followed by the remote's name to 00050002. Buttons: write
 * one byte to 00050003, the button bits (0x80 release, 0x40 focus) together with the "immediate" mode bits 0x0C
 * to press, and 0x0C alone to let go. Other values those projects name (a 2-second delay mode, a movie mode, zoom
 * buttons) are never sent: neither project uses them, so they aren't confirmed.
 */
object CanonBle {
    val SERVICE: UUID = UUID.fromString("00050000-0000-1000-0000-d8492fffa821")
    val PAIR: UUID = UUID.fromString("00050002-0000-1000-0000-d8492fffa821")
    val CONTROL: UUID = UUID.fromString("00050003-0000-1000-0000-d8492fffa821")

    private const val PAIR_PREFIX = 0x03
    private const val BUTTON_RELEASE = 0x80
    private const val BUTTON_FOCUS = 0x40
    private const val MODE_IMMEDIATE = 0x0C

    /** The name the camera shows for this remote. ASCII only and short, as in both projects. */
    const val REMOTE_NAME = "ShutterCtrl"

    /** The pairing write: 0x03, then the remote's name in ASCII (other characters become '?'). */
    fun pairCommand(name: String = REMOTE_NAME): ByteArray {
        val ascii = name.take(MAX_NAME).map { if (it.code in 0x20..0x7E) it.code.toByte() else '?'.code.toByte() }
        return byteArrayOf(PAIR_PREFIX.toByte()) + ascii.toByteArray()
    }

    /** Shutter button fully down (the camera focuses as it would with the remote, then takes the photo). */
    val pressRelease: ByteArray get() = byteArrayOf((BUTTON_RELEASE or MODE_IMMEDIATE).toByte())

    /** Focus button down (the half press). */
    val pressFocus: ByteArray get() = byteArrayOf((BUTTON_FOCUS or MODE_IMMEDIATE).toByte())

    /** Every button up. */
    val releaseAll: ByteArray get() = byteArrayOf(MODE_IMMEDIATE.toByte())

    private const val MAX_NAME = 20
}
