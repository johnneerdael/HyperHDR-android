package eu.hyperhdr.android.stats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

class LiveStatsCollector {
    private val frames = AtomicLong()
    private val bytes = AtomicLong()
    @Volatile private var lastWidth = 0
    @Volatile private var lastHeight = 0
    @Volatile private var lastErr: String? = null
    private var lastSampleNanos = System.nanoTime()
    private var lastFrames = 0L
    private var lastBytes = 0L

    private val _stats = MutableStateFlow(LiveStats(0.0, 0L, 0, 0, null))
    val stats: StateFlow<LiveStats> = _stats.asStateFlow()

    fun onFrame(byteCount: Int, width: Int, height: Int) {
        frames.incrementAndGet()
        bytes.addAndGet(byteCount.toLong())
        lastWidth = width; lastHeight = height
    }

    fun onError(message: String) { lastErr = message }
    fun onErrorCleared() { lastErr = null }

    /** Call this once a second (e.g., from a coroutine timer in the service). */
    fun sample() {
        val now = System.nanoTime()
        val deltaSec = (now - lastSampleNanos) / 1e9
        if (deltaSec <= 0) return
        val curFrames = frames.get(); val curBytes = bytes.get()
        val fps = (curFrames - lastFrames) / deltaSec
        val bps = ((curBytes - lastBytes) / deltaSec).toLong()
        lastFrames = curFrames; lastBytes = curBytes; lastSampleNanos = now
        _stats.value = LiveStats(fps, bps, lastWidth, lastHeight, lastErr)
    }
}
