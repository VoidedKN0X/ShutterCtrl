package com.vknxworks.shutterctrl.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.InetAddress
import javax.net.SocketFactory

/**
 * Follows the phone's Wi-Fi network. When the phone joins the camera's own access point, Android keeps
 * mobile data as the default route (the camera's Wi-Fi has no internet), so every camera socket is
 * created through this Wi-Fi [Network] instead of the default one. Needs only ACCESS_NETWORK_STATE.
 */
class CameraNetwork(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    data class WifiState(val network: Network?, val hasInternet: Boolean, val gateway: InetAddress?, val ownAddress: InetAddress?)

    private val _wifi = MutableStateFlow(WifiState(null, false, null, null))
    val wifi: StateFlow<WifiState> = _wifi

    private var lp: LinkProperties? = null
    private var caps: NetworkCapabilities? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            caps = nc; publish(network)
        }

        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            lp = linkProperties; publish(network)
        }

        override fun onLost(network: Network) {
            if (_wifi.value.network == network) {
                lp = null; caps = null
                _wifi.value = WifiState(null, false, null, null)
            }
        }
    }

    private fun publish(network: Network) {
        val l = lp
        val gateway = l?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
            ?.takeIf { LocalAddress.isAllowed(it) }
        val own = l?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address }
        val internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        _wifi.value = WifiState(network, internet, gateway, own)
    }

    private var started = false

    fun start() {
        if (started) return
        started = true
        // No INTERNET capability required: the camera's own access point has none.
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(req, callback) }.onFailure { started = false }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    /** Sockets on the Wi-Fi network when there is one, else on the default network (e.g. Ethernet). */
    fun socketFactory(): SocketFactory = _wifi.value.network?.socketFactory ?: SocketFactory.getDefault()

    fun bind(socket: java.net.DatagramSocket) {
        _wifi.value.network?.bindSocket(socket)
    }
}
