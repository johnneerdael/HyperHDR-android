package eu.hyperhdr.android.tv.compose.support

import android.content.Intent
import eu.hyperhdr.android.json.JsonEvent
import eu.hyperhdr.android.service.ScreenBinder
import eu.hyperhdr.android.service.ServiceState
import eu.hyperhdr.android.stats.LiveStats
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class StubBinder(
    initialState: ServiceState = ServiceState.IDLE,
    initialStats: LiveStats = LiveStats(0.0, 0L, 0, 0, null),
    initialServerHdr: Boolean = false,
) : ScreenBinder {
    private val _state = MutableStateFlow(initialState)
    private val _stats = MutableStateFlow(initialStats)
    private val _hdr = MutableStateFlow(initialServerHdr)
    private val _events = MutableSharedFlow<JsonEvent>(extraBufferCapacity = 16)

    override val state: StateFlow<ServiceState> = _state.asStateFlow()
    override val stats: StateFlow<LiveStats> = _stats.asStateFlow()
    override val serverHdrSignaled: StateFlow<Boolean> = _hdr.asStateFlow()
    override val jsonEventsFlow: SharedFlow<JsonEvent> = _events.asSharedFlow()

    override fun lastError(): String? = null
    override fun startCapture(projectionResultCode: Int, projectionData: Intent) {
        _state.value = ServiceState.CONNECTING
    }
    override fun stopCapture() { _state.value = ServiceState.IDLE }
    override suspend fun setHdrVideoMode(hdr: Boolean): Boolean = true

    fun pushState(s: ServiceState) { _state.value = s }
    fun pushStats(s: LiveStats) { _stats.value = s }
    fun pushHdr(hdr: Boolean) { _hdr.value = hdr }
    suspend fun pushEvent(ev: JsonEvent) { _events.emit(ev) }

    companion object {
        fun idle() = StubBinder(initialState = ServiceState.IDLE)
        fun streaming(fps: Double = 30.0) = StubBinder(
            initialState = ServiceState.STREAMING,
            initialStats = LiveStats(fps, 200_000L, 160, 90, null),
        )
        fun error(msg: String) = StubBinder(initialState = ServiceState.ERROR)
    }
}
