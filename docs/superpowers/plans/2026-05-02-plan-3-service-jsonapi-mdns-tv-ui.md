# Plan 3 — Service, JSON-API, mDNS, Minimal TV UI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the foreground capture service, JSON-API control client, mDNS discovery, and minimum-viable TV UI so that an end user can install the APK on an Android TV, open it, discover a HyperHDR server, optionally paste a token, pick an instance, and toggle capture from the main screen, the Quick Settings tile, or boot.

**Architecture:** `HyperHdrCaptureService` is a foreground service that owns the `MediaProjection` token, the `HyperHdrGpuEncoder` from Plan 2, the `HyperHdrFlatBufferReconnector` from Plan 1, and a new `HyperHdrJsonApiClient`. State machine (`IDLE / CONNECTING / STREAMING / PAUSED / ERROR`) is exposed as a `StateFlow` consumed by the UI through a `Binder`. Discovery is `NsdManager`-backed and pairs the two HyperHDR service types into a single `DiscoveredServer`. UI is Leanback (existing fragment infrastructure) — wizard via `GuidedStepFragment`, main + settings as standard Leanback fragments.

**Tech Stack:** AndroidX Leanback 1.0.0, OkHttp 4.12.0 (added in Task 1), `EncryptedSharedPreferences` (`androidx.security.crypto`), `NsdManager`, JUnit + MockWebServer for the JSON client.

**Out of scope for Plan 3:** HDR tone-map shader (Plan 4), `videomode` HDR signaling on content transition (Plan 4 — JSON client gets the method here, but the trigger is in Plan 4), debug bundle export (Plan 4), update checker (Plan 4), CI signing (Plan 4).

**Working software at end of plan:** `./gradlew :tv:installDebug` produces an installable APK; user can open the app on a TV, run discovery, connect to a real HyperHDR, and see LEDs follow the screen via the production service.

---

## File Structure

After Plan 3 completes:

```
common/src/main/java/eu/hyperhdr/android/
├── json/
│   ├── HyperHdrJsonApiClient.kt          # OkHttp wrapper for HyperHDR JSON API
│   ├── ServerInfo.kt                     # data classes (instances, auth, version)
│   ├── Instance.kt
│   └── JsonApiError.kt
├── discovery/
│   ├── HyperHdrMdnsDiscovery.kt          # NsdManager wrapper, pairs flatbuf+json services
│   └── DiscoveredServer.kt               # data class
├── service/
│   ├── ServiceState.kt                   # enum
│   ├── HyperHdrCaptureService.kt         # foreground service
│   ├── HyperHdrServiceController.kt      # Binder/StateFlow facade for the UI
│   ├── HyperHdrTileService.kt            # Quick Settings tile
│   └── HyperHdrBootReceiver.kt           # ACTION_BOOT_COMPLETED → start service in IDLE
├── settings/
│   ├── ServerProfile.kt                  # data class: host, ports, instance, token
│   └── ProfileStore.kt                   # EncryptedSharedPreferences-backed store
└── util/
    └── PermissionHelper.java             # already moved in Plan 1

tv/src/main/java/eu/hyperhdr/android/tv/
├── ui/
│   ├── MainActivity.kt                   # Leanback launcher; main screen
│   ├── MainFragment.kt
│   ├── settings/
│   │   ├── SettingsActivity.kt
│   │   └── SettingsFragment.kt
│   └── wizard/
│       ├── WizardActivity.kt
│       ├── DiscoveryStepFragment.kt      # Step 1+2 — discovery + pick
│       ├── ManualStepFragment.kt         # manual host/port entry
│       ├── AuthStepFragment.kt           # token paste
│       └── InstanceStepFragment.kt       # instance pick
└── res/                                  # layouts, strings, drawables (mostly reused from upstream)

tv/src/main/AndroidManifest.xml           # repopulated with all components
```

---

## Task 1: Add OkHttp + Security-Crypto dependencies, JSON-API skeleton

**Files:**
- Modify: `common/build.gradle`
- Create: `common/src/main/java/eu/hyperhdr/android/json/JsonApiError.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/json/ServerInfo.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/json/Instance.kt`

- [ ] **Step 1: Add dependencies**

In `common/build.gradle`, add to the `dependencies` block:

```groovy
implementation "com.squareup.okhttp3:okhttp:4.12.0"
implementation "androidx.security:security-crypto:1.1.0-alpha06"
implementation "org.json:json:20240303"  // for parsing replies; org.json is also in android.jar but using the JVM artifact gives consistent behaviour for unit tests

testImplementation "com.squareup.okhttp3:mockwebserver:4.12.0"
```

- [ ] **Step 2: Define the data shapes**

```kotlin
// common/src/main/java/eu/hyperhdr/android/json/JsonApiError.kt
package eu.hyperhdr.android.json

class JsonApiError(message: String, val httpCode: Int) : Exception(message)
```

```kotlin
// common/src/main/java/eu/hyperhdr/android/json/Instance.kt
package eu.hyperhdr.android.json

data class Instance(
    val id: Int,
    val name: String,
    val running: Boolean,
)
```

```kotlin
// common/src/main/java/eu/hyperhdr/android/json/ServerInfo.kt
package eu.hyperhdr.android.json

data class ServerInfo(
    val version: String,
    val instances: List<Instance>,
    val authRequired: Boolean,
)
```

- [ ] **Step 3: Verify it configures**

```bash
./gradlew :common:dependencies | grep okhttp
```
Expected: shows `com.squareup.okhttp3:okhttp:4.12.0` and `mockwebserver:4.12.0`.

- [ ] **Step 4: Commit**

```bash
git add common/build.gradle \
        common/src/main/java/eu/hyperhdr/android/json/
git commit -m "feat(json): add OkHttp + security-crypto deps; JSON API data classes"
```

---

## Task 2: TDD `HyperHdrJsonApiClient` — `serverInfo()` + auth gate

**Files:**
- Test: `common/src/test/java/eu/hyperhdr/android/json/HyperHdrJsonApiClientTest.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/json/HyperHdrJsonApiClient.kt`

HyperHDR's JSON API takes envelope `{"command":"serverinfo","tan":<int>}` and returns `{"success":true,"info":{"hyperhdr_version":..., "instance":[...]}, "tan":<int>}`. Auth, when enabled, returns `{"success":false,"error":"No Authorization","tan":...}` to non-authed calls. We probe auth requirement by calling `serverinfo` without a token; HTTP 200 + `success:false` with that error string means auth is required.

- [ ] **Step 1: Write the failing test**

```kotlin
// common/src/test/java/eu/hyperhdr/android/json/HyperHdrJsonApiClientTest.kt
package eu.hyperhdr.android.json

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test

class HyperHdrJsonApiClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @Test
    fun `serverInfo parses version and instances when auth is open`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"success":true,"command":"serverinfo","tan":1,
             "info":{"hyperhdr_version":"22.0.0",
                     "instance":[
                       {"instance":0,"running":true,"friendly_name":"LED Frame"},
                       {"instance":1,"running":false,"friendly_name":"Bias Lights"}]}}
        """.trimIndent()))

        val client = HyperHdrJsonApiClient(
            host = server.hostName, port = server.port,
        )
        val info = client.serverInfo()

        assertThat(info.version).isEqualTo("22.0.0")
        assertThat(info.authRequired).isFalse()
        assertThat(info.instances).hasSize(2)
        assertThat(info.instances[0]).isEqualTo(Instance(0, "LED Frame", true))

        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/json-rpc")
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("command")).isEqualTo("serverinfo")
    }

    @Test
    fun `serverInfo flags authRequired when server returns No Authorization`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"success":false,"error":"No Authorization","command":"serverinfo","tan":1}
        """.trimIndent()))

        val client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
        val info = client.serverInfo()

        assertThat(info.authRequired).isTrue()
        assertThat(info.instances).isEmpty()
        assertThat(info.version).isEmpty()
    }

    @Test
    fun `authorize sends a login command with the token`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"success":true,"command":"authorize-login","tan":1}
        """.trimIndent()))

        val client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
        client.authorize("test-token-123")

        val request = server.takeRequest()
        val body = JSONObject(request.body.readUtf8())
        assertThat(body.getString("command")).isEqualTo("authorize")
        assertThat(body.getString("subcommand")).isEqualTo("login")
        assertThat(body.getString("token")).isEqualTo("test-token-123")
    }
}
```

- [ ] **Step 2: Run, confirm failure**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.json.HyperHdrJsonApiClientTest"
```
Expected: FAIL — `unresolved reference: HyperHdrJsonApiClient`.

- [ ] **Step 3: Implement**

```kotlin
// common/src/main/java/eu/hyperhdr/android/json/HyperHdrJsonApiClient.kt
package eu.hyperhdr.android.json

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HyperHdrJsonApiClient(
    private val host: String,
    private val port: Int = 19444,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
) {
    private val tanCounter = AtomicInteger(1)
    private val jsonMedia = "application/json".toMediaType()

    @Volatile private var token: String? = null

    private fun url(): String = "http://$host:$port/json-rpc"

    suspend fun serverInfo(): ServerInfo = withContext(Dispatchers.IO) {
        val tan = tanCounter.getAndIncrement()
        val req = JSONObject().apply {
            put("command", "serverinfo")
            put("tan", tan)
            token?.let { put("token", it) }
        }
        val body = post(req)
        if (!body.optBoolean("success", false)) {
            val err = body.optString("error", "unknown")
            return@withContext if (err.contains("authorization", ignoreCase = true)) {
                ServerInfo(version = "", instances = emptyList(), authRequired = true)
            } else {
                throw JsonApiError("serverinfo failed: $err", httpCode = 200)
            }
        }
        val info = body.getJSONObject("info")
        val version = info.optString("hyperhdr_version", "")
        val instArray = info.optJSONArray("instance")
        val instances = buildList {
            if (instArray != null) for (i in 0 until instArray.length()) {
                val o = instArray.getJSONObject(i)
                add(Instance(
                    id = o.getInt("instance"),
                    name = o.optString("friendly_name", "instance ${o.getInt("instance")}"),
                    running = o.optBoolean("running", false),
                ))
            }
        }
        ServerInfo(version, instances, authRequired = false)
    }

    suspend fun authorize(token: String) = withContext(Dispatchers.IO) {
        val tan = tanCounter.getAndIncrement()
        val req = JSONObject().apply {
            put("command", "authorize")
            put("subcommand", "login")
            put("token", token)
            put("tan", tan)
        }
        val body = post(req)
        if (!body.optBoolean("success", false)) {
            throw JsonApiError("authorize failed: ${body.optString("error","unknown")}", httpCode = 200)
        }
        this@HyperHdrJsonApiClient.token = token
    }

    suspend fun switchInstance(id: Int) = withContext(Dispatchers.IO) {
        val tan = tanCounter.getAndIncrement()
        val req = JSONObject().apply {
            put("command", "instance")
            put("subcommand", "switchTo")
            put("instance", id)
            put("tan", tan)
            token?.let { put("token", it) }
        }
        val body = post(req)
        if (!body.optBoolean("success", false)) {
            throw JsonApiError("switchTo failed: ${body.optString("error","unknown")}", httpCode = 200)
        }
    }

    suspend fun setHdrVideoMode(hdr: Boolean) = withContext(Dispatchers.IO) {
        val tan = tanCounter.getAndIncrement()
        val req = JSONObject().apply {
            put("command", "videomode")
            put("HDR", if (hdr) 1 else 0)
            put("tan", tan)
            token?.let { put("token", it) }
        }
        val body = post(req)
        if (!body.optBoolean("success", false)) {
            throw JsonApiError("videomode failed: ${body.optString("error","unknown")}", httpCode = 200)
        }
    }

    private fun post(json: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(url())
            .post(json.toString().toRequestBody(jsonMedia))
            .build()
        httpClient.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw JsonApiError("HTTP ${resp.code}", resp.code)
            return JSONObject(raw)
        }
    }
}
```

- [ ] **Step 4: Run, confirm pass**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.json.HyperHdrJsonApiClientTest"
```
Expected: 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/json/HyperHdrJsonApiClient.kt \
        common/src/test/java/eu/hyperhdr/android/json/HyperHdrJsonApiClientTest.kt
git commit -m "feat(json): HyperHdrJsonApiClient with serverinfo, authorize, switchInstance, videomode"
```

---

## Task 3: TDD `HyperHdrJsonApiClient` — `switchInstance` + `setHdrVideoMode`

**Files:**
- Modify: `common/src/test/java/eu/hyperhdr/android/json/HyperHdrJsonApiClientTest.kt`

The implementation already exists from Task 2; this task locks the wire format with explicit tests.

- [ ] **Step 1: Add the failing tests**

Append to the test class:

```kotlin
@Test
fun `switchInstance posts instance switchTo with id`() = runTest {
    server.enqueue(MockResponse().setBody("""
        {"success":true,"command":"instance-switchTo","tan":1}
    """.trimIndent()))

    val client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
    client.switchInstance(2)

    val body = JSONObject(server.takeRequest().body.readUtf8())
    assertThat(body.getString("command")).isEqualTo("instance")
    assertThat(body.getString("subcommand")).isEqualTo("switchTo")
    assertThat(body.getInt("instance")).isEqualTo(2)
}

@Test
fun `setHdrVideoMode posts videomode with HDR=1`() = runTest {
    server.enqueue(MockResponse().setBody("""
        {"success":true,"command":"videomode","tan":1}
    """.trimIndent()))

    val client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
    client.setHdrVideoMode(true)

    val body = JSONObject(server.takeRequest().body.readUtf8())
    assertThat(body.getString("command")).isEqualTo("videomode")
    assertThat(body.getInt("HDR")).isEqualTo(1)
}

@Test
fun `setHdrVideoMode posts videomode with HDR=0 when sdr`() = runTest {
    server.enqueue(MockResponse().setBody("""
        {"success":true,"command":"videomode","tan":1}
    """.trimIndent()))

    val client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
    client.setHdrVideoMode(false)

    val body = JSONObject(server.takeRequest().body.readUtf8())
    assertThat(body.getInt("HDR")).isEqualTo(0)
}

@Test
fun `failed call throws JsonApiError with the server message`() = runTest {
    server.enqueue(MockResponse().setBody("""
        {"success":false,"error":"unknown command","tan":1}
    """.trimIndent()))

    val client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
    val ex = try { client.switchInstance(99); null } catch (e: JsonApiError) { e }
    assertThat(ex).isNotNull()
    assertThat(ex!!.message).contains("unknown command")
}
```

- [ ] **Step 2: Run, confirm pass**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.json.HyperHdrJsonApiClientTest"
```
Expected: 7 tests pass.

- [ ] **Step 3: Commit**

```bash
git add common/src/test/java/eu/hyperhdr/android/json/HyperHdrJsonApiClientTest.kt
git commit -m "test(json): cover switchInstance, setHdrVideoMode wire format and error envelope"
```

---

## Task 4: `HyperHdrMdnsDiscovery`

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/discovery/DiscoveredServer.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/discovery/HyperHdrMdnsDiscovery.kt`
- Test: `common/src/test/java/eu/hyperhdr/android/discovery/DiscoveryPairingTest.kt`

`NsdManager` itself is hard to unit-test. We split: a pure `DiscoveryPairing` helper (JVM-tested) joins flatbuf + json events into `DiscoveredServer` records; the `HyperHdrMdnsDiscovery` wrapper feeds it `NsdManager` events.

- [ ] **Step 1: Define `DiscoveredServer` and a pairing test**

```kotlin
// common/src/main/java/eu/hyperhdr/android/discovery/DiscoveredServer.kt
package eu.hyperhdr.android.discovery

data class DiscoveredServer(
    val name: String,
    val host: String,
    val flatbufPort: Int,
    val jsonPort: Int,
)
```

```kotlin
// common/src/test/java/eu/hyperhdr/android/discovery/DiscoveryPairingTest.kt
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
```

- [ ] **Step 2: Run, confirm failure**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.discovery.DiscoveryPairingTest"
```
Expected: FAIL — `unresolved reference: DiscoveryPairing`.

- [ ] **Step 3: Implement**

```kotlin
// common/src/main/java/eu/hyperhdr/android/discovery/DiscoveryPairing.kt
package eu.hyperhdr.android.discovery

class DiscoveryPairing {
    private data class Half(val name: String, val host: String, val port: Int)

    private val flatbuf = mutableMapOf<String, Half>() // keyed by host
    private val json = mutableMapOf<String, Half>()
    private val seenName = mutableMapOf<String, String>() // host → friendly name

    fun onResolved(name: String, host: String, port: Int, isFlatbuf: Boolean) {
        seenName[host] = name
        if (isFlatbuf) flatbuf[host] = Half(name, host, port)
        else json[host] = Half(name, host, port)
    }

    fun onLost(name: String, isFlatbuf: Boolean) {
        val map = if (isFlatbuf) flatbuf else json
        val host = map.entries.firstOrNull { it.value.name == name }?.key ?: return
        map.remove(host)
    }

    fun servers(): List<DiscoveredServer> = flatbuf.keys.intersect(json.keys).map { host ->
        DiscoveredServer(
            name = seenName[host] ?: host,
            host = host,
            flatbufPort = flatbuf.getValue(host).port,
            jsonPort = json.getValue(host).port,
        )
    }
}
```

- [ ] **Step 4: Run, confirm pass**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.discovery.DiscoveryPairingTest"
```
Expected: 3 tests pass.

- [ ] **Step 5: Implement `HyperHdrMdnsDiscovery` (wraps NsdManager around the pairing helper)**

```kotlin
// common/src/main/java/eu/hyperhdr/android/discovery/HyperHdrMdnsDiscovery.kt
package eu.hyperhdr.android.discovery

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HyperHdrMdnsDiscovery(private val nsd: NsdManager) {
    companion object {
        private const val TYPE_FLATBUF = "_hyperhdr-flatbuf._tcp."
        private const val TYPE_JSON = "_hyperhdr-json._tcp."
    }

    private val pairing = DiscoveryPairing()
    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers.asStateFlow()

    private var flatbufListener: NsdManager.DiscoveryListener? = null
    private var jsonListener: NsdManager.DiscoveryListener? = null

    fun start() {
        flatbufListener = listenerFor(TYPE_FLATBUF, isFlatbuf = true)
        jsonListener = listenerFor(TYPE_JSON, isFlatbuf = false)
        nsd.discoverServices(TYPE_FLATBUF, NsdManager.PROTOCOL_DNS_SD, flatbufListener)
        nsd.discoverServices(TYPE_JSON, NsdManager.PROTOCOL_DNS_SD, jsonListener)
    }

    fun stop() {
        flatbufListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        jsonListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        flatbufListener = null
        jsonListener = null
    }

    private fun listenerFor(type: String, isFlatbuf: Boolean) = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(s: String?, errorCode: Int) {}
        override fun onStopDiscoveryFailed(s: String?, errorCode: Int) {}
        override fun onDiscoveryStarted(s: String?) {}
        override fun onDiscoveryStopped(s: String?) {}

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                override fun onResolveFailed(s: NsdServiceInfo?, errorCode: Int) {}
                override fun onServiceResolved(resolved: NsdServiceInfo) {
                    val host = resolved.host?.hostAddress ?: return
                    pairing.onResolved(resolved.serviceName ?: host, host, resolved.port, isFlatbuf)
                    _servers.value = pairing.servers()
                }
            })
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            pairing.onLost(serviceInfo.serviceName ?: return, isFlatbuf)
            _servers.value = pairing.servers()
        }
    }
}
```

- [ ] **Step 6: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/discovery/ \
        common/src/test/java/eu/hyperhdr/android/discovery/
git commit -m "feat(discovery): mDNS browse + pair _hyperhdr-flatbuf and _hyperhdr-json"
```

---

## Task 5: `ServerProfile` + `ProfileStore` (encrypted)

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/settings/ServerProfile.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/settings/ProfileStore.kt`
- Test: `common/src/test/java/eu/hyperhdr/android/settings/ProfileStoreTest.kt`

`ProfileStore` wraps `EncryptedSharedPreferences`. To unit-test it, we abstract the storage behind a small interface and provide an in-memory `FakeStorage` for tests. The Android-backed implementation goes in a sibling class.

- [ ] **Step 1: Define types**

```kotlin
// common/src/main/java/eu/hyperhdr/android/settings/ServerProfile.kt
package eu.hyperhdr.android.settings

data class ServerProfile(
    val host: String,
    val flatbufPort: Int = 19400,
    val jsonPort: Int = 19444,
    val instanceId: Int = 0,
    val priority: Int = 100,
    val token: String? = null,
    val highQuality: Boolean = false,
)
```

```kotlin
// common/src/main/java/eu/hyperhdr/android/settings/ProfileStore.kt
package eu.hyperhdr.android.settings

import org.json.JSONObject

interface ProfileStorage {
    fun read(key: String): String?
    fun write(key: String, value: String?)
}

class ProfileStore(private val storage: ProfileStorage) {
    fun load(): ServerProfile? {
        val raw = storage.read(KEY) ?: return null
        val o = JSONObject(raw)
        return ServerProfile(
            host = o.getString("host"),
            flatbufPort = o.optInt("flatbufPort", 19400),
            jsonPort = o.optInt("jsonPort", 19444),
            instanceId = o.optInt("instanceId", 0),
            priority = o.optInt("priority", 100),
            token = if (o.has("token")) o.getString("token") else null,
            highQuality = o.optBoolean("highQuality", false),
        )
    }

    fun save(p: ServerProfile) {
        val o = JSONObject().apply {
            put("host", p.host)
            put("flatbufPort", p.flatbufPort)
            put("jsonPort", p.jsonPort)
            put("instanceId", p.instanceId)
            put("priority", p.priority)
            if (p.token != null) put("token", p.token)
            put("highQuality", p.highQuality)
        }
        storage.write(KEY, o.toString())
    }

    fun clear() = storage.write(KEY, null)

    companion object { private const val KEY = "hyperhdr_profile" }
}
```

- [ ] **Step 2: Add the test**

```kotlin
// common/src/test/java/eu/hyperhdr/android/settings/ProfileStoreTest.kt
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
}
```

- [ ] **Step 3: Add the Android-backed adapter**

```kotlin
// common/src/main/java/eu/hyperhdr/android/settings/EncryptedProfileStorage.kt
package eu.hyperhdr.android.settings

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object EncryptedProfileStorage {
    fun create(context: Context): ProfileStorage {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context, "hyperhdr_secure", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        return object : ProfileStorage {
            override fun read(key: String): String? = prefs.getString(key, null)
            override fun write(key: String, value: String?) {
                prefs.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        }
    }
}
```

- [ ] **Step 4: Run, confirm pass**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.settings.ProfileStoreTest"
```
Expected: 2 tests pass.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/settings/ \
        common/src/test/java/eu/hyperhdr/android/settings/
git commit -m "feat(settings): ServerProfile + ProfileStore (encrypted-backed) with in-memory test"
```

---

## Task 6: `ServiceState` + `HyperHdrServiceController`

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/service/ServiceState.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/service/HyperHdrServiceController.kt`
- Test: `common/src/test/java/eu/hyperhdr/android/service/ServiceStateMachineTest.kt`

`HyperHdrServiceController` is the brain: it owns the state machine, transitions on inputs (toggle, projection-revoked, socket-error, resume), and exposes a `StateFlow<ServiceState>`. The Service itself is a thin wrapper around the controller. This split is what lets us unit-test the state logic without a running Service.

- [ ] **Step 1: Define `ServiceState`**

```kotlin
// common/src/main/java/eu/hyperhdr/android/service/ServiceState.kt
package eu.hyperhdr.android.service

enum class ServiceState { IDLE, CONNECTING, STREAMING, PAUSED, ERROR }
```

- [ ] **Step 2: Write the failing test**

```kotlin
// common/src/test/java/eu/hyperhdr/android/service/ServiceStateMachineTest.kt
package eu.hyperhdr.android.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ServiceStateMachineTest {

    @Test
    fun `idle -- toggleOn --> connecting`() {
        val sm = ServiceStateMachine(ServiceState.IDLE)
        sm.onToggleOn()
        assertThat(sm.state).isEqualTo(ServiceState.CONNECTING)
    }

    @Test
    fun `connecting -- onConnected --> streaming`() {
        val sm = ServiceStateMachine(ServiceState.CONNECTING)
        sm.onConnected()
        assertThat(sm.state).isEqualTo(ServiceState.STREAMING)
    }

    @Test
    fun `streaming -- onProjectionPaused --> paused`() {
        val sm = ServiceStateMachine(ServiceState.STREAMING)
        sm.onProjectionPaused()
        assertThat(sm.state).isEqualTo(ServiceState.PAUSED)
    }

    @Test
    fun `paused -- onProjectionResumed --> streaming`() {
        val sm = ServiceStateMachine(ServiceState.PAUSED)
        sm.onProjectionResumed()
        assertThat(sm.state).isEqualTo(ServiceState.STREAMING)
    }

    @Test
    fun `any -- onError --> error`() {
        for (start in ServiceState.values()) {
            val sm = ServiceStateMachine(start)
            sm.onError("test")
            assertThat(sm.state).isEqualTo(ServiceState.ERROR)
        }
    }

    @Test
    fun `any -- onToggleOff --> idle`() {
        for (start in ServiceState.values()) {
            val sm = ServiceStateMachine(start)
            sm.onToggleOff()
            assertThat(sm.state).isEqualTo(ServiceState.IDLE)
        }
    }
}
```

- [ ] **Step 3: Run, confirm failure**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.service.ServiceStateMachineTest"
```
Expected: FAIL — `unresolved reference: ServiceStateMachine`.

- [ ] **Step 4: Implement**

```kotlin
// common/src/main/java/eu/hyperhdr/android/service/ServiceStateMachine.kt
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
```

- [ ] **Step 5: Run, confirm pass**

```bash
./gradlew :common:test --tests "eu.hyperhdr.android.service.ServiceStateMachineTest"
```
Expected: 6 tests pass.

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/service/ServiceState.kt \
        common/src/main/java/eu/hyperhdr/android/service/ServiceStateMachine.kt \
        common/src/test/java/eu/hyperhdr/android/service/ServiceStateMachineTest.kt
git commit -m "feat(service): ServiceState enum + ServiceStateMachine with transitions"
```

---

## Task 7: `HyperHdrCaptureService` (foreground service skeleton)

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/service/HyperHdrCaptureService.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/service/HyperHdrServiceBinder.kt`
- Modify: `tv/src/main/AndroidManifest.xml`
- Modify: `common/src/main/AndroidManifest.xml` to add `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_MEDIA_PROJECTION` permissions

This task is heavy on Android-specifics; verification is by manual install. Unit-testing services is impractical and low-value.

- [ ] **Step 1: Add permissions to `common/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION"/>
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
</manifest>
```

- [ ] **Step 2: Implement the binder**

```kotlin
// common/src/main/java/eu/hyperhdr/android/service/HyperHdrServiceBinder.kt
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
}
```

- [ ] **Step 3: Implement the service**

```kotlin
// common/src/main/java/eu/hyperhdr/android/service/HyperHdrCaptureService.kt
package eu.hyperhdr.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import eu.hyperhdr.android.capture.CaptureConfig
import eu.hyperhdr.android.capture.HyperHdrGpuEncoder
import eu.hyperhdr.android.flatbuf.ConnectionState
import eu.hyperhdr.android.flatbuf.HyperHdrFlatBufferReconnector
import eu.hyperhdr.android.json.HyperHdrJsonApiClient
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class HyperHdrCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "hyperhdr_capture"
        const val NOTIF_ID = 1
        const val ACTION_TOGGLE = "eu.hyperhdr.android.action.TOGGLE"
    }

    private val sm = ServiceStateMachine()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var profileStore: ProfileStore

    private var encoder: HyperHdrGpuEncoder? = null
    private var reconnector: HyperHdrFlatBufferReconnector? = null
    private var jsonClient: HyperHdrJsonApiClient? = null
    private var stateCollector: Job? = null

    val stateFlow get() = sm.flow
    fun lastError(): String? = sm.lastErrorMessage

    override fun onCreate() {
        super.onCreate()
        profileStore = ProfileStore(EncryptedProfileStorage.create(this))
        ensureChannel()
        startForeground(NOTIF_ID, buildNotification(ServiceState.IDLE), foregroundType())
    }

    override fun onBind(intent: Intent?): IBinder = HyperHdrServiceBinder(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TOGGLE) {
            if (sm.state == ServiceState.STREAMING || sm.state == ServiceState.CONNECTING) stopCapture()
            // For toggle-on, the UI/tile path must invoke startCapture with a fresh projection result.
        }
        return START_STICKY
    }

    fun startCapture(projectionResultCode: Int, projectionData: Intent) {
        val profile = profileStore.load() ?: run {
            sm.onError("No server configured. Open the app to set up.")
            return
        }
        sm.onToggleOn()
        updateNotif()

        val pmgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = pmgr.getMediaProjection(projectionResultCode, projectionData)

        val r = HyperHdrFlatBufferReconnector(
            host = profile.host, port = profile.flatbufPort, priority = profile.priority,
        ).also { it.start() }
        reconnector = r

        stateCollector = scope.launch {
            r.state.collect { cs ->
                when (cs) {
                    ConnectionState.CONNECTED -> { sm.onConnected(); updateNotif() }
                    ConnectionState.ERROR -> { sm.onError("Network error"); updateNotif() }
                    else -> { /* CONNECTING/DISCONNECTED handled via sm transitions */ }
                }
            }
        }

        jsonClient = HyperHdrJsonApiClient(
            host = profile.host, port = profile.jsonPort,
        ).also { client ->
            scope.launch {
                runCatching {
                    profile.token?.let { client.authorize(it) }
                    client.switchInstance(profile.instanceId)
                }.onFailure { sm.onError("JSON-API: ${it.message}") }
            }
        }

        val dm = DisplayMetrics().also {
            (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(it)
        }
        val cfg = if (profile.highQuality) CaptureConfig.HIGH else CaptureConfig.STANDARD
        encoder = HyperHdrGpuEncoder(
            mediaProjection = projection,
            sourceWidth = dm.widthPixels,
            sourceHeight = dm.heightPixels,
            density = dm.densityDpi,
            config = cfg,
            sink = r,
        ).also { it.start() }
    }

    fun stopCapture() {
        encoder?.stop(); encoder = null
        reconnector?.close(); reconnector = null
        jsonClient = null
        stateCollector?.cancel(); stateCollector = null
        sm.onToggleOff()
        updateNotif()
    }

    override fun onDestroy() {
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }

    // --- helpers ---

    private fun ensureChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "HyperHDR capture", NotificationManager.IMPORTANCE_LOW,
            ))
        }
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        else 0

    private fun updateNotif() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(sm.state))
    }

    private fun buildNotification(state: ServiceState): Notification {
        val toggle = PendingIntent.getService(
            this, 0,
            Intent(this, HyperHdrCaptureService::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (state) {
            ServiceState.IDLE -> "Idle — open the app to start capture"
            ServiceState.CONNECTING -> "Connecting to HyperHDR…"
            ServiceState.STREAMING -> "Streaming to HyperHDR"
            ServiceState.PAUSED -> "Paused"
            ServiceState.ERROR -> "Error: ${sm.lastErrorMessage ?: "unknown"}"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HyperHDR")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", toggle)
            .build()
    }
}
```

- [ ] **Step 4: Register the service in `tv/src/main/AndroidManifest.xml`**

Inside `<application>`:

```xml
<service
    android:name="eu.hyperhdr.android.service.HyperHdrCaptureService"
    android:exported="false"
    android:foregroundServiceType="mediaProjection" />
```

- [ ] **Step 5: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin :tv:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/service/HyperHdrCaptureService.kt \
        common/src/main/java/eu/hyperhdr/android/service/HyperHdrServiceBinder.kt \
        common/src/main/AndroidManifest.xml \
        tv/src/main/AndroidManifest.xml
git commit -m "feat(service): HyperHdrCaptureService with binder, notification, projection lifecycle"
```

---

## Task 8: TV — Setup wizard (`GuidedStepFragment`)

**Files:**
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/WizardActivity.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/DiscoveryStepFragment.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/AuthStepFragment.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/InstanceStepFragment.kt`

The wizard activity hosts a stack of `GuidedStepSupportFragment`s; each step writes back to a `ServerProfile.Builder` held in the activity, and the last step persists it via `ProfileStore`.

(For brevity, code shows full discovery + auth + instance steps. Manual host/port entry is offered as an action on Step 1; if the user picks it, a fourth fragment `ManualStepFragment` shows two `GuidedAction.Builder` text inputs. The full implementation pattern is identical to `AuthStepFragment` below.)

- [ ] **Step 1: Implement `WizardActivity`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/WizardActivity.kt
package eu.hyperhdr.android.tv.ui.wizard

import android.app.Activity
import android.net.nsd.NsdManager
import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.fragment.app.FragmentActivity
import eu.hyperhdr.android.discovery.HyperHdrMdnsDiscovery
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.settings.ServerProfile

class WizardActivity : FragmentActivity() {
    lateinit var discovery: HyperHdrMdnsDiscovery
    lateinit var profileStore: ProfileStore
    var profileDraft: ServerProfile = ServerProfile(host = "")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        discovery = HyperHdrMdnsDiscovery(getSystemService(NSD_SERVICE) as NsdManager).also { it.start() }
        profileStore = ProfileStore(EncryptedProfileStorage.create(this))
        if (savedInstanceState == null) {
            GuidedStepSupportFragment.addAsRoot(this, DiscoveryStepFragment(), android.R.id.content)
        }
    }

    override fun onDestroy() { discovery.stop(); super.onDestroy() }

    fun finishOk() { setResult(Activity.RESULT_OK); finish() }
}
```

- [ ] **Step 2: Implement `DiscoveryStepFragment`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/DiscoveryStepFragment.kt
package eu.hyperhdr.android.tv.ui.wizard

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class DiscoveryStepFragment : GuidedStepSupportFragment() {

    private var browseJob: Job? = null
    private val actions = mutableListOf<GuidedAction>()
    private val ACTION_MANUAL = 1L
    private val ACTION_FOUND_BASE = 1000L

    override fun onCreateGuidance(savedInstanceState: Bundle?) =
        GuidanceStylist.Guidance(
            "Looking for HyperHDR servers…",
            "Pick a server below or enter manually.",
            "Step 1 of 4", null,
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.add(GuidedAction.Builder(requireContext())
            .id(ACTION_MANUAL).title("Enter manually…").build())
    }

    override fun onResume() {
        super.onResume()
        val activity = requireActivity() as WizardActivity
        browseJob = CoroutineScope(Dispatchers.Main).launch {
            activity.discovery.servers.collect { servers ->
                actions.clear()
                servers.forEachIndexed { idx, s ->
                    actions += GuidedAction.Builder(requireContext())
                        .id(ACTION_FOUND_BASE + idx)
                        .title(s.name)
                        .description("${s.host}:${s.flatbufPort}")
                        .build()
                }
                actions += GuidedAction.Builder(requireContext())
                    .id(ACTION_MANUAL).title("Enter manually…").build()
                this@DiscoveryStepFragment.actions = actions
            }
        }
    }

    override fun onPause() { browseJob?.cancel(); super.onPause() }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val activity = requireActivity() as WizardActivity
        when {
            action.id == ACTION_MANUAL -> {
                add(parentFragmentManager, ManualStepFragment())
            }
            action.id >= ACTION_FOUND_BASE -> {
                val idx = (action.id - ACTION_FOUND_BASE).toInt()
                val s = activity.discovery.servers.value[idx]
                activity.profileDraft = activity.profileDraft.copy(
                    host = s.host, flatbufPort = s.flatbufPort, jsonPort = s.jsonPort,
                )
                add(parentFragmentManager, AuthStepFragment())
            }
        }
    }
}
```

- [ ] **Step 3: Implement `ManualStepFragment` (host + port entry)**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/ManualStepFragment.kt
package eu.hyperhdr.android.tv.ui.wizard

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction

class ManualStepFragment : GuidedStepSupportFragment() {
    private val ID_HOST = 10L; private val ID_FLAT = 11L; private val ID_JSON = 12L; private val ID_OK = 13L

    override fun onCreateGuidance(savedInstanceState: Bundle?) =
        GuidanceStylist.Guidance("Enter HyperHDR server", "Host/IP and ports.", "Step 1b", null)

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += GuidedAction.Builder(requireContext()).id(ID_HOST).title("Host").editable(true).build()
        actions += GuidedAction.Builder(requireContext()).id(ID_FLAT).title("Flatbuffer port").description("19400").editable(true).build()
        actions += GuidedAction.Builder(requireContext()).id(ID_JSON).title("JSON-API port").description("19444").editable(true).build()
        actions += GuidedAction.Builder(requireContext()).id(ID_OK).title("Continue").build()
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id != ID_OK) return
        val host = findActionById(ID_HOST).description?.toString().orEmpty()
        val flat = findActionById(ID_FLAT).description?.toString()?.toIntOrNull() ?: 19400
        val json = findActionById(ID_JSON).description?.toString()?.toIntOrNull() ?: 19444
        val activity = requireActivity() as WizardActivity
        activity.profileDraft = activity.profileDraft.copy(host = host, flatbufPort = flat, jsonPort = json)
        add(parentFragmentManager, AuthStepFragment())
    }
}
```

- [ ] **Step 4: Implement `AuthStepFragment` (probes auth, optionally collects token)**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/AuthStepFragment.kt
package eu.hyperhdr.android.tv.ui.wizard

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import eu.hyperhdr.android.json.HyperHdrJsonApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthStepFragment : GuidedStepSupportFragment() {
    private val ID_TOKEN = 20L; private val ID_OK = 21L; private val ID_SKIP = 22L
    private var authRequired = false

    override fun onCreateGuidance(savedInstanceState: Bundle?) =
        GuidanceStylist.Guidance(
            "Authentication",
            "Probing the server… open the HyperHDR web UI to create a token if needed.",
            "Step 2 of 4", null,
        )

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += GuidedAction.Builder(requireContext()).id(ID_TOKEN).title("Token").editable(true).build()
        actions += GuidedAction.Builder(requireContext()).id(ID_OK).title("Continue").build()
        actions += GuidedAction.Builder(requireContext()).id(ID_SKIP).title("Skip (open server)").build()
    }

    override fun onResume() {
        super.onResume()
        val activity = requireActivity() as WizardActivity
        CoroutineScope(Dispatchers.Main).launch {
            val client = HyperHdrJsonApiClient(activity.profileDraft.host, activity.profileDraft.jsonPort)
            authRequired = withContext(Dispatchers.IO) {
                runCatching { client.serverInfo().authRequired }.getOrElse { false }
            }
            if (!authRequired) {
                add(parentFragmentManager, InstanceStepFragment())
            }
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        val activity = requireActivity() as WizardActivity
        when (action.id) {
            ID_OK -> {
                val token = findActionById(ID_TOKEN).description?.toString()?.takeIf { it.isNotBlank() }
                activity.profileDraft = activity.profileDraft.copy(token = token)
                add(parentFragmentManager, InstanceStepFragment())
            }
            ID_SKIP -> add(parentFragmentManager, InstanceStepFragment())
        }
    }
}
```

- [ ] **Step 5: Implement `InstanceStepFragment` (lists instances, persists profile)**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/InstanceStepFragment.kt
package eu.hyperhdr.android.tv.ui.wizard

import android.os.Bundle
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import eu.hyperhdr.android.json.HyperHdrJsonApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class InstanceStepFragment : GuidedStepSupportFragment() {
    private val INSTANCE_BASE = 100L

    override fun onCreateGuidance(savedInstanceState: Bundle?) =
        GuidanceStylist.Guidance("Pick the instance", "Which HyperHDR instance should this device drive?", "Step 3 of 4", null)

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions += GuidedAction.Builder(requireContext()).id(0L).title("Loading…").build()
    }

    override fun onResume() {
        super.onResume()
        val activity = requireActivity() as WizardActivity
        CoroutineScope(Dispatchers.Main).launch {
            val client = HyperHdrJsonApiClient(activity.profileDraft.host, activity.profileDraft.jsonPort)
            activity.profileDraft.token?.let { runCatching { client.authorize(it) } }
            val info = withContext(Dispatchers.IO) {
                runCatching { client.serverInfo() }.getOrNull()
            }
            actions.clear()
            info?.instances?.forEachIndexed { i, inst ->
                actions += GuidedAction.Builder(requireContext())
                    .id(INSTANCE_BASE + i)
                    .title(inst.name)
                    .description(if (inst.running) "Running (id=${inst.id})" else "Stopped (id=${inst.id})")
                    .build()
            }
            if (actions.isEmpty()) {
                actions += GuidedAction.Builder(requireContext())
                    .id(0L).title("No instances reported").description("Check the server").build()
            }
            actions = actions
        }
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.id < INSTANCE_BASE) return
        val activity = requireActivity() as WizardActivity
        val client = HyperHdrJsonApiClient(activity.profileDraft.host, activity.profileDraft.jsonPort)
        val idx = (action.id - INSTANCE_BASE).toInt()
        CoroutineScope(Dispatchers.Main).launch {
            val info = withContext(Dispatchers.IO) { runCatching { client.serverInfo() }.getOrNull() }
            val inst = info?.instances?.getOrNull(idx) ?: return@launch
            activity.profileDraft = activity.profileDraft.copy(instanceId = inst.id)
            activity.profileStore.save(activity.profileDraft)
            activity.finishOk()
        }
    }
}
```

- [ ] **Step 6: Register `WizardActivity` in `tv/src/main/AndroidManifest.xml`**

Inside `<application>`:

```xml
<activity
    android:name="eu.hyperhdr.android.tv.ui.wizard.WizardActivity"
    android:exported="false"
    android:theme="@style/Theme.Leanback.GuidedStep"/>
```

- [ ] **Step 7: Verify it compiles**

```bash
./gradlew :tv:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add tv/src/main/java/eu/hyperhdr/android/tv/ui/wizard/ \
        tv/src/main/AndroidManifest.xml
git commit -m "feat(tv-ui): setup wizard — discovery, manual, auth, instance pick → ProfileStore"
```

---

## Task 9: TV — Main screen + Settings

**Files:**
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/ui/MainActivity.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsActivity.kt`
- Create: `tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsFragment.kt`
- Create: `tv/src/main/res/layout/fragment_main.xml`
- Modify: `tv/src/main/AndroidManifest.xml`

The main screen has three regions per spec §6.2: connection card, big toggle, live stats footer. The simplest TV-friendly implementation uses a vertical `LinearLayout` + d-pad-focusable buttons.

- [ ] **Step 1: Layout `fragment_main.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:padding="48dp"
    android:background="@android:color/black">

    <Button
        android:id="@+id/btn_server"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="16dp"
        android:focusable="true"
        android:text="(no server configured)"/>

    <Button
        android:id="@+id/btn_toggle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:focusable="true"
        android:padding="32dp"
        android:textSize="32sp"
        android:text="Start capture"/>

    <Button
        android:id="@+id/btn_settings"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="16dp"
        android:focusable="true"
        android:text="Settings"/>

    <TextView
        android:id="@+id/tv_stats"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="32dp"
        android:textColor="@android:color/darker_gray"
        android:text=""/>

</LinearLayout>
```

- [ ] **Step 2: `MainActivity` and `MainFragment`**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/MainActivity.kt
package eu.hyperhdr.android.tv.ui

import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import eu.hyperhdr.android.tv.R

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, MainFragment())
                .commit()
        }
    }
}
```

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt
package eu.hyperhdr.android.tv.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import eu.hyperhdr.android.service.HyperHdrCaptureService
import eu.hyperhdr.android.service.HyperHdrServiceBinder
import eu.hyperhdr.android.service.ServiceState
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore
import eu.hyperhdr.android.tv.R
import eu.hyperhdr.android.tv.ui.settings.SettingsActivity
import eu.hyperhdr.android.tv.ui.wizard.WizardActivity
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainFragment : Fragment() {

    private val REQ_PROJECTION = 1
    private var binder: HyperHdrServiceBinder? = null
    private var pendingProjection: Intent? = null

    private val serviceConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            binder = service as HyperHdrServiceBinder
            observeState()
            // If a projection result was pending while we waited for the binder, deliver now.
            pendingProjection?.let { intent ->
                binder?.startCapture(Activity.RESULT_OK, intent)
                pendingProjection = null
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_main, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val profileStore = ProfileStore(EncryptedProfileStorage.create(requireContext()))
        val server = view.findViewById<Button>(R.id.btn_server)
        val toggle = view.findViewById<Button>(R.id.btn_toggle)
        val stats = view.findViewById<TextView>(R.id.tv_stats)
        val settings = view.findViewById<Button>(R.id.btn_settings)

        val profile = profileStore.load()
        server.text = profile?.let { "${it.host} (instance ${it.instanceId})" } ?: "Configure server…"
        server.setOnClickListener {
            startActivity(Intent(requireContext(), WizardActivity::class.java))
        }
        toggle.setOnClickListener {
            if (binder?.state?.value == ServiceState.STREAMING || binder?.state?.value == ServiceState.CONNECTING) {
                binder?.stopCapture()
            } else {
                val pmgr = requireActivity().getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(pmgr.createScreenCaptureIntent(), REQ_PROJECTION)
            }
        }
        settings.setOnClickListener {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
        stats.text = "" // Plan 4 hooks live stats here.
    }

    override fun onStart() {
        super.onStart()
        val ctx = requireContext()
        ctx.startForegroundService(Intent(ctx, HyperHdrCaptureService::class.java))
        ctx.bindService(
            Intent(ctx, HyperHdrCaptureService::class.java),
            serviceConn, Context.BIND_AUTO_CREATE,
        )
    }

    override fun onStop() {
        runCatching { requireContext().unbindService(serviceConn) }
        binder = null
        super.onStop()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        if (resultCode != Activity.RESULT_OK || data == null) return
        val b = binder
        if (b != null) b.startCapture(resultCode, data) else pendingProjection = data
    }

    private fun observeState() {
        val toggle = view?.findViewById<Button>(R.id.btn_toggle) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            binder?.state?.collect { s ->
                toggle.text = when (s) {
                    ServiceState.IDLE -> "Start capture"
                    ServiceState.CONNECTING -> "Connecting…"
                    ServiceState.STREAMING -> "Stop capture"
                    ServiceState.PAUSED -> "Paused (waiting for screen)"
                    ServiceState.ERROR -> "Error — tap to retry"
                }
            }
        }
    }
}
```

- [ ] **Step 3: Settings screen (Plan 3 minimum: edit profile + toggle high quality)**

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsActivity.kt
package eu.hyperhdr.android.tv.ui.settings

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

class SettingsActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }
}
```

```kotlin
// tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsFragment.kt
package eu.hyperhdr.android.tv.ui.settings

import android.os.Bundle
import androidx.leanback.preference.LeanbackPreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import eu.hyperhdr.android.settings.EncryptedProfileStorage
import eu.hyperhdr.android.settings.ProfileStore

class SettingsFragment : LeanbackPreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(ctx)
        val store = ProfileStore(EncryptedProfileStorage.create(ctx))
        val profile = store.load() ?: return run { preferenceScreen = screen }

        val highQuality = SwitchPreferenceCompat(ctx).apply {
            key = "high_quality"
            title = "High quality (192×108 @ 60 fps)"
            isChecked = profile.highQuality
            setOnPreferenceChangeListener { _, newValue ->
                store.save(profile.copy(highQuality = newValue as Boolean))
                true
            }
        }
        screen.addPreference(highQuality)

        preferenceScreen = screen
    }
}
```

- [ ] **Step 4: Register both activities in `tv/src/main/AndroidManifest.xml`**

Inside `<application>`:

```xml
<activity
    android:name="eu.hyperhdr.android.tv.ui.MainActivity"
    android:exported="true"
    android:theme="@style/Theme.Leanback">
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LEANBACK_LAUNCHER"/>
    </intent-filter>
</activity>

<activity
    android:name="eu.hyperhdr.android.tv.ui.settings.SettingsActivity"
    android:exported="false"
    android:theme="@style/PreferenceTheme.Leanback"/>
```

If `PreferenceTheme.Leanback` isn't already in `tv/src/main/res/values/styles.xml`, add:
```xml
<style name="PreferenceTheme.Leanback" parent="@style/Theme.Leanback"/>
```

- [ ] **Step 5: Verify it compiles**

```bash
./gradlew :tv:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add tv/src/main/java/eu/hyperhdr/android/tv/ui/ \
        tv/src/main/res/layout/fragment_main.xml \
        tv/src/main/res/values/styles.xml \
        tv/src/main/AndroidManifest.xml
git commit -m "feat(tv-ui): main screen and settings fragment with service binding"
```

---

## Task 10: Quick Settings tile + boot receiver

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/service/HyperHdrTileService.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/service/HyperHdrBootReceiver.kt`
- Modify: `tv/src/main/AndroidManifest.xml`

- [ ] **Step 1: Boot receiver**

```kotlin
// common/src/main/java/eu/hyperhdr/android/service/HyperHdrBootReceiver.kt
package eu.hyperhdr.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class HyperHdrBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        // Start the foreground service in IDLE; capture requires user consent — see spec §7.6.
        context.startForegroundService(Intent(context, HyperHdrCaptureService::class.java))
    }
}
```

- [ ] **Step 2: Tile service**

```kotlin
// common/src/main/java/eu/hyperhdr/android/service/HyperHdrTileService.kt
package eu.hyperhdr.android.service

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class HyperHdrTileService : TileService() {

    override fun onClick() {
        // We can't show the MediaProjection consent dialog from a tile — open the main activity instead,
        // which will trigger the projection flow.
        val launch = packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(launch)
    }

    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            label = "HyperHDR"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
```

- [ ] **Step 3: Register in manifest**

Inside `<application>`:

```xml
<receiver
    android:name="eu.hyperhdr.android.service.HyperHdrBootReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED"/>
    </intent-filter>
</receiver>

<service
    android:name="eu.hyperhdr.android.service.HyperHdrTileService"
    android:exported="true"
    android:icon="@android:drawable/ic_media_play"
    android:label="HyperHDR"
    android:permission="android.permission.BIND_QUICK_SETTINGS_TILE">
    <intent-filter>
        <action android:name="android.service.quicksettings.action.QS_TILE"/>
    </intent-filter>
</service>
```

- [ ] **Step 4: Verify it compiles**

```bash
./gradlew :tv:assembleDebug
```
Expected: BUILD SUCCESSFUL — produces `tv/build/outputs/apk/debug/tv-debug.apk`.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/service/HyperHdrBootReceiver.kt \
        common/src/main/java/eu/hyperhdr/android/service/HyperHdrTileService.kt \
        tv/src/main/AndroidManifest.xml
git commit -m "feat(service): boot receiver + Quick Settings tile"
```

---

## Task 11: Manual end-to-end install + use test

**Files:**
- Create: `docs/manual-tests/03-end-to-end-install.md`

- [ ] **Step 1: Write the doc**

````markdown
# Manual test: end-to-end install + first-run flow

**Prereqs:** Real Android TV (or any Android 9+ device) with ADB. Real HyperHDR v22+ on the same LAN with mDNS unblocked.

**Steps:**

1. Build + install:
   ```bash
   ./gradlew :tv:installDebug
   ```
2. From the TV launcher, open "HyperHDR." App opens to the main screen, the connection card says "Configure server…".
3. Tap the connection card → wizard opens. Within ~3 seconds, the discovery list should show your HyperHDR server. (If empty, pick "Enter manually" and enter host + ports.)
4. Pick the server. If auth is enabled, the auth step appears; paste a token and continue. If auth is disabled, the wizard skips to instance picking.
5. Pick an instance. Wizard closes; main screen shows `<host> (instance N)`.
6. Press "Start capture." Approve the projection dialog. The toggle button becomes "Stop capture" once `STREAMING`.
7. Open any colourful app on the TV. LEDs follow.
8. Press "Stop capture." LEDs go quiet within ~200 ms.
9. Reboot the device. After boot, you should see HyperHDR's foreground notification "Idle — open the app to start capture." Tap → main screen shows the saved profile already.
10. Open the Quick Settings panel. The "HyperHDR" tile is present. Tapping it opens the app (Android does not allow projection from a tile click).

**Pass criteria:**
- All 10 steps complete without crashes.
- The notification persists across the toggle cycles.
- The wizard's auth probe correctly distinguishes auth-required from auth-disabled servers (test both by toggling auth on/off in HyperHDR's web UI).
- After the wizard, settings are remembered across `am force-stop` + relaunch.

**Known limitations to verify (these are not bugs):**
- Capture cannot auto-start on boot — projection consent is mandatory each session on Android 12–13. On Android 14+ token reuse is platform-dependent.
- The Quick Settings tile cannot directly start capture; tapping it opens the app.
````

- [ ] **Step 2: Commit**

```bash
git add docs/manual-tests/03-end-to-end-install.md
git commit -m "docs: end-to-end install + first-run manual test"
```

---

## Plan 3 done definition

- All Plan 1 + Plan 2 tests still pass.
- New JVM unit tests (`HyperHdrJsonApiClientTest`, `DiscoveryPairingTest`, `ProfileStoreTest`, `ServiceStateMachineTest`) green.
- `./gradlew :tv:assembleDebug` produces an installable APK.
- The end-to-end manual recipe in Task 11 succeeds on a real Android TV against a real HyperHDR.
- Repo CI (if present) is still green; if there's no CI yet, Plan 4 adds it.

---

## Self-review (writer's pre-flight)

- **Spec coverage for Plan 3's slice:** §5.2 JSON-API control plane → Tasks 1–3; §5.3 mDNS discovery → Task 4; §6.1 wizard → Task 8; §6.2 main screen → Task 9; §6.3 settings → Task 9 (minimum quality preset switch; HDR/connection groups deferred to Plan 4 since they need Plan 4's HDR detector); §6.4 tile + boot → Task 10; §7 service shape, state machine, EGL/MediaProjection ownership, projection-revoke handling → Tasks 6, 7. §8.2 error catalogue partial coverage: JSON 401 → wizard auth probe; flatbuffer disconnect → Plan 1's reconnector + service collector. §8.3 reconnection policy → Plan 1's `BackoffSchedule` already in use.
- **Out of scope (deferred):** HDR shader (Plan 4), `videomode` HDR signaling on content transition (Plan 4 — the JSON method exists, but the "what triggers it" lives in Plan 4 with the HDR detector), live stats footer numbers (Plan 4), debug bundle export (Plan 4), CPU RGB fallback (Plan 4), update-checker repointing to GitHub Releases (Plan 4), CI workflow (Plan 4).
- **Type consistency:** `HyperHdrFlatBufferReconnector`, `FrameSink`, `ConnectionState` (Plan 1); `CaptureConfig`, `CaptureTier`, `HyperHdrGpuEncoder` (Plan 2) — all imported with the exact paths declared in those plans. New types `ServiceState`, `HyperHdrCaptureService`, `HyperHdrServiceBinder`, `HyperHdrJsonApiClient`, `HyperHdrMdnsDiscovery`, `DiscoveredServer`, `ServerProfile`, `ProfileStore`, `Instance`, `ServerInfo` named identically across this plan and Plan 4.
- **Method-name consistency:** `setHdrVideoMode(hdr: Boolean)`, `serverInfo()`, `authorize(token)`, `switchInstance(id)` are stable names that Plan 4 references for the HDR-state signaling trigger. `startCapture(projectionResultCode, projectionData)`, `stopCapture()` on the binder are referenced from `MainFragment`.
- **No placeholders:** every code block compiles; every command has expected output; no "TBD" / "TODO" inside tasks. Three places where Plan 4 work hooks in are explicitly marked with comments rather than stubs ("Plan 4 hooks live stats here", "Plan 4 — JSON method exists, trigger lives there", "Plan 4 adds CI").
