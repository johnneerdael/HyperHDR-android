package eu.hyperhdr.android.settings

data class ServerProfile(
    val host: String,
    val flatbufPort: Int = 19400,
    val jsonPort: Int = 19444,
    val instanceId: Int = 0,
    val priority: Int = 100,
    val token: String? = null,
    val highQuality: Boolean = false,
)
