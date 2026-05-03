package eu.hyperhdr.android.flatbuf

interface FrameSink {
    fun sendNv12(
        yPlane: ByteArray,
        uvPlane: ByteArray,
        width: Int,
        height: Int,
        strideY: Int,
        strideUv: Int,
    )

    /**
     * Send a 10-bit P010 frame. Default implementation throws — only sinks that connect
     * to a server understanding the P010 wire variant override this. The current GPU pipeline
     * never calls this on a sink that doesn't support it; the service-level tier selector
     * guarantees pairing.
     */
    fun sendP010(
        yPlane: ByteArray,
        uvPlane: ByteArray,
        width: Int,
        height: Int,
        strideY: Int,
        strideUv: Int,
    ) {
        throw UnsupportedOperationException("This FrameSink does not support P010 frames")
    }
}
