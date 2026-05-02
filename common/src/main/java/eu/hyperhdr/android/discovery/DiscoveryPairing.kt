package eu.hyperhdr.android.discovery

class DiscoveryPairing {
    private data class Half(val name: String, val host: String, val port: Int)

    private val flatbuf = mutableMapOf<String, Half>() // keyed by host
    private val json = mutableMapOf<String, Half>()
    private val seenName = mutableMapOf<String, String>() // host → friendly name

    fun onResolved(name: String, host: String, port: Int, isFlatbuf: Boolean) {
        seenName[host] = name
        if (isFlatbuf) flatbuf[host] = Half(name, host, port)
        else json[host] = Half(name, host, port)
    }

    fun onLost(name: String, isFlatbuf: Boolean) {
        val map = if (isFlatbuf) flatbuf else json
        val host = map.entries.firstOrNull { it.value.name == name }?.key ?: return
        map.remove(host)
    }

    fun servers(): List<DiscoveredServer> = flatbuf.keys.intersect(json.keys).map { host ->
        DiscoveredServer(
            name = seenName[host] ?: host,
            host = host,
            flatbufPort = flatbuf.getValue(host).port,
            jsonPort = json.getValue(host).port,
        )
    }
}
