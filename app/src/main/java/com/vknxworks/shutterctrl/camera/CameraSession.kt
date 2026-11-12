package com.vknxworks.shutterctrl.camera

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.vknxworks.shutterctrl.net.CameraNetwork
import com.vknxworks.shutterctrl.net.LocalAddress
import com.vknxworks.shutterctrl.ptp.CanonData
import com.vknxworks.shutterctrl.ptp.CanonEventParser
import com.vknxworks.shutterctrl.ptp.DeviceInfo
import com.vknxworks.shutterctrl.ptp.ObjectInfo
import com.vknxworks.shutterctrl.ptp.Op
import com.vknxworks.shutterctrl.ptp.Prop
import com.vknxworks.shutterctrl.ptp.PtpException
import com.vknxworks.shutterctrl.ptp.PtpIpClient
import com.vknxworks.shutterctrl.ptp.PtpResult
import com.vknxworks.shutterctrl.ptp.PtpTransport
import com.vknxworks.shutterctrl.ptp.PtpUsbClient
import com.vknxworks.shutterctrl.ptp.Rc
import com.vknxworks.shutterctrl.ptp.UsbDetachedException
import com.vknxworks.shutterctrl.ptp.UsbOpenException
import com.vknxworks.shutterctrl.usb.UsbCameras
import com.vknxworks.shutterctrl.ptp.ViewFinder
import com.vknxworks.shutterctrl.ptp.parseU32Array
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.InetAddress

/** Connection state, shown on the Camera tab. */
sealed interface Link {
    data object Idle : Link
    /** [host] is the camera's address, or "USB" over a cable ([usb] true). */
    data class Connecting(val host: String, val usb: Boolean = false) : Link
    data class Connected(val host: String, val name: String, val info: DeviceInfo, val usb: Boolean = false) : Link
    data class Failed(val host: String, val reason: Reason, val detail: String = "", val usb: Boolean = false) : Link

    enum class Reason { UNREACHABLE, REFUSED, LOST, NOT_CANON, NOT_LOCAL, USB_DETACHED, USB_DENIED, USB_OPEN }
}

data class CardState(
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val items: List<ObjectInfo> = emptyList(),
    val error: String? = null,
)

/**
 * One camera connection for the whole app, over Wi-Fi (PTP/IP) or a USB cable: session set-up, Canon
 * remote mode, the GetEvent poll, the card, shooting, live view and the firmware transfer. Everything above
 * the connection only sees [PtpTransport], so it works the same on both. Every PTP call is serialised.
 */
class CameraSession(
    private val context: Context,
    private val scope: CoroutineScope,
    val network: CameraNetwork,
    val usb: UsbCameras,
) {
    private val _link = MutableStateFlow<Link>(Link.Idle)
    val link: StateFlow<Link> = _link

    /** Canon property values (e.g. 0xD101 Av) and the values the camera allows right now. */
    private val _props = MutableStateFlow<Map<Int, Long>>(emptyMap())
    val props: StateFlow<Map<Int, Long>> = _props
    private val _allowed = MutableStateFlow<Map<Int, List<Long>>>(emptyMap())
    val allowed: StateFlow<Map<Int, List<Long>>> = _allowed

    private val _card = MutableStateFlow(CardState())
    val card: StateFlow<CardState> = _card

    private val _liveFrame = MutableStateFlow<ByteArray?>(null)
    val liveFrame: StateFlow<ByteArray?> = _liveFrame

    private val saver = Saver(context)
    private val thumbs = object : LruCache<Int, ByteArray>(12 * 1024 * 1024) {
        override fun sizeOf(key: Int, value: ByteArray) = value.size
    }
    private val noThumb = HashSet<Int>()

    private var client: PtpTransport? = null
    private val io = Mutex()
    private var pollJob: Job? = null
    private var liveJob: Job? = null

    /** Firmware transfer running: no polling, nothing else may talk to the camera. */
    @Volatile var exclusive = false
        private set

    val info: DeviceInfo? get() = (_link.value as? Link.Connected)?.info

    // ---------- Connection ----------

    /** The attached camera this session uses over USB (its UsbManager key), or null on Wi-Fi. */
    private var usbKey: String? = null

    init {
        // The list of attached cameras changed: if ours is gone, the cable was pulled.
        usb.onChanged = { keys -> usbKey?.let { if (it !in keys) usbGone() } }
    }

    /** Connects over Wi-Fi (PTP/IP) to [hostText], which must be a local address. */
    fun connect(hostText: String, guid: ByteArray, name: String) {
        val current = _link.value
        if (current is Link.Connecting || current is Link.Connected) return
        val addr = LocalAddress.parse(hostText)
        if (addr == null) {
            _link.value = Link.Failed(hostText, Link.Reason.NOT_LOCAL)
            return
        }
        _link.value = Link.Connecting(hostText)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { openIp(addr, guid, name) } }
            result.onSuccess { (c, info) -> connected(c, info, hostText) }
                .onFailure { e -> _link.value = Link.Failed(hostText, failReason(e), e.message.orEmpty()) }
        }
    }

    /**
     * Connects over a USB cable. Asks for Android's USB permission first if ShutterCtrl doesn't have it
     * for this camera yet. [label] is what the Camera tab shows instead of an address ("USB").
     */
    fun connectUsb(camera: UsbCameras.Camera, label: String) {
        val current = _link.value
        if (current is Link.Connecting || current is Link.Connected) return
        _link.value = Link.Connecting(label, usb = true)
        usb.requestPermission(camera) { granted ->
            if (_link.value !is Link.Connecting) return@requestPermission
            if (!granted) {
                _link.value = Link.Failed(label, Link.Reason.USB_DENIED, usb = true)
                return@requestPermission
            }
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val c = PtpUsbClient(usb.open(camera), camera.name)
                        try {
                            c.start()
                        } catch (e: Exception) {
                            c.close(); throw e
                        }
                        c to openSession(c)
                    }
                }
                result.onSuccess { (c, info) ->
                    if (!usb.isAttached(camera.key)) {
                        c.onClosed = null; c.close()
                        _link.value = Link.Failed(label, Link.Reason.USB_DETACHED, usb = true)
                        return@onSuccess
                    }
                    usbKey = camera.key
                    connected(c, info, label)
                }.onFailure { e ->
                    val reason = if (!usb.isAttached(camera.key) || e is UsbDetachedException) Link.Reason.USB_DETACHED else failReason(e)
                    _link.value = Link.Failed(label, reason, e.message.orEmpty(), usb = true)
                }
            }
        }
    }

    private fun connected(c: PtpTransport, info: DeviceInfo, host: String) {
        client = c
        c.onClosed = { e -> if (e != null) scope.launch { lost(e) } }
        // The transport may have failed between set-up and here.
        if (!c.isOpen) {
            client = null
            _link.value = Link.Failed(host, if (usbKey != null) Link.Reason.USB_DETACHED else Link.Reason.LOST, usb = usbKey != null)
            usbKey = null
            return
        }
        _link.value = Link.Connected(host, c.cameraName.ifBlank { info.model }, info, usb = usbKey != null)
        startPolling()
    }

    private fun failReason(e: Throwable): Link.Reason = when {
        e is UsbDetachedException -> Link.Reason.USB_DETACHED
        e is UsbOpenException -> Link.Reason.USB_OPEN
        e is PtpException && e.message?.contains("refused") == true -> Link.Reason.REFUSED
        e is PtpException && e.code == -1 -> Link.Reason.NOT_CANON
        e is PtpException -> Link.Reason.LOST
        else -> Link.Reason.UNREACHABLE
    }

    private fun openIp(addr: InetAddress, guid: ByteArray, name: String): Pair<PtpTransport, DeviceInfo> {
        val factory = if (addr.isLoopbackAddress) javax.net.SocketFactory.getDefault() else network.socketFactory()
        val c = PtpIpClient(factory, addr, guid, name)
        try {
            c.connect()
        } catch (e: Exception) {
            c.close()
            throw e
        }
        return c to openSession(c)
    }

    /**
     * The same on every transport: GetDeviceInfo (must be a Canon), OpenSession, then Canon's remote and
     * event modes. Closes [c] on failure.
     */
    private fun openSession(c: PtpTransport): DeviceInfo {
        try {
            val di = c.transact(Op.GET_DEVICE_INFO)
            if (di.code != Rc.OK || di.data == null) throw PtpException("GetDeviceInfo failed: ${Rc.name(di.code)}")
            val info = DeviceInfo.parse(di.data)
            if (!info.manufacturer.contains("Canon", ignoreCase = true)) throw PtpException("Not a Canon camera", -1)
            val open = c.transact(Op.OPEN_SESSION, intArrayOf(1))
            if (open.code != Rc.OK && open.code != Rc.SESSION_ALREADY_OPEN) throw PtpException("OpenSession failed: ${Rc.name(open.code)}")
            if (info.supports(Op.EOS_SET_REMOTE_MODE)) c.transact(Op.EOS_SET_REMOTE_MODE, intArrayOf(1))
            if (info.supports(Op.EOS_SET_EVENT_MODE)) c.transact(Op.EOS_SET_EVENT_MODE, intArrayOf(1))
            return info
        } catch (e: Exception) {
            c.onClosed = null
            c.close()
            throw e
        }
    }

    private fun lost(e: Throwable) {
        val l = _link.value
        if (l !is Link.Connected) return
        val key = usbKey
        if (key != null && (e is UsbDetachedException || !usb.isAttached(key))) {
            usbGone()
            return
        }
        teardown()
        _link.value = Link.Failed(l.host, Link.Reason.LOST, e.message.orEmpty(), usb = l.usb)
    }

    /**
     * The USB cable was pulled (or the camera turned off): end the session, stop the intervalometer and
     * say so. Safe to call more than once.
     */
    private fun usbGone() {
        val l = _link.value
        if (usbKey == null) return
        runCatching { IntervalService.stop(context) }
        teardown()
        if (l is Link.Connected) _link.value = Link.Failed(l.host, Link.Reason.USB_DETACHED, usb = true)
    }

    fun disconnect() {
        scope.launch {
            val c = client ?: run { _link.value = Link.Idle; return@launch }
            stopLiveView()
            pollJob?.cancel()
            withContext(Dispatchers.IO) {
                io.withLock { runCatching { c.transact(Op.CLOSE_SESSION) } }
            }
            teardown()
            _link.value = Link.Idle
        }
    }

    fun dismissFailure() {
        if (_link.value is Link.Failed) _link.value = Link.Idle
    }

    private fun teardown() {
        pollJob?.cancel(); pollJob = null
        liveJob?.cancel(); liveJob = null
        client?.onClosed = null
        client?.close()
        client = null
        usbKey = null
        _props.value = emptyMap()
        _allowed.value = emptyMap()
        _card.value = CardState()
        _liveFrame.value = null
        thumbs.evictAll()
        noThumb.clear()
        exclusive = false
    }

    /** Runs one PTP call on the IO thread, after any other call has finished. */
    private suspend fun <T> call(block: (PtpTransport) -> T): T = withContext(Dispatchers.IO) {
        io.withLock {
            val c = client ?: throw PtpException("Not connected")
            block(c)
        }
    }

    private fun PtpTransport.ok(op: Int, params: IntArray = IntArray(0), data: ByteArray? = null): PtpResult {
        val r = transact(op, params, data)
        if (r.code != Rc.OK) throw PtpException("${"0x%04X".format(op)} → ${Rc.name(r.code)}", r.code)
        return r
    }

    // ---------- Events ----------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && client != null) {
                if (!exclusive) runCatching { pollOnce() }
                delay(if (liveJob != null) 1000 else 500)
            }
        }
    }

    private suspend fun pollOnce() {
        val i = info ?: return
        if (!i.supports(Op.EOS_GET_EVENT)) return
        val r = call { it.transact(Op.EOS_GET_EVENT) }
        val data = r.data ?: return
        val ev = CanonEventParser.parse(data)
        if (ev.props.isNotEmpty()) _props.update { it + ev.props }
        if (ev.allowed.isNotEmpty()) _allowed.update { it + ev.allowed }
        if (ev.addedObjects.isNotEmpty() && _card.value.loaded) {
            for (h in ev.addedObjects) {
                val oi = runCatching { objectInfo(h) }.getOrNull() ?: continue
                if (!oi.isFolder) _card.update { s -> s.copy(items = listOf(oi) + s.items.filter { it.handle != h }) }
            }
        }
    }

    // ---------- Card ----------

    private suspend fun objectInfo(handle: Int): ObjectInfo {
        val r = call { it.ok(Op.GET_OBJECT_INFO, intArrayOf(handle)) }
        return ObjectInfo.parse(handle, r.data ?: throw PtpException("No object info"))
    }

    fun loadCard() {
        if (_card.value.loading || client == null) return
        _card.value = CardState(loading = true)
        scope.launch {
            try {
                val storages = parseU32Array(call { it.ok(Op.GET_STORAGE_IDS) }.data ?: ByteArray(4))
                val items = ArrayList<ObjectInfo>()
                for (s in storages) {
                    if (s and 0xFFFF == 0) continue // storage slot without a card
                    val handles = parseU32Array(call { it.ok(Op.GET_OBJECT_HANDLES, intArrayOf(s, 0, 0)) }.data ?: ByteArray(4))
                    for (h in handles.reversed()) {
                        val oi = runCatching { objectInfo(h) }.getOrNull() ?: continue
                        if (!oi.isFolder) {
                            items += oi
                            if (items.size % 24 == 0) _card.value = CardState(loading = true, items = items.toList())
                        }
                    }
                }
                _card.value = CardState(loaded = true, items = items.sortedByDescending { it.captureDate.ifBlank { it.fileName } })
            } catch (e: Exception) {
                _card.value = CardState(error = e.message ?: "")
            }
        }
    }

    suspend fun thumbnail(info: ObjectInfo): ByteArray? {
        thumbs.get(info.handle)?.let { return it }
        if (info.handle in noThumb || client == null || exclusive) return null
        val i = this.info ?: return null
        if (!i.supports(Op.GET_THUMB) || info.thumbSize == 0L) return null
        val r = runCatching { call { it.transact(Op.GET_THUMB, intArrayOf(info.handle)) } }.getOrNull()
        val data = r?.data
        if (r == null || r.code != Rc.OK || data == null || data.isEmpty()) {
            noThumb += info.handle
            return null
        }
        thumbs.put(info.handle, data)
        return data
    }

    /** Downloads one file to Pictures/ShutterCtrl; [progress] gets 0..1. */
    suspend fun download(info: ObjectInfo, progress: (Float) -> Unit): Uri = withContext(Dispatchers.IO) {
        val i = this@CameraSession.info ?: throw PtpException("Not connected")
        val pending = saver.begin(info)
        try {
            val total = info.size
            val chunk = 1024 * 1024
            val partial = when {
                total in 1 until 0xFFFFFFFFL && i.supports(Op.GET_PARTIAL_OBJECT) -> Op.GET_PARTIAL_OBJECT
                total in 1 until 0xFFFFFFFFL && i.supports(Op.EOS_GET_PARTIAL_OBJECT) -> Op.EOS_GET_PARTIAL_OBJECT
                else -> 0
            }
            if (partial != 0) {
                var offset = 0L
                while (offset < total) {
                    val n = minOf(chunk.toLong(), total - offset).toInt()
                    var got = 0
                    val r = call { c ->
                        c.transactTo(partial, intArrayOf(info.handle, offset.toInt(), n), null, { b, off, len -> pending.out.write(b, off, len); got += len }, null)
                    }
                    if (r.code != Rc.OK) throw PtpException("Download failed: ${Rc.name(r.code)}", r.code)
                    if (got == 0) throw PtpException("The camera sent no data")
                    offset += got
                    progress((offset.toFloat() / total).coerceIn(0f, 1f))
                }
            } else {
                val r = call { c ->
                    c.transactTo(Op.GET_OBJECT, intArrayOf(info.handle), null, { b, off, len -> pending.out.write(b, off, len) }) { got, t ->
                        val all = if (t > 0) t else total
                        if (all > 0) progress((got.toFloat() / all).coerceIn(0f, 1f))
                    }
                }
                if (r.code != Rc.OK) throw PtpException("Download failed: ${Rc.name(r.code)}", r.code)
            }
            saver.finish(pending)
            pending.uri
        } catch (e: Exception) {
            saver.abort(pending)
            throw e
        }
    }

    // ---------- Shooting ----------

    suspend fun halfPress(on: Boolean) {
        call { c ->
            if (on) c.ok(Op.EOS_REMOTE_RELEASE_ON, intArrayOf(1, 0)) else c.ok(Op.EOS_REMOTE_RELEASE_OFF, intArrayOf(1))
        }
    }

    /**
     * One shot: half press (focus), full press, then both released. Canon's RemoteReleaseOn takes the
     * button state (1 half, 2 full) and an AF flag (0 with AF).
     */
    suspend fun shoot() {
        call { c ->
            c.ok(Op.EOS_REMOTE_RELEASE_ON, intArrayOf(1, 0))
        }
        delay(250)
        try {
            call { c -> c.ok(Op.EOS_REMOTE_RELEASE_ON, intArrayOf(2, 0)) }
            delay(150)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { call { c -> c.transact(Op.EOS_REMOTE_RELEASE_OFF, intArrayOf(2)) } }
                runCatching { call { c -> c.transact(Op.EOS_REMOTE_RELEASE_OFF, intArrayOf(1)) } }
            }
        }
    }

    suspend fun setProp(prop: Int, value: Long) {
        call { c -> c.ok(Op.EOS_SET_DEVICE_PROP_VALUE_EX, data = CanonData.propValue(prop, value)) }
        _props.update { it + (prop to value) }
    }

    // ---------- Live view ----------

    val canLiveView: Boolean get() = info?.let { it.supports(Op.EOS_GET_VIEWFINDER_DATA) && it.supports(Op.EOS_SET_DEVICE_PROP_VALUE_EX) } == true

    fun startLiveView() {
        if (liveJob != null || !canLiveView) return
        liveJob = scope.launch {
            try {
                // EVF output device 2 = the computer (here: the phone), as libgphoto2 and the Canon app set it.
                setProp(Prop.EVF_OUTPUT_DEVICE, 2)
                var misses = 0
                while (isActive) {
                    val r = runCatching { call { it.transact(Op.EOS_GET_VIEWFINDER_DATA, intArrayOf(0x00200000, 0, 0)) } }.getOrNull()
                    val frame = r?.data?.takeIf { r.code == Rc.OK }?.let { ViewFinder.extractJpeg(it) }
                    if (frame != null) {
                        _liveFrame.value = frame
                        misses = 0
                        delay(60)
                    } else {
                        misses++
                        delay(if (r?.code == Rc.CANON_NOT_READY || r?.code == Rc.DEVICE_BUSY) 120 else 300)
                        if (misses > 100) break
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    suspend fun stopLiveView() {
        val j = liveJob ?: return
        liveJob = null
        j.cancel()
        _liveFrame.value = null
        if (client != null) runCatching { setProp(Prop.EVF_OUTPUT_DEVICE, 0) }
    }

    val liveViewRunning get() = liveJob != null

    // ---------- Firmware ----------

    val firmwareRoute: FirmwareRules.Route
        get() = info?.let { FirmwareRules.route(it.supports(Op.EOS_UPDATE_FIRMWARE), it.supports(Op.SEND_OBJECT_INFO) && it.supports(Op.SEND_OBJECT)) }
            ?: FirmwareRules.Route.NONE

    /**
     * Sends a .FIR file the way Canon Camera Connect does: operation 0x911F per chunk, params
     * (file size, offset), data = 32-byte file name field + chunk. Nothing else talks to the camera
     * meanwhile. The camera installs it only when you start the update on the camera.
     */
    suspend fun sendFirmwareCanon(fileName: String, size: Long, open: () -> InputStream, progress: (Float) -> Unit) {
        withExclusive {
            withContext(Dispatchers.IO) {
                open().use { input ->
                    val buf = ByteArray(CanonData.FIRMWARE_CHUNK)
                    var offset = 0L
                    while (offset < size) {
                        val want = minOf(buf.size.toLong(), size - offset).toInt()
                        var n = 0
                        while (n < want) {
                            val r = input.read(buf, n, want - n)
                            if (r < 0) break
                            n += r
                        }
                        if (n < want) throw PtpException("The file ended early")
                        val payload = CanonData.firmwareChunk(fileName, buf, n)
                        io.withLock {
                            val c = client ?: throw PtpException("Not connected")
                            val r = c.transact(Op.EOS_UPDATE_FIRMWARE, intArrayOf(size.toInt(), offset.toInt()), payload)
                            if (r.code != Rc.OK) throw PtpException("Firmware transfer refused: ${Rc.name(r.code)}", r.code)
                        }
                        offset += n
                        progress((offset.toFloat() / size).coerceIn(0f, 1f))
                    }
                }
            }
        }
    }

    /** The standard route: copy the .FIR to the root of the card (SendObjectInfo + SendObject). */
    suspend fun copyFirmwareToCard(fileName: String, size: Long, open: () -> InputStream, progress: (Float) -> Unit) {
        withExclusive {
            withContext(Dispatchers.IO) {
                io.withLock {
                    val c = client ?: throw PtpException("Not connected")
                    val storages = parseU32Array(c.ok(Op.GET_STORAGE_IDS).data ?: ByteArray(4)).filter { it and 0xFFFF != 0 }
                    val storage = storages.firstOrNull() ?: throw PtpException("No card in the camera")
                    val infoData = ObjectInfo.forSend(storage, size, fileName)
                    // Parent 0xFFFFFFFF: the root of the card, where the camera looks for firmware.
                    c.ok(Op.SEND_OBJECT_INFO, intArrayOf(storage, -1), infoData)
                    open().use { input ->
                        val r = c.transactStreamOut(Op.SEND_OBJECT, IntArray(0), size, input) { s, t -> progress((s.toFloat() / t).coerceIn(0f, 1f)) }
                        if (r.code != Rc.OK) throw PtpException("Copy refused: ${Rc.name(r.code)}", r.code)
                    }
                }
            }
        }
    }

    private suspend fun withExclusive(block: suspend () -> Unit) {
        stopLiveView()
        exclusive = true
        try {
            block()
        } finally {
            exclusive = false
        }
    }
}
