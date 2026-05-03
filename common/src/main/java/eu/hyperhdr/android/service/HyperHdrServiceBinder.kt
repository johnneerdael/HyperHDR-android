package eu.hyperhdr.android.service

import android.content.Intent
import android.os.Binder
import eu.hyperhdr.android.json.JsonEvent
import eu.hyperhdr.android.stats.LiveStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

class HyperHdrServiceBinder(private val service: HyperHdrCaptureService) : Binder(), ScreenBinder {
    override val state: StateFlow<ServiceState> get() = service.stateFlow
    override fun lastError(): String? = service.lastError()
    override fun startCapture(projectionResultCode: Int, projectionData: Intent) =
        service.startCapture(projectionResultCode, projectionData)
    override fun stopCapture() = service.stopCapture()

    /** Plan 4 wires HDR detection to this; Plan 3 returns false unconditionally. */
    override suspend fun setHdrVideoMode(hdr: Boolean): Boolean = service.setHdrVideoMode(hdr)

    override val stats: StateFlow<LiveStats> get() = service.statsCollector.stats

    /** Latest HDR-tonemap state reported by the server over the WebSocket. */
    override val serverHdrSignaled: StateFlow<Boolean> get() = service.serverHdrSignaled

    /** Live stream of all WebSocket events from the server (since service start). */
    override val jsonEventsFlow: Flow<JsonEvent>
        get() = service.jsonEvents?.flow ?: emptyFlow()
}
