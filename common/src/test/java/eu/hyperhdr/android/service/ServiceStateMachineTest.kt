package eu.hyperhdr.android.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServiceStateMachineTest {

    @Test
    fun `idle toggleOn transitions to connecting`() {
        val sm = ServiceStateMachine(ServiceState.IDLE)
        sm.onToggleOn()
        assertThat(sm.state).isEqualTo(ServiceState.CONNECTING)
    }

    @Test
    fun `connecting onConnected transitions to streaming`() {
        val sm = ServiceStateMachine(ServiceState.CONNECTING)
        sm.onConnected()
        assertThat(sm.state).isEqualTo(ServiceState.STREAMING)
    }

    @Test
    fun `streaming onProjectionPaused transitions to paused`() {
        val sm = ServiceStateMachine(ServiceState.STREAMING)
        sm.onProjectionPaused()
        assertThat(sm.state).isEqualTo(ServiceState.PAUSED)
    }

    @Test
    fun `paused onProjectionResumed transitions to streaming`() {
        val sm = ServiceStateMachine(ServiceState.PAUSED)
        sm.onProjectionResumed()
        assertThat(sm.state).isEqualTo(ServiceState.STREAMING)
    }

    @Test
    fun `any onError transitions to error`() {
        for (start in ServiceState.values()) {
            val sm = ServiceStateMachine(start)
            sm.onError("test")
            assertThat(sm.state).isEqualTo(ServiceState.ERROR)
        }
    }

    @Test
    fun `any onToggleOff transitions to idle`() {
        for (start in ServiceState.values()) {
            val sm = ServiceStateMachine(start)
            sm.onToggleOff()
            assertThat(sm.state).isEqualTo(ServiceState.IDLE)
        }
    }
}
