package eu.hyperhdr.android.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HyperHdrMdnsDiscovery(
    private val nsd: NsdManager,
    private val wifiManager: WifiManager? = null,
) {
    companion object {
        private const val TYPE_FLATBUF = "_hyperhdr-flatbuf._tcp"
        private const val TYPE_JSON = "_hyperhdr-json._tcp"
        private const val TAG = "HyperHdrMdns"
    }

    private val pairing = DiscoveryPairing()
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private var flatbufListener: NsdManager.DiscoveryListener? = null
    private var jsonListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        multicastLock = wifiManager?.createMulticastLock(TAG)?.apply {
            setReferenceCounted(true)
            acquire()
        }
        flatbufListener = listenerFor(TYPE_FLATBUF, isFlatbuf = true)
        jsonListener = listenerFor(TYPE_JSON, isFlatbuf = false)
        nsd.discoverServices(TYPE_FLATBUF, NsdManager.PROTOCOL_DNS_SD, flatbufListener)
        nsd.discoverServices(TYPE_JSON, NsdManager.PROTOCOL_DNS_SD, jsonListener)
    }

    fun stop() {
        flatbufListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        jsonListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        flatbufListener = null
        jsonListener = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private fun listenerFor(type: String, isFlatbuf: Boolean) = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(s: String?, errorCode: Int) {}
        override fun onStopDiscoveryFailed(s: String?, errorCode: Int) {}
        override fun onDiscoveryStarted(s: String?) {}
        override fun onDiscoveryStopped(s: String?) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    pairing.onResolved(resolved.serviceName ?: host, host, resolved.port, isFlatbuf)
                    _servers.value = pairing.servers()
                }
            })
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            pairing.onLost(serviceInfo.serviceName ?: return, isFlatbuf)
            _servers.value = pairing.servers()
        }
    }
}
