package eu.hyperhdr.android.discovery

class DiscoveryPairing {
    private val byName = mutableMapOf<String, DiscoveredServer>()

    fun onResolved(name: String, host: String, jsonPort: Int, flatbufPort: Int) {
        byName[name] = DiscoveredServer(
            name = name,
            host = host,
            flatbufPort = flatbufPort,
            jsonPort = jsonPort,
        )
    }

    fun onLost(name: String) {
        byName.remove(name)
    }

    fun servers(): List<DiscoveredServer> = byName.values.toList()
}
