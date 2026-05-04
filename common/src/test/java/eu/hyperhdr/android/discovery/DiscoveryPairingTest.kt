package eu.hyperhdr.android.discovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiscoveryPairingTest {

    @Test
    fun `resolved record produces a discovered server`() {
        val pairing = DiscoveryPairing()
        pairing.onResolved("server-a", "192.168.1.10", jsonPort = 8090, flatbufPort = 19400)

        assertThat(pairing.servers()).containsExactly(
            DiscoveredServer("server-a", "192.168.1.10", flatbufPort = 19400, jsonPort = 8090),
        )
    }

    @Test
    fun `multiple hosts produce multiple servers`() {
        val pairing = DiscoveryPairing()
        pairing.onResolved("a", "192.168.1.10", jsonPort = 8090, flatbufPort = 19400)
        pairing.onResolved("b", "192.168.1.20", jsonPort = 8090, flatbufPort = 19400)

        assertThat(pairing.servers()).hasSize(2)
    }

    @Test
    fun `lost service is removed`() {
        val pairing = DiscoveryPairing()
        pairing.onResolved("a", "192.168.1.10", jsonPort = 8090, flatbufPort = 19400)
        pairing.onLost("a")

        assertThat(pairing.servers()).isEmpty()
    }

    @Test
    fun `re-resolve updates the record in place`() {
        val pairing = DiscoveryPairing()
        pairing.onResolved("a", "192.168.1.10", jsonPort = 8090, flatbufPort = 19400)
        pairing.onResolved("a", "192.168.1.10", jsonPort = 9090, flatbufPort = 19400)

        assertThat(pairing.servers()).containsExactly(
            DiscoveredServer("a", "192.168.1.10", flatbufPort = 19400, jsonPort = 9090),
        )
    }
}
