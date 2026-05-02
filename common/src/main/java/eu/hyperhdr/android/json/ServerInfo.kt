package eu.hyperhdr.android.json

data class ServerInfo(
    val version: String,
    val instances: List<Instance>,
    val authRequired: Boolean,
)
