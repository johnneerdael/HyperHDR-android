package eu.hyperhdr.android.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProfileStoreTest {

    private class InMemoryStorage : ProfileStorage {
        private val map = mutableMapOf<String, String>()
        override fun read(key: String): String? = map[key]
        override fun write(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    @Test
    fun `save then load round-trips a profile with a token`() {
        val store = ProfileStore(InMemoryStorage())
        val original = ServerProfile(
            host = "192.168.1.10", instanceId = 2, priority = 80,
            token = "abc123", highQuality = true,
        )
        store.save(original)
        assertThat(store.load()).isEqualTo(original)
    }

    @Test
    fun `clear removes the profile`() {
        val store = ProfileStore(InMemoryStorage())
        store.save(ServerProfile(host = "x"))
        store.clear()
        assertThat(store.load()).isNull()
    }

    @Test
    fun `hdrNative round-trips through save and load`() {
        val store = ProfileStore(InMemoryStorage())
        val original = ServerProfile(
            host = "192.168.1.10", hdrAware = true, hdrNative = true,
        )
        store.save(original)
        assertThat(store.load()).isEqualTo(original)
    }
}
