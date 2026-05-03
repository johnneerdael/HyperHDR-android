package eu.hyperhdr.android.capture

/**
 * Pure rate-limit logic. Not thread-safe — the GL pipelines call into this from a single
 * Handler-bound thread, which serialises the access. Lives in [common] (no Android deps)
 * so it can be JVM-unit-tested.
 *
 * Usage:
 * ```
 * val gate = FrameRateGate(targetFps = config.frameRate)
 * if (!gate.shouldTick(System.nanoTime())) return
 * ```
 */
internal class FrameRateGate(targetFps: Int) {

    private val intervalNanos: Long =
        if (targetFps > 0) 1_000_000_000L / targetFps else 0L

    private var lastTickNanos: Long? = null

    /**
     * Returns true if enough time has elapsed since the previous successful tick to
     * permit a new one. The first call always returns true (no anchor yet). Calls that
     * return false do NOT advance the anchor — callers can re-check at any later moment
     * without losing alignment with the original cadence.
     *
     * `targetFps <= 0` disables gating entirely (always returns true).
     */
    fun shouldTick(nowNanos: Long): Boolean {
        if (intervalNanos == 0L) return true
        val last = lastTickNanos
        if (last != null && nowNanos - last < intervalNanos) return false
        lastTickNanos = nowNanos
        return true
    }
}
