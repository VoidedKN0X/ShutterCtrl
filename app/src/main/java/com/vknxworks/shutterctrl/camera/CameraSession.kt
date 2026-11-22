package com.vknxworks.shutterctrl.camera

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.vknxworks.shutterctrl.net.CameraNetwork
import com.vknxworks.shutterctrl.net.LocalAddress
import com.vknxworks.shutterctrl.ptp.BracketPlan
import com.vknxworks.shutterctrl.ptp.CanonData
import com.vknxworks.shutterctrl.ptp.FocusStack
import com.vknxworks.shutterctrl.ptp.MovieRecord
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
    /** Geotags a finished download (uri, MIME type) before it appears in the library; see LocationLogger. */
    private val geotag: (Uri, String) -> Unit = { _, _ -> },
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

    /** A bracketing or focus-stacking series in progress (null when none). */
    private val _series = MutableStateFlow<Series?>(null)
    val series: StateFlow<Series?> = _series
    private var seriesJob: Job? = null

    private val _liveFrame = MutableStateFlow<ByteArray?>(null)
    val liveFrame: StateFlow<ByteArray?> = _liveFrame

    /** New photos the camera reported while connected (for auto-download), whether or not the Card tab is open. */
    private val _newObjects = MutableSharedFlow<ObjectInfo>(extraBufferCapacity = 64)
    val newObjects: SharedFlow<ObjectInfo> = _newObjects

    /** A bulb exposure in progress: seconds in all and seconds left (null when none). */
    data class Bulb(val total: Int, val left: Int, val error: String? = null, val finished: Boolean = false)
    private val _bulb = MutableStateFlow<Bulb?>(null)
    val bulb: StateFlow<Bulb?> = _bulb
    private var bulbJob: Job? = null

    /**
     * Downloads that stopped part-way (the connection dropped), by file: where the unfinished file is and how
     * much of it arrived. Downloading the same file again continues from there. Kept while ShutterCtrl runs.
     */
    private class Partial(val uri: Uri, val offset: Long, val total: Long)
    private val partials = HashMap<String, Partial>()

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
        val c = PtpIpClient(network.socketFactory(), addr, guid, name)
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
        seriesJob?.cancel(); seriesJob = null
        _series.value = null
        bulbJob?.cancel(); bulbJob = null
        _bulb.value = null
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
        if (ev.addedObjects.isNotEmpty()) {
            for (h in ev.addedObjects) {
                val oi = runCatching { objectInfo(h) }.getOrNull() ?: continue
                if (oi.isFolder) continue
                if (_card.value.loaded) _card.update { s -> s.copy(items = listOf(oi) + s.items.filter { it.handle != h }) }
                _newObjects.tryEmit(oi)
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
        val r = try {
            call { it.transact(Op.GET_THUMB, intArrayOf(info.handle)) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The tile scrolled away before its turn: not "this file has no thumbnail", so it's asked again later.
            throw e
        } catch (_: Throwable) {
            null
        }
        val data = r?.data
        if (r == null || r.code != Rc.OK || data == null || data.isEmpty()) {
            noThumb += info.handle
            return null
        }
        thumbs.put(info.handle, data)
        return data
    }

    /** Where an interrupted download of [info] stopped (0..1), or null when there is nothing to continue. */
    fun resumeFraction(info: ObjectInfo): Float? = synchronized(partials) {
        partials[partialKey(info)]?.let { (it.offset.toFloat() / it.total).coerceIn(0f, 1f) }
    }

    /** The same file on the card, also after a reconnect (when its handle may differ). */
    private fun partialKey(info: ObjectInfo) = "${info.fileName}|${info.size}|${info.captureDate}"

    /**
     * Downloads one file to Pictures/ShutterCtrl; [progress] gets 0..1. When the camera can send parts of a
     * file and an earlier download of the same file stopped part-way, it continues where that one stopped;
     * if this one stops part-way too, what arrived is kept (hidden) for the next try.
     */
    suspend fun download(info: ObjectInfo, progress: (Float) -> Unit): Uri = withContext(Dispatchers.IO) {
        val i = this@CameraSession.info ?: throw PtpException("Not connected")
        val total = info.size
        val partial = when {
            total in 1 until 0xFFFFFFFFL && i.supports(Op.GET_PARTIAL_OBJECT) -> Op.GET_PARTIAL_OBJECT
            total in 1 until 0xFFFFFFFFL && i.supports(Op.EOS_GET_PARTIAL_OBJECT) -> Op.EOS_GET_PARTIAL_OBJECT
            else -> 0
        }
        val key = partialKey(info)
        val earlier = if (partial != 0) synchronized(partials) { partials.remove(key) } else null
        val pending = earlier?.let { runCatching { saver.resume(it.uri, it.offset) }.getOrNull() } ?: saver.begin(info)
        var offset = earlier?.takeIf { it.uri == pending.uri }?.offset ?: 0L
        try {
            if (partial != 0) {
                val chunk = 1024 * 1024
                if (offset > 0) progress((offset.toFloat() / total).coerceIn(0f, 1f))
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
            saver.finish(pending) { uri -> geotag(uri, info.mime) }
            pending.uri
        } catch (e: Throwable) {
            if (partial != 0 && offset > 0 && offset < total && e !is kotlinx.coroutines.CancellationException) {
                // Keep what arrived (still hidden from the library) so the next try continues from here.
                if (saver.keep(pending)) synchronized(partials) { partials[key] = Partial(pending.uri, offset, total) } else saver.abort(pending)
            } else {
                saver.abort(pending)
            }
            throw e
        }
    }

    // ---------- Delete ----------

    /** The camera lists the standard DeleteObject, or Canon's own (libgphoto2). */
    val canDelete: Boolean get() = info?.let { it.supports(Op.DELETE_OBJECT) || it.supports(Op.EOS_DELETE_OBJECT) } == true

    /** Deletes [obj] from the card. Nothing on the phone is touched. */
    suspend fun delete(obj: ObjectInfo) {
        val i = info ?: throw PtpException("Not connected")
        call { c ->
            // Standard DeleteObject: handle and object format (0 = any).
            if (i.supports(Op.DELETE_OBJECT)) c.ok(Op.DELETE_OBJECT, intArrayOf(obj.handle, 0))
            else c.ok(Op.EOS_DELETE_OBJECT, intArrayOf(obj.handle))
        }
        _card.update { st -> st.copy(items = st.items.filter { it.handle != obj.handle }) }
        thumbs.remove(obj.handle)
    }

    // ---------- Shooting ----------

    suspend fun halfPress(on: Boolean) {
        call { c ->
            if (on) c.ok(Op.EOS_REMOTE_RELEASE_ON, intArrayOf(1, 0)) else c.ok(Op.EOS_REMOTE_RELEASE_OFF, intArrayOf(1))
        }
    }

    /**
     * One shot: half press (focus), full press, then both released. Canon's RemoteReleaseOn takes the
     * button state (1 half, 2 full) and an AF flag (0 with AF, 1 without: focus stays where it is).
     */
    suspend fun shoot(af: Boolean = true) {
        val flag = if (af) 0 else 1
        call { c ->
            c.ok(Op.EOS_REMOTE_RELEASE_ON, intArrayOf(1, flag))
        }
        delay(250)
        try {
            call { c -> c.ok(Op.EOS_REMOTE_RELEASE_ON, intArrayOf(2, flag)) }
            delay(150)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { call { c -> c.transact(Op.EOS_REMOTE_RELEASE_OFF, intArrayOf(2)) } }
                runCatching { call { c -> c.transact(Op.EOS_REMOTE_RELEASE_OFF, intArrayOf(1)) } }
            }
        }
    }

    // ---------- Bulb ----------

    /** The camera can hold the shutter open: Canon's BulbStart/BulbEnd, or the remote release held down. */
    val canBulb: Boolean get() = info?.let { (it.supports(Op.EOS_BULB_START) && it.supports(Op.EOS_BULB_END)) || it.supports(Op.EOS_REMOTE_RELEASE_ON) } == true

    /**
     * A bulb exposure of [seconds] (the camera must be set to Bulb). With BulbStart/BulbEnd when the camera
     * lists them (libgphoto2's "bulb"), otherwise by holding the remote release fully down and letting go
     * (libgphoto2's "Press Full" and "Release Full"). The shutter is always closed again, also when stopped early.
     */
    fun startBulb(seconds: Int) {
        val i = info ?: return
        if (bulbJob?.isActive == true || seriesJob?.isActive == true || seconds <= 0) return
        val ops = i.supports(Op.EOS_BULB_START) && i.supports(Op.EOS_BULB_END)
        _bulb.value = Bulb(seconds, seconds)
        bulbJob = scope.launch {
            var error: String? = null
            try {
                if (ops) {
                    call { c -> c.ok(Op.EOS_BULB_START) }
                } else {
                    call { c -> c.ok(Op.EOS_REMOTE_RELEASE_ON, intArrayOf(1, 0)) }
                    delay(250)
                    call { c -> c.ok(Op.EOS_REMOTE_RELEASE_ON, intArrayOf(2, 0)) }
                }
                for (left in seconds downTo 1) {
                    _bulb.value = Bulb(seconds, left)
                    delay(1000)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message.orEmpty()
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (client != null) {
                        if (ops) {
                            runCatching { call { c -> c.transact(Op.EOS_BULB_END) } }
                        } else {
                            runCatching { call { c -> c.transact(Op.EOS_REMOTE_RELEASE_OFF, intArrayOf(2)) } }
                            runCatching { call { c -> c.transact(Op.EOS_REMOTE_RELEASE_OFF, intArrayOf(1)) } }
                        }
                    }
                    _bulb.value = Bulb(seconds, 0, error = error, finished = true)
                }
            }
        }
    }

    /** Ends the bulb exposure now (the shutter closes). */
    fun stopBulb() {
        bulbJob?.cancel()
    }

    fun clearBulb() {
        if (bulbJob?.isActive != true) _bulb.value = null
    }

    // ---------- Movie ----------

    /** Movies can be started from here: the camera reports libgphoto2's EVFRecordStatus and takes settings. */
    val canMovie: Boolean get() = canSetProps && Prop.EVF_RECORD_STATUS in _props.value

    /** Starts (true) or stops recording a movie to the card. Live view must be running; the camera in movie mode. */
    suspend fun setMovieRecording(on: Boolean) {
        setProp(Prop.EVF_RECORD_STATUS, if (on) MovieRecord.CARD else MovieRecord.STOP)
    }

    suspend fun setProp(prop: Int, value: Long) {
        call { c -> c.ok(Op.EOS_SET_DEVICE_PROP_VALUE_EX, data = CanonData.propValue(prop, value)) }
        _props.update { it + (prop to value) }
    }

    // ---------- Bracketing and focus stacking ----------

    /** Progress of a series: [done] of [total] shots; [error] set when it stopped early. */
    data class Series(val kind: Kind, val done: Int, val total: Int, val error: String? = null, val finished: Boolean = false) {
        enum class Kind { BRACKET, FOCUS_STACK }
    }

    /** Focus stacking needs Canon's DriveLens and live view (the lens is only driven while live view runs). */
    val canFocusStack: Boolean get() = canLiveView && info?.supports(Op.EOS_DRIVE_LENS) == true

    /** Whether the camera can take setting changes at all (bracketing also needs the values it allows). */
    val canSetProps: Boolean get() = info?.supports(Op.EOS_SET_DEVICE_PROP_VALUE_EX) == true

    /** Moves the focus one DriveLens step (see [FocusStack.driveParam]). Live view must be running. */
    suspend fun driveLens(param: Int) {
        call { c -> c.ok(Op.EOS_DRIVE_LENS, intArrayOf(param)) }
    }

    /**
     * Exposure bracketing: for each shot, set the value, give the camera a moment, shoot (autofocus only on
     * the first shot, so every frame is focused the same), and put the starting value back at the end.
     */
    fun startBracket(plan: BracketPlan) {
        if (seriesJob?.isActive == true || bulbJob?.isActive == true || client == null) return
        val total = plan.shots.size
        _series.value = Series(Series.Kind.BRACKET, 0, total)
        seriesJob = scope.launch {
            var error: String? = null
            try {
                plan.shots.forEachIndexed { i, shot ->
                    setProp(plan.method.prop, shot.code)
                    delay(SETTLE_MS)
                    shoot(af = i == 0)
                    _series.value = Series(Series.Kind.BRACKET, i + 1, total)
                    delay(BETWEEN_SHOTS_MS)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message.orEmpty()
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    if (client != null) runCatching { setProp(plan.method.prop, plan.restore) }
                    _series.value = _series.value?.copy(error = error, finished = true)
                }
            }
        }
    }

    /**
     * Focus stacking: shot, focus moved by DriveLens, next shot… Shots are taken without autofocus so the
     * camera doesn't undo the steps. Live view must be running (the caller is the live view screen).
     */
    fun startFocusStack(actions: List<FocusStack.Action>) {
        if (seriesJob?.isActive == true || bulbJob?.isActive == true || client == null) return
        val total = actions.count { it is FocusStack.Action.Shoot }
        _series.value = Series(Series.Kind.FOCUS_STACK, 0, total)
        seriesJob = scope.launch {
            var error: String? = null
            var done = 0
            try {
                for (a in actions) {
                    when (a) {
                        is FocusStack.Action.Shoot -> {
                            shoot(af = false)
                            done++
                            _series.value = Series(Series.Kind.FOCUS_STACK, done, total)
                            delay(BETWEEN_SHOTS_MS)
                        }
                        is FocusStack.Action.Drive -> {
                            driveLens(a.param)
                            delay(DRIVE_MS)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message.orEmpty()
            } finally {
                _series.value = _series.value?.copy(error = error, finished = true)
            }
        }
    }

    /** Stops a series after the step in progress (bracketing still puts the starting value back). */
    fun stopSeries() {
        seriesJob?.cancel()
    }

    fun clearSeries() {
        if (seriesJob?.isActive != true) _series.value = null
    }

    val seriesRunning: Boolean get() = seriesJob?.isActive == true

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

    private companion object {
        /** After a setting change, before the shot. */
        const val SETTLE_MS = 400L
        /** After a shot, so the camera can store it before the next one. */
        const val BETWEEN_SHOTS_MS = 900L
        /** After each focus step. */
        const val DRIVE_MS = 250L
    }

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
