package eu.hyperhdr.android.json

/**
 * Inbound WebSocket events from HyperHDR. Only events that map to a UI surface or service
 * behavior are modelled; others (leds-colors, priorities-update, etc.) are dropped at
 * parse time.
 */
sealed interface JsonEvent {
    /** A single component (COMP_HDR, COMP_LEDDEVICE, etc.) was toggled. */
    data class ComponentChanged(val name: String, val enabled: Boolean) : JsonEvent

    /** An instance started, stopped, or had its friendly name changed. */
    data class InstanceChanged(val instances: List<Instance>) : JsonEvent

    /** Server's external-tonemap mode changed (HDR=0/1 from the videomode JSON-RPC). */
    data class VideoModeChanged(val hdr: Boolean) : JsonEvent

    /** Initial state snapshot delivered after subscribe. */
    data class ServerInfoSnapshot(val info: ServerInfo) : JsonEvent
}
