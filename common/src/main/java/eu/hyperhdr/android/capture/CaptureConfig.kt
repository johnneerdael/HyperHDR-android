package eu.hyperhdr.android.capture

data class CaptureConfig(
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val tier: CaptureTier,
) {
    init {
        require(width > 0 && width % 2 == 0) { "width must be positive and even (got $width)" }
        require(height > 0 && height % 2 == 0) { "height must be positive and even (got $height)" }
        require(frameRate > 0) { "frameRate must be positive (got $frameRate)" }
    }

    companion object {
        val STANDARD = CaptureConfig(160, 90, 30, CaptureTier.SDR)
        val HIGH = CaptureConfig(192, 108, 60, CaptureTier.SDR)
    }
}
