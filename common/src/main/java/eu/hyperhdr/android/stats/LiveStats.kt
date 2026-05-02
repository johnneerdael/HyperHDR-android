package eu.hyperhdr.android.stats

data class LiveStats(
    val fps: Double,
    val bytesPerSec: Long,
    val width: Int,
    val height: Int,
    val lastErrorMessage: String?,
)
