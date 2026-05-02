package eu.hyperhdr.android.discovery

data class DiscoveredServer(
    val name: String,
    val host: String,
    val flatbufPort: Int,
    val jsonPort: Int,
)
