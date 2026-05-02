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
}
