package eu.hyperhdr.android.json

/**
 * Slim subset of HyperHDR's serverinfo response that the wizard and capture
 * service consume.
 *
 * The optional [flatbufferFormats] / [flatbufferWireVersion] fields come from
 * the `flatbuffer` capability block introduced by the P010 fork. Stock
 * HyperHDR returns no such block — [flatbufferFormats] is empty and
 * [flatbufferWireVersion] is null in that case, which both translate to
 * "wire v1: RawImage + NV12Image only" on the client side.
 */
data class ServerInfo(
    val version: String,
    val instances: List<Instance>,
    val authRequired: Boolean,
    val flatbufferFormats: Set<String> = emptySet(),
    val flatbufferWireVersion: Int? = null,
) {
    val supportsP010: Boolean get() = "P010Image" in flatbufferFormats
}
