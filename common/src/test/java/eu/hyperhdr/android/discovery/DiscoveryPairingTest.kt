package eu.hyperhdr.android.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiscoveryPairingTest {

    @Test
    fun `flatbuf and json on same host get paired`() {
        val pairing = DiscoveryPairing()
        pairing.onResolved("server-a", "192.168.1.10", 19400, isFlatbuf = true)
        assertThat(pairing.servers()).isEmpty()

        pairing.onResolved("server-a", "192.168.1.10", 19444, isFlatbuf = false)
        assertThat(pairing.servers()).containsExactly(
            DiscoveredServer("server-a", "192.168.1.10", 19400, 19444),
        )
    }

    @Test
    fun `multiple hosts produce multiple paired servers`() {
        val pairing = DiscoveryPairing()
        pairing.onResolved("a", "192.168.1.10", 19400, true)
        pairing.onResolved("a", "192.168.1.10", 19444, false)
        pairing.onResolved("b", "192.168.1.20", 19400, true)
        pairing.onResolved("b", "192.168.1.20", 19444, false)

        assertThat(pairing.servers()).hasSize(2)
    }

    @Test
    fun `lost service removes the pair`() {
        val pairing = DiscoveryPairing()
        pairing.onResolved("a", "192.168.1.10", 19400, true)
        pairing.onResolved("a", "192.168.1.10", 19444, false)
        pairing.onLost("a", isFlatbuf = true)
        assertThat(pairing.servers()).isEmpty()
    }
}
