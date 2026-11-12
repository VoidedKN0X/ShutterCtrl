package com.vknxworks.shutterctrl.net

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import javax.net.SocketFactory

/**
 * SSDP discovery (UPnP): an M-SEARCH to 239.255.255.250:1900, then each answer's device description
 * is read to find Canon cameras. Only private and link-local addresses are contacted.
 */
object Ssdp {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900

    /**
     * Search targets: Canon's EOS smartphone service (known from other open-source tools), the camera
     * service named in Canon Camera Connect's UPnP library, then everything as a fallback.
     */
    val searchTargets = listOf(
        "urn:schemas-canon-com:service:ICPO-SmartPhoneEOSSystemService:1",
        "urn:schemas-canon-com:service:CameraConnectedMobileService:1",
        "ssdp:all",
    )

    fun mSearch(st: String): ByteArray = (
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $ADDRESS:$PORT\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $st\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)

    /** Header names in lower case; values trimmed. Null if it isn't an HTTP 200 answer. */
    fun parseResponse(text: String): Map<String, String>? {
        val lines = text.split("\r\n", "\n")
        val first = lines.firstOrNull()?.trim() ?: return null
        if (!first.startsWith("HTTP/1.1 200") && !first.startsWith("NOTIFY")) return null
        val h = HashMap<String, String>()
        for (l in lines.drop(1)) {
            val i = l.indexOf(':')
            if (i <= 0) continue
            h[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        return h
    }

    data class Description(val friendlyName: String, val manufacturer: String, val model: String, val isCamera: Boolean)

    fun parseDescription(xml: String): Description {
        fun tag(name: String) = Regex("<$name>([^<]{0,200})</$name>", RegexOption.IGNORE_CASE).find(xml)?.groupValues?.get(1)?.trim().orEmpty()
        val deviceType = tag("deviceType")
        val model = unescape(tag("modelName"))
        // Canon printers and scanners answer too; keep cameras only.
        val cameraHint = listOf("ICPO", "EOS", "PowerShot", "Camera").any { xml.contains(it, ignoreCase = true) }
        val notCamera = listOf("Printer", "Scanner").any { deviceType.contains(it, ignoreCase = true) }
        return Description(unescape(tag("friendlyName")), unescape(tag("manufacturer")), model, cameraHint && !notCamera)
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")

    data class Found(val address: InetAddress, val name: String, val manufacturer: String, val model: String)

    /**
     * Searches for [timeoutMs]. [bind] puts the socket on the camera's Wi-Fi. Blocking.
     */
    fun search(bind: (DatagramSocket) -> Unit, socketFactory: SocketFactory, timeoutMs: Long = 3500): List<Found> {
        val found = LinkedHashMap<InetAddress, Found>()
        val locations = LinkedHashMap<InetAddress, String?>()
        DatagramSocket(null).use { s ->
            s.reuseAddress = true
            s.bind(InetSocketAddress(0))
            runCatching { bind(s) }
            s.soTimeout = 300
            val group = InetAddress.getByName(ADDRESS)
            for (st in searchTargets) {
                val msg = mSearch(st)
                s.send(DatagramPacket(msg, msg.size, group, PORT))
            }
            val buf = ByteArray(4096)
            val end = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < end) {
                val p = DatagramPacket(buf, buf.size)
                try {
                    s.receive(p)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val from = p.address
                if (!LocalAddress.isAllowed(from)) continue
                val h = parseResponse(String(p.data, 0, p.length, Charsets.UTF_8)) ?: continue
                val server = h["server"].orEmpty()
                val st = h["st"].orEmpty()
                val canonHint = st.contains("canon", true) || server.contains("canon", true) || h["usn"].orEmpty().contains("canon", true)
                if (!locations.containsKey(from) || canonHint) locations[from] = h["location"]
            }
        }
        for ((addr, location) in locations) {
            val desc = location?.let { fetchDescription(it, addr, socketFactory) }
            val isCanon = desc?.manufacturer?.contains("canon", true) == true
            if (desc != null && isCanon && desc.isCamera) {
                found[addr] = Found(addr, desc.friendlyName.ifBlank { desc.model }, desc.manufacturer, desc.model)
            }
        }
        return found.values.toList()
    }

    /** A plain HTTP/1.0 GET over a socket on the camera's network; only to the address that answered. */
    private fun fetchDescription(location: String, from: InetAddress, socketFactory: SocketFactory): Description? = runCatching {
        val uri = URI(location)
        if (!uri.scheme.equals("http", true)) return null
        val host = LocalAddress.parse(uri.host ?: return null) ?: return null
        if (host != from) return null
        val port = if (uri.port > 0) uri.port else 80
        val path = (uri.rawPath ?: "/").ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
        socketFactory.createSocket().use { sock ->
            sock.connect(InetSocketAddress(host, port), 3000)
            sock.soTimeout = 3000
            val hostHeader = if (host is java.net.Inet6Address) "[${host.hostAddress}]" else host.hostAddress
            sock.getOutputStream().write("GET $path HTTP/1.0\r\nHost: $hostHeader:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
            val out = ByteArrayOutputStream()
            val inp = sock.getInputStream()
            val b = ByteArray(8192)
            while (out.size() < 64 * 1024) {
                val r = inp.read(b)
                if (r < 0) break
                out.write(b, 0, r)
            }
            parseDescription(out.toString(Charsets.UTF_8.name()))
        }
    }.getOrNull()
}
