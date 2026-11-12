package com.vknxworks.shutterctrl.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * ShutterCtrl only talks to cameras on the local network: private and link-local addresses.
 * Everything else is refused before a socket is opened.
 */
object LocalAddress {
    /** Set only in debug builds, for tests against a simulated camera through `adb reverse`. */
    @Volatile var allowLoopback = false

    fun isAllowed(a: InetAddress): Boolean {
        if (a.isLoopbackAddress) return allowLoopback
        val b = a.address
        return when (a) {
            is Inet4Address -> {
                val o0 = b[0].toInt() and 0xFF
                val o1 = b[1].toInt() and 0xFF
                o0 == 10 ||
                    (o0 == 172 && o1 in 16..31) ||
                    (o0 == 192 && o1 == 168) ||
                    (o0 == 169 && o1 == 254)
            }
            is Inet6Address -> {
                val o0 = b[0].toInt() and 0xFF
                val o1 = b[1].toInt() and 0xFF
                (o0 and 0xFE) == 0xFC || (o0 == 0xFE && (o1 and 0xC0) == 0x80)
            }
            else -> false
        }
    }

    /**
     * Parses a typed IP address without any DNS lookup (names are refused), and only if it's allowed.
     */
    fun parse(text: String): InetAddress? {
        val t = text.trim().removePrefix("[").removeSuffix("]")
        if (t.isEmpty()) return null
        val looksV4 = t.matches(Regex("""\d{1,3}(\.\d{1,3}){3}"""))
        val looksV6 = t.contains(':') && t.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
        if (looksV4 && t.split('.').any { it.toInt() > 255 }) return null
        if (!looksV4 && !looksV6) return null
        val a = try { InetAddress.getByName(t) } catch (_: Exception) { return null }
        return a.takeIf { isAllowed(it) }
    }
}
