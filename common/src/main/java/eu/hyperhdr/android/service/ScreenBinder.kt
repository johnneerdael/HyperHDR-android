package eu.hyperhdr.android.service

import android.content.Intent
import eu.hyperhdr.android.json.JsonEvent
import eu.hyperhdr.android.stats.LiveStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The subset of [HyperHdrServiceBinder] that screens depend on. Production:
 * [HyperHdrServiceBinder] implements this directly. Tests provide their own implementation
 * (e.g. StubBinder) for deterministic state without instantiating the service.
 */
interface ScreenBinder {
    val state: StateFlow<ServiceState>
    val stats: StateFlow<LiveStats>
    val serverHdrSignaled: StateFlow<Boolean>
    val jsonEventsFlow: Flow<JsonEvent>
    fun lastError(): String?
    fun startCapture(projectionResultCode: Int, projectionData: Intent)
    fun stopCapture()
    suspend fun setHdrVideoMode(hdr: Boolean): Boolean
}
