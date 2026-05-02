package eu.hyperhdr.android.service

import android.content.Intent
import android.os.Binder
import kotlinx.coroutines.flow.StateFlow

class HyperHdrServiceBinder(private val service: HyperHdrCaptureService) : Binder() {
    val state: StateFlow<ServiceState> get() = service.stateFlow
    fun lastError(): String? = service.lastError()
    fun startCapture(projectionResultCode: Int, projectionData: Intent) =
        service.startCapture(projectionResultCode, projectionData)
    fun stopCapture() = service.stopCapture()

    /** Plan 4 wires HDR detection to this; Plan 3 returns false unconditionally. */
    suspend fun setHdrVideoMode(hdr: Boolean): Boolean = service.setHdrVideoMode(hdr)

    val stats: StateFlow<eu.hyperhdr.android.stats.LiveStats> get() = service.statsCollector.stats

    /** Latest HDR-tonemap state reported by the server over the WebSocket. */
    val serverHdrSignaled: kotlinx.coroutines.flow.StateFlow<Boolean> get() = service.serverHdrSignaled

    /** Live stream of all WebSocket events from the server (since service start). */
    val jsonEventsFlow: kotlinx.coroutines.flow.Flow<eu.hyperhdr.android.json.JsonEvent>
        get() = service.jsonEvents?.flow ?: kotlinx.coroutines.flow.emptyFlow()
}
