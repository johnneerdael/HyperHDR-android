package eu.hyperhdr.android.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ServiceStateMachine(initial: ServiceState = ServiceState.IDLE) {
    private val _state = MutableStateFlow(initial)
    val flow: StateFlow<ServiceState> = _state.asStateFlow()
    val state: ServiceState get() = _state.value

    var lastErrorMessage: String? = null
        private set

    fun onToggleOn() { transition(ServiceState.CONNECTING) }
    fun onToggleOff() { transition(ServiceState.IDLE); lastErrorMessage = null }
    fun onConnected() { if (state == ServiceState.CONNECTING) transition(ServiceState.STREAMING) }
    fun onProjectionPaused() { if (state == ServiceState.STREAMING) transition(ServiceState.PAUSED) }
    fun onProjectionResumed() { if (state == ServiceState.PAUSED) transition(ServiceState.STREAMING) }
    fun onError(message: String) { lastErrorMessage = message; transition(ServiceState.ERROR) }
    fun acknowledgeError() { if (state == ServiceState.ERROR) transition(ServiceState.IDLE) }

    private fun transition(next: ServiceState) { _state.value = next }
}
