package eu.hyperhdr.android.capture

import kotlin.math.max

class FrameClock(
    private val targetFps: Int,
    private val displayHz: Double,
) {
    private val divisor: Int = max(1, (displayHz / targetFps).toInt())

    /** Returns true when this VSYNC index should produce a captured frame. */
    fun shouldEmitForVsync(index: Long): Boolean = (index % divisor) == 0L
}
