package com.vknxworks.shutterctrl.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The camera as a Bluetooth remote (Canon's BR-E1 protocol, see [CanonBle]): find a camera that is waiting to pair
 * with a remote, pair, and press Focus and Shoot without Wi-Fi. Everything is started by a tap; nothing runs in the
 * background and nothing connects by itself. Needs Android's Nearby devices permission (Bluetooth scan and connect;
 * the scan is declared as never used for location).
 */
@SuppressLint("MissingPermission") // Every entry point checks hasPermissions() first.
class BleRemote(private val context: Context, private val scope: CoroutineScope) {
    data class Found(val address: String, val name: String)

    sealed interface State {
        data object Idle : State
        data class Scanning(val found: List<Found>) : State
        /** Connecting to (and, the first time, pairing with) the camera at [address]. */
        data class Connecting(val address: String, val name: String) : State
        data class Connected(val address: String, val name: String) : State
        data class Failed(val reason: Reason) : State
    }

    enum class Reason { NO_BLUETOOTH, BLUETOOTH_OFF, NO_PERMISSION, NOT_FOUND, CONNECT, NOT_CANON_REMOTE, WRITE, LOST }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val adapter: BluetoothAdapter? get() = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var control: BluetoothGattCharacteristic? = null
    private var scanJob: Job? = null
    private val ops = Mutex()
    private var pendingWrite: CompletableDeferred<Boolean>? = null
    private var connected: CompletableDeferred<Boolean>? = null
    private var discovered: CompletableDeferred<Boolean>? = null

    fun hasPermissions(): Boolean = PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun ready(): Boolean {
        val a = adapter
        _state.value = when {
            a == null -> State.Failed(Reason.NO_BLUETOOTH)
            !hasPermissions() -> State.Failed(Reason.NO_PERMISSION)
            !a.isEnabled -> State.Failed(Reason.BLUETOOTH_OFF)
            else -> return true
        }
        return false
    }

    // ---------- Finding a camera ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val s = _state.value as? State.Scanning ?: return
            val address = result.device.address
            if (s.found.any { it.address == address }) return
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: address
            _state.value = State.Scanning(s.found + Found(address, name))
        }

        override fun onScanFailed(errorCode: Int) {
            _state.value = State.Failed(Reason.NOT_FOUND)
        }
    }

    /** Looks for cameras offering the remote service (the camera's "pair with a remote" screen) for 30 seconds. */
    fun scan() {
        if (!ready()) return
        disconnect()
        val scanner = adapter?.bluetoothLeScanner ?: run { _state.value = State.Failed(Reason.BLUETOOTH_OFF); return }
        _state.value = State.Scanning(emptyList())
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(CanonBle.SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner.startScan(listOf(filter), settings, scanCallback) }.onFailure {
            _state.value = State.Failed(Reason.NOT_FOUND)
            return
        }
        scanJob = scope.launch {
            delay(SCAN_MS)
            stopScan()
            val s = _state.value
            if (s is State.Scanning && s.found.isEmpty()) _state.value = State.Failed(Reason.NOT_FOUND)
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (hasPermissions()) runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    // ---------- Connecting and pairing ----------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connected?.complete(false)
                discovered?.complete(false)
                pendingWrite?.complete(false)
                if (gatt === g) {
                    runCatching { g.close() }
                    gatt = null
                    control = null
                    if (_state.value is State.Connected) _state.value = State.Failed(Reason.LOST)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            discovered?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }
    }

    /**
     * Connects to the camera at [address] and introduces ShutterCtrl as its remote (the pairing write; both
     * projects send it on every connection). The first time, Android pairs (bonds) with the camera first and
     * may ask you to confirm. [onReady] gets the address and name once it worked, to remember the camera.
     */
    fun connect(address: String, name: String, onReady: (String, String) -> Unit = { _, _ -> }) {
        if (!ready()) return
        stopScan()
        disconnect()
        val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: run { _state.value = State.Failed(Reason.CONNECT); return }
        _state.value = State.Connecting(address, name)
        scope.launch {
            val reason = runCatching { open(device) }.getOrElse { Reason.CONNECT }
            if (reason == null) {
                _state.value = State.Connected(address, name)
                onReady(address, name)
            } else {
                close()
                _state.value = State.Failed(reason)
            }
        }
    }

    /** Null when connected and introduced, otherwise why not. */
    private suspend fun open(device: BluetoothDevice): Reason? {
        if (device.bondState != BluetoothDevice.BOND_BONDED) bond(device)
        val c = CompletableDeferred<Boolean>().also { connected = it }
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE) ?: return Reason.CONNECT
        gatt = g
        if (withTimeoutOrNull(CONNECT_MS) { c.await() } != true) return Reason.CONNECT
        val d = CompletableDeferred<Boolean>().also { discovered = it }
        if (!g.discoverServices() || withTimeoutOrNull(CONNECT_MS) { d.await() } != true) return Reason.CONNECT
        val service = g.getService(CanonBle.SERVICE) ?: return Reason.NOT_CANON_REMOTE
        val pair = service.getCharacteristic(CanonBle.PAIR) ?: return Reason.NOT_CANON_REMOTE
        val ctl = service.getCharacteristic(CanonBle.CONTROL) ?: return Reason.NOT_CANON_REMOTE
        if (!write(g, pair, CanonBle.pairCommand())) return Reason.WRITE
        control = ctl
        return null
    }

    /** Bonds ("just works", as furble does) and waits up to a minute for you to confirm, if Android asks. */
    private suspend fun bond(device: BluetoothDevice) {
        val done = CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                if (d.address != device.address) return
                val st = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                if (st == BluetoothDevice.BOND_BONDED || st == BluetoothDevice.BOND_NONE) done.complete(Unit)
            }
        }
        context.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        try {
            if (device.createBond()) withTimeoutOrNull(BOND_MS) { done.await() }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private suspend fun write(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray): Boolean = ops.withLock {
        val w = CompletableDeferred<Boolean>().also { pendingWrite = it }
        val started = g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
        started && withTimeoutOrNull(WRITE_MS) { w.await() } == true
    }

    // ---------- Buttons ----------

    /** Focus (half press) held while [down]. */
    suspend fun focus(down: Boolean): Boolean = send(if (down) CanonBle.pressFocus else CanonBle.releaseAll)

    /** One photo: the shutter button pressed and let go. */
    suspend fun shoot(): Boolean {
        val ok = send(CanonBle.pressRelease)
        delay(PRESS_MS)
        return send(CanonBle.releaseAll) && ok
    }

    private suspend fun send(value: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = control ?: return false
        if (!hasPermissions()) return false
        return write(g, c, value)
    }

    // ---------- Ending ----------

    private fun close() {
        val g = gatt ?: return
        gatt = null
        control = null
        if (hasPermissions()) runCatching { g.disconnect(); g.close() }
    }

    /** Disconnects (the camera stays paired, on the phone and in the camera). */
    fun disconnect() {
        close()
        if (_state.value !is State.Scanning) _state.value = State.Idle
    }

    fun dismiss() {
        if (_state.value is State.Failed) _state.value = State.Idle
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        private const val SCAN_MS = 30_000L
        private const val CONNECT_MS = 15_000L
        private const val BOND_MS = 60_000L
        private const val WRITE_MS = 5_000L
        private const val PRESS_MS = 200L
    }
}
