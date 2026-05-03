package eu.hyperhdr.android.flatbuf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.Closeable

class HyperHdrFlatBufferReconnector(
    private val host: String,
    private val port: Int,
    private val priority: Int = 100,
    private val origin: String = "HyperHDR-Android",
    private val backoff: BackoffSchedule = BackoffSchedule.default(),
    private val statsCollector: eu.hyperhdr.android.stats.LiveStatsCollector? = null,
) : FrameSink, Closeable {

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loop: Job? = null

    @Volatile private var current: HyperHdrFlatBufferClient? = null

    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        while (true) {
            _state.value = ConnectionState.CONNECTING
            val client = HyperHdrFlatBufferClient(host, port, priority, origin, statsCollector = statsCollector)
            try {
                client.connect()
                current = client
                backoff.reset()
                _state.value = ConnectionState.CONNECTED

                // Suspend until the client transitions to ERROR or DISCONNECTED.
                client.state.collect { s ->
                    if (s == ConnectionState.ERROR || s == ConnectionState.DISCONNECTED) {
                        throw java.io.IOException("client transitioned to $s")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Don't swallow cancellation — let close() actually stop us.
                throw e
            } catch (_: Exception) {
                _state.value = ConnectionState.ERROR
                current?.close()
                current = null
                delay(backoff.nextDelayMillis())
            }
        }
    }

    override fun sendNv12(
        yPlane: ByteArray, uvPlane: ByteArray,
        width: Int, height: Int, strideY: Int, strideUv: Int,
    ) {
        current?.sendNv12(yPlane, uvPlane, width, height, strideY, strideUv)
    }

    override fun sendP010(
        yPlane: ByteArray, uvPlane: ByteArray,
        width: Int, height: Int, strideY: Int, strideUv: Int,
    ) {
        current?.sendP010(yPlane, uvPlane, width, height, strideY, strideUv)
    }

    fun clear(priority: Int = -1) { current?.clear(priority) }
    fun setColor(rgb: Int, durationMs: Int = -1) { current?.setColor(rgb, durationMs) }

    override fun close() {
        loop?.cancel()
        scope.cancel()
        current?.close()
        current = null
        _state.value = ConnectionState.DISCONNECTED
    }
}
