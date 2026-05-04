package eu.hyperhdr.android.settings

data class ServerProfile(
    val host: String,
    val flatbufPort: Int = 19400,
    // jsonPort is the HTTP port carrying /json-rpc — i.e. HyperHDR's web server port
    // (default 8090). The raw-TCP JSON server on 19444 is a different protocol and
    // is not used by the OkHttp-based client.
    val jsonPort: Int = 8090,
    val instanceId: Int = 0,
    val priority: Int = 100,
    val token: String? = null,
    val highQuality: Boolean = false,
    val hdrAware: Boolean = false,
    val hdrNative: Boolean = false,
)
