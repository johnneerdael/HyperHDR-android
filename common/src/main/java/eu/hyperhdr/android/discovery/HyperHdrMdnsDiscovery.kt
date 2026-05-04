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
        // HyperHDR's WebServer registers exactly one Bonjour service. The JSON server
        // (raw TCP, port 19400) and flatbuffers server are NOT advertised — only this.
        // See HyperHDR/sources/bonjour/DiscoveryRecord.cpp and webserver/WebServer.cpp.
        private const val TYPE_HTTP = "_hyperhdr-http._tcp"
        // The raw TCP flatbuffers port is fixed by HyperHDR upstream and not configurable
        // through mDNS — fall back to the documented default and let manual entry override.
        private const val DEFAULT_FLATBUF_PORT = 19400
        private const val TAG = "HyperHdrMdns"
    }

    private val pairing = DiscoveryPairing()
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start() {
        multicastLock = wifiManager?.createMulticastLock(TAG)?.apply {
            setReferenceCounted(true)
            acquire()
        }
        listener = makeListener()
        nsd.discoverServices(TYPE_HTTP, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    private fun makeListener() = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(s: String?, errorCode: Int) {}
        override fun onStopDiscoveryFailed(s: String?, errorCode: Int) {}
        override fun onDiscoveryStarted(s: String?) {}
        override fun onDiscoveryStopped(s: String?) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    pairing.onResolved(
                        name = resolved.serviceName ?: host,
                        host = host,
                        // The advertised port IS HyperHDR's web server port and the same
                        // endpoint that hosts /json-rpc over HTTP — exactly what the
                        // OkHttp-based JSON client needs.
                        jsonPort = resolved.port,
                        flatbufPort = DEFAULT_FLATBUF_PORT,
                    )
                    _servers.value = pairing.servers()
                }
            })
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            pairing.onLost(serviceInfo.serviceName ?: return)
            _servers.value = pairing.servers()
        }
    }
}
