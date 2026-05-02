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
}
