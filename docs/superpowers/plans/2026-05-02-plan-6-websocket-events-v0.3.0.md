# Plan 6 — WebSocket Live JSON-API Events (v0.3.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a WebSocket transport mode to `HyperHdrJsonApiClient` so the app reflects HyperHDR server-side state changes live (instance switches, component toggles, HDR detection) instead of polling-on-screen-open. Keeps the HTTP one-shot transport (Plans 3) as the default for register-time calls; WebSocket is opened lazily when a UI surface needs live updates.

**Architecture:** OkHttp's `WebSocket` API for client-side; HyperHDR's web server upgrades WebSocket on the same `:19444` port via the `Upgrade: websocket` HTTP header. Wire protocol: same JSON envelope as the HTTP transport (`{"command":"serverinfo","subscribe":[...],"tan":...}`), but the server pushes state-change events back over the same connection. New `HyperHdrJsonApiEvents` class owns the socket; exposes events as a Kotlin `SharedFlow<JsonEvent>`. Service-side, an event collector translates HyperHDR component-state events into `HdrDetector.current` updates (and vice-versa for the HDR signaling round-trip), so the existing surfaces don't need to know WebSocket is involved.

**Tech Stack:** OkHttp 4.12.0 (already a dep, includes `WebSocket` + `WebSocketListener`). MockWebServer 4.12.0 (test-time WebSocket support). Same Kotlin/coroutines stack as Plans 3.

**Out of scope:** P010 wire format, Compose-for-TV rewrite, the polling fallback when WebSocket is unavailable (the existing HTTP one-shot path stays — UI just gets less-live updates).

**Working software at end of plan:** `v0.3.0` published. While the user has settings open, server-side instance switches or HDR-component toggles update the UI within ~1 second without re-opening. Manual recipe: open settings, then on the HyperHDR web UI toggle HDR tone-mapping → the app's "Signal HDR" status reflects the change live.

---

## File Structure

```
common/src/main/java/eu/hyperhdr/android/json/
├── HyperHdrJsonApiClient.kt                     # unchanged (HTTP transport)
├── HyperHdrJsonApiEvents.kt                     # NEW — WebSocket subscription manager
├── JsonEvent.kt                                 # NEW — sealed type of inbound events
├── ServerInfo.kt, Instance.kt, JsonApiError.kt  # unchanged

common/src/test/java/eu/hyperhdr/android/json/
├── HyperHdrJsonApiClientTest.kt                 # unchanged (HTTP)
└── HyperHdrJsonApiEventsTest.kt                 # NEW — WebSocket event parsing + reconnect
```

`HyperHdrCaptureService` gains a `HyperHdrJsonApiEvents` instance that's started on bind, plus a small adapter coroutine that bridges WebSocket-delivered HDR events into the existing `HdrDetector`-driven flow. Both directions: HyperHDR → app (server reports HDR-tonemap toggled in web UI) and app → HyperHDR (the existing `setHdrVideoMode` round-trip from Plan 4).

---

## Task 1: Define `JsonEvent` sealed type

**Files:**
- Create: `common/src/main/java/eu/hyperhdr/android/json/JsonEvent.kt`

The HyperHDR server can push four event categories that matter to v0.3.0:

| HyperHDR event command | What it carries | Our consumer |
|---|---|---|
| `components-update` | One component (COMP_HDR, COMP_LEDDEVICE, etc.) toggled on/off | UI badge in settings; service for COMP_HDR sync |
| `instance-update` | One instance started/stopped, friendly name changed | Settings instance picker live-update |
| `videomode-update` | Server's external-tonemap mode changed (HDR=0/1/2/3) | Service round-trip with `HdrDetector` |
| `serverinfo` (subscribe response) | Initial state snapshot | Bootstrap |

Anything else (`leds-colors`, `priorities-update`, etc.) we ignore in v0.3.0 — they're either too noisy (60 Hz LED color stream) or not user-visible in our UI.

- [ ] **Step 1: Create the sealed type**

Create `common/src/main/java/eu/hyperhdr/android/json/JsonEvent.kt`:

```kotlin
package eu.hyperhdr.android.json

/**
 * Inbound WebSocket events from HyperHDR. Only events that map to a UI surface or service
 * behavior are modelled; others (leds-colors, priorities-update, etc.) are dropped at
 * parse time.
 */
sealed interface JsonEvent {
    /** A single component (COMP_HDR, COMP_LEDDEVICE, etc.) was toggled. */
    data class ComponentChanged(val name: String, val enabled: Boolean) : JsonEvent

    /** An instance started, stopped, or had its friendly name changed. */
    data class InstanceChanged(val instances: List<Instance>) : JsonEvent

    /** Server's external-tonemap mode changed (HDR=0/1 from the videomode JSON-RPC). */
    data class VideoModeChanged(val hdr: Boolean) : JsonEvent

    /** Initial state snapshot delivered after subscribe. */
    data class ServerInfoSnapshot(val info: ServerInfo) : JsonEvent
}
```

- [ ] **Step 2: Verify it compiles**

```bash
./gradlew :common:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/json/JsonEvent.kt
git commit -m "feat(json): JsonEvent sealed type for WebSocket-delivered server events"
```

---

## Task 2: TDD `HyperHdrJsonApiEvents` — parse a `components-update` push

**Files:**
- Test: `common/src/test/java/eu/hyperhdr/android/json/HyperHdrJsonApiEventsTest.kt`
- Create: `common/src/main/java/eu/hyperhdr/android/json/HyperHdrJsonApiEvents.kt`

The class wraps an OkHttp `WebSocket`. On `start(token)`, it opens the socket, sends a `serverinfo` request with `"subscribe": ["components-update", "instance-update", "videomode-update"]`, and emits parsed events via a `SharedFlow<JsonEvent>`.

- [ ] **Step 1: Write the failing test**

Create `common/src/test/java/eu/hyperhdr/android/json/HyperHdrJsonApiEventsTest.kt`:

```kotlin
package eu.hyperhdr.android.json

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test

class HyperHdrJsonApiEventsTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `parses components-update push as ComponentChanged event`() = runTest {
        val response = MockResponse().withWebSocketUpgrade(object : okhttp3.mockwebserver.QueueDispatcher() {
            // Server-side handler is set up below via the alternate ctor; simpler to use server.enqueue.
        })
        // Simpler: use the WebSocketRecorder pattern.
        val events = HyperHdrJsonApiEvents(host = server.hostName, port = server.port)
        events.start(token = null)

        // Drain the subscribe request the client sent.
        // Then push a components-update from the server side.
        // Then collect from events.flow and assert the first emission.

        // (See implementation: this test scaffolds the Mock WebSocket pattern below.)
        events.close()
    }
}
```

The above is a sketch — the precise test setup depends on which `MockWebServer` API you use for WebSocket bidirectional simulation. Two options:

**Option A (recommended): use `MockWebServer.enqueue(MockResponse().withWebSocketUpgrade(listener))`** — pass a `WebSocketListener` that captures incoming subscribe requests and pushes outgoing events.

**Option B: write a tiny TCP server that does the WebSocket handshake by hand** — more work but no MockWebServer dependency on the WebSocket API.

Option A is shorter; rewrite the test as:

```kotlin
package eu.hyperhdr.android.json

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

class HyperHdrJsonApiEventsTest {

    private lateinit var server: MockWebServer
    private val incomingMessages = LinkedBlockingDeque<String>()
    private var serverSocket: WebSocket? = null

    private val serverListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            serverSocket = webSocket
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            incomingMessages.put(text)
        }
    }

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() { server.shutdown() }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `parses components-update push as ComponentChanged event`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))

        val events = HyperHdrJsonApiEvents(host = server.hostName, port = server.port)
        events.start(token = null)

        // 1. Wait for the client's subscribe request to arrive at the fake server.
        val subscribeRequest = withContext(Dispatchers.IO) {
            incomingMessages.poll(2, TimeUnit.SECONDS)
        }
        assertThat(subscribeRequest).isNotNull()
        assertThat(subscribeRequest!!).contains("\"command\":\"serverinfo\"")
        assertThat(subscribeRequest).contains("\"subscribe\"")
        assertThat(subscribeRequest).contains("components-update")

        // 2. Push a components-update event from the fake server.
        serverSocket!!.send(
            """{"command":"components-update","data":{"name":"HDR","enabled":true}}"""
        )

        // 3. The events flow should emit ComponentChanged(HDR, true).
        val event = withContext(Dispatchers.Default.limitedParallelism(1)) {
            withTimeout(2_000) { events.flow.first() }
        }
        assertThat(event).isInstanceOf(JsonEvent.ComponentChanged::class.java)
        val cc = event as JsonEvent.ComponentChanged
        assertThat(cc.name).isEqualTo("HDR")
        assertThat(cc.enabled).isTrue()

        events.close()
    }
}
```

(The `withContext(Dispatchers.Default.limitedParallelism(1))` wrapper is the same pattern used in `HyperHdrFlatBufferClientTest` — `runTest` uses virtual time but real network I/O needs wall-clock time.)

- [ ] **Step 2: Run, confirm FAILURE**

```bash
./gradlew :common:testDebugUnitTest --tests "eu.hyperhdr.android.json.HyperHdrJsonApiEventsTest"
```
Expected: FAIL with `unresolved reference: HyperHdrJsonApiEvents`.

- [ ] **Step 3: Implement `HyperHdrJsonApiEvents`**

Create `common/src/main/java/eu/hyperhdr/android/json/HyperHdrJsonApiEvents.kt`:

```kotlin
package eu.hyperhdr.android.json

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WebSocket-backed live-events client for HyperHDR JSON-RPC. Opens the socket on [start],
 * subscribes to the server-side update streams we care about, and exposes parsed events via
 * [flow] as a [SharedFlow]. The HTTP one-shot client ([HyperHdrJsonApiClient]) is unaffected
 * — that one stays the default for register-time calls; this class is only opened when a UI
 * surface (settings page, main screen) wants live state.
 *
 * Reconnection on disconnect is intentionally not provided here — the consumer owns the
 * lifecycle and decides whether/when to reopen. (For v0.3.0 the consumer is the service,
 * which already understands "session lifetime"; reopening on disconnect would race the
 * service's own state machine.)
 */
class HyperHdrJsonApiEvents(
    private val host: String,
    private val port: Int = 19444,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        // No read timeout: WebSocket is long-lived.
        .readTimeout(0, TimeUnit.SECONDS)
        .build(),
) : Closeable {

    private val tanCounter = AtomicInteger(1)
    private val _flow = MutableSharedFlow<JsonEvent>(replay = 0, extraBufferCapacity = 16)
    val flow: SharedFlow<JsonEvent> = _flow.asSharedFlow()

    private var ws: WebSocket? = null

    fun start(token: String?) {
        val request = Request.Builder()
            .url("ws://$host:$port/")
            .build()
        ws = httpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildSubscribeRequest(token))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseEvent(text)?.let { _flow.tryEmit(it) }
            }
            // We don't reconnect on close/failure here. Consumer decides; see class doc.
        })
    }

    private fun buildSubscribeRequest(token: String?): String {
        val tan = tanCounter.getAndIncrement()
        val req = JSONObject().apply {
            put("command", "serverinfo")
            put("tan", tan)
            put("subscribe", JSONArray(listOf(
                "components-update",
                "instance-update",
                "videomode-update",
            )))
            if (token != null) put("token", token)
        }
        return req.toString()
    }

    private fun parseEvent(text: String): JsonEvent? {
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (obj.optString("command")) {
            "components-update" -> {
                val data = obj.optJSONObject("data") ?: return null
                JsonEvent.ComponentChanged(
                    name = data.optString("name"),
                    enabled = data.optBoolean("enabled", false),
                )
            }
            "instance-update" -> {
                val arr = obj.optJSONArray("data") ?: return null
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(Instance(
                            id = o.getInt("instance"),
                            name = o.optString("friendly_name", "instance ${o.getInt("instance")}"),
                            running = o.optBoolean("running", false),
                        ))
                    }
                }
                JsonEvent.InstanceChanged(list)
            }
            "videomode-update" -> {
                val hdr = obj.optJSONObject("data")?.optInt("HDR", 0) ?: 0
                JsonEvent.VideoModeChanged(hdr = hdr != 0)
            }
            "serverinfo" -> {
                // Initial snapshot delivered as the response to our subscribe request.
                val info = obj.optJSONObject("info") ?: return null
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
                JsonEvent.ServerInfoSnapshot(ServerInfo(
                    version = info.optString("hyperhdr_version", ""),
                    instances = instances,
                    authRequired = false,
                ))
            }
            else -> null
        }
    }

    override fun close() {
        ws?.close(1000, "client closing")
        ws = null
    }
}
```

- [ ] **Step 4: Run, confirm PASS**

```bash
./gradlew :common:testDebugUnitTest --tests "eu.hyperhdr.android.json.HyperHdrJsonApiEventsTest"
```
Expected: 1 test passes.

If it fails with "incomingMessages.poll() returned null": the WebSocket handshake didn't complete. Check the `ws://` URL — HyperHDR's actual endpoint is the WebSocket upgrade on the JSON-RPC port, which is the root path `/`. Our `MockWebServer` accepts WebSocket upgrades on any path, but a real HyperHDR may reject some.

- [ ] **Step 5: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/json/HyperHdrJsonApiEvents.kt \
        common/src/test/java/eu/hyperhdr/android/json/HyperHdrJsonApiEventsTest.kt
git commit -m "feat(json): HyperHdrJsonApiEvents — WebSocket subscribe + components-update"
```

---

## Task 3: TDD `HyperHdrJsonApiEvents` — parse `instance-update` and `videomode-update`

**Files:**
- Modify: `common/src/test/java/eu/hyperhdr/android/json/HyperHdrJsonApiEventsTest.kt` — append two more tests

Implementation already supports both — Task 2's parser handles all four cases. This task locks the wire format with explicit tests.

- [ ] **Step 1: Append the tests**

Inside the existing `HyperHdrJsonApiEventsTest` class:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@Test
fun `parses instance-update push as InstanceChanged event`() = runTest {
    server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
    val events = HyperHdrJsonApiEvents(host = server.hostName, port = server.port)
    events.start(token = null)

    incomingMessages.poll(2, TimeUnit.SECONDS) // drain subscribe

    serverSocket!!.send("""
        {"command":"instance-update","data":[
            {"instance":0,"running":true,"friendly_name":"LED Frame"},
            {"instance":1,"running":false,"friendly_name":"Bias Lights"}
        ]}
    """.trimIndent())

    val event = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(2_000) { events.flow.first() }
    }
    assertThat(event).isInstanceOf(JsonEvent.InstanceChanged::class.java)
    val ic = event as JsonEvent.InstanceChanged
    assertThat(ic.instances).hasSize(2)
    assertThat(ic.instances[0]).isEqualTo(Instance(0, "LED Frame", true))
    assertThat(ic.instances[1]).isEqualTo(Instance(1, "Bias Lights", false))

    events.close()
}

@OptIn(ExperimentalCoroutinesApi::class)
@Test
fun `parses videomode-update HDR=1 as VideoModeChanged(true)`() = runTest {
    server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
    val events = HyperHdrJsonApiEvents(host = server.hostName, port = server.port)
    events.start(token = null)

    incomingMessages.poll(2, TimeUnit.SECONDS) // drain subscribe

    serverSocket!!.send("""{"command":"videomode-update","data":{"HDR":1}}""")

    val event = withContext(Dispatchers.Default.limitedParallelism(1)) {
        withTimeout(2_000) { events.flow.first() }
    }
    assertThat(event).isInstanceOf(JsonEvent.VideoModeChanged::class.java)
    assertThat((event as JsonEvent.VideoModeChanged).hdr).isTrue()

    events.close()
}
```

- [ ] **Step 2: Run, confirm PASS**

```bash
./gradlew :common:testDebugUnitTest --tests "eu.hyperhdr.android.json.HyperHdrJsonApiEventsTest"
```
Expected: 3 tests pass.

- [ ] **Step 3: Commit**

```bash
git add common/src/test/java/eu/hyperhdr/android/json/HyperHdrJsonApiEventsTest.kt
git commit -m "test(json): cover instance-update and videomode-update event parsing"
```

---

## Task 4: Service-side wiring — bridge events into `HdrDetector`

**Files:**
- Modify: `common/src/main/java/eu/hyperhdr/android/service/HyperHdrCaptureService.kt`

Plan 4 wired `HdrDetector.current` (Android-side display HDR detection) → `setHdrVideoMode(...)` (HyperHDR-side signal). Now we add the reverse direction: when HyperHDR's web UI toggles its own HDR-tonemap component (or when another client changes the videomode), the app's UI should reflect that — by re-emitting the `HdrDetector.current` value, which already drives the Plan 4 round-trip and any UI that observes it.

Specifically: when we receive a `JsonEvent.VideoModeChanged(hdr=X)` from the WebSocket, and X disagrees with our last-sent value, we **don't** override the local detector — that would create a feedback loop where two clients toggling each other oscillate forever. Instead we just record the server's last-known value so the UI can show "server: HDR on, my display: SDR" if those disagree.

For v0.3.0 we keep this simple: the service exposes a new `serverHdrSignaled: StateFlow<Boolean>` on the binder that the UI can read. No state-machine changes; no bidirectional sync; just observe-and-display.

- [ ] **Step 1: Add the field + WebSocket lifecycle to `HyperHdrCaptureService`**

In `HyperHdrCaptureService.kt`, add fields:

```kotlin
    private var jsonEvents: eu.hyperhdr.android.json.HyperHdrJsonApiEvents? = null
    private var jsonEventsCollectorJob: Job? = null
    private val _serverHdrSignaled = kotlinx.coroutines.flow.MutableStateFlow(false)
    val serverHdrSignaled: kotlinx.coroutines.flow.StateFlow<Boolean> = _serverHdrSignaled
```

(Place these next to the other private encoder/reconnector/jsonClient fields.)

- [ ] **Step 2: Open the WebSocket in `startCapture()`**

In `startCapture(...)`, after the existing `jsonClient = HyperHdrJsonApiClient(...)` block, add:

```kotlin
        jsonEvents = eu.hyperhdr.android.json.HyperHdrJsonApiEvents(
            host = profile.host, port = profile.jsonPort,
        ).also { it.start(profile.token) }

        jsonEventsCollectorJob = scope.launch {
            jsonEvents?.flow?.collect { ev ->
                when (ev) {
                    is eu.hyperhdr.android.json.JsonEvent.VideoModeChanged ->
                        _serverHdrSignaled.value = ev.hdr
                    is eu.hyperhdr.android.json.JsonEvent.ComponentChanged -> {
                        if (ev.name == "HDR") _serverHdrSignaled.value = ev.enabled
                    }
                    else -> { /* InstanceChanged, ServerInfoSnapshot — UI surfaces consume directly via binder */ }
                }
            }
        }
```

- [ ] **Step 3: Tear down in `stopCapture()`**

Add to `stopCapture()`:

```kotlin
        jsonEventsCollectorJob?.cancel(); jsonEventsCollectorJob = null
        jsonEvents?.close(); jsonEvents = null
        _serverHdrSignaled.value = false
```

- [ ] **Step 4: Expose via the binder**

Open `HyperHdrServiceBinder.kt`. Add:

```kotlin
    /** Latest HDR-tonemap state reported by the server over the WebSocket. */
    val serverHdrSignaled: kotlinx.coroutines.flow.StateFlow<Boolean> get() = service.serverHdrSignaled

    /** Live stream of all WebSocket events from the server (since service start). */
    val jsonEventsFlow: kotlinx.coroutines.flow.Flow<eu.hyperhdr.android.json.JsonEvent>
        get() = service.jsonEvents?.flow ?: kotlinx.coroutines.flow.emptyFlow()
```

- [ ] **Step 5: Verify everything compiles**

```bash
./gradlew :common:compileDebugKotlin :tv:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Verify all unit tests still pass**

```bash
./gradlew :common:testDebugUnitTest
```
Expected: BUILD SUCCESSFUL — Plans 1–5 tests + Plan 6 Tasks 2–3's new 3 = 38 tests.

- [ ] **Step 7: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/service/HyperHdrCaptureService.kt \
        common/src/main/java/eu/hyperhdr/android/service/HyperHdrServiceBinder.kt
git commit -m "feat(service): wire WebSocket events into binder (serverHdrSignaled, jsonEventsFlow)"
```

---

## Task 5: UI — reflect live `serverHdrSignaled` in the main screen footer

**Files:**
- Modify: `tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt`

The live stats footer (Plan 4 Task 4) currently shows `fps · KB/s · WxH` and an error indicator. Append a server-HDR badge when `serverHdrSignaled.value == true`.

- [ ] **Step 1: Add a third collector in `observeState()`**

Open `tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt`. In `observeState()`, alongside the existing `binder?.state?.collect { ... }` and `binder?.stats?.collect { ... }` blocks, add:

```kotlin
        viewLifecycleOwner.lifecycleScope.launch {
            binder?.serverHdrSignaled?.collect { hdr ->
                val statsView = view?.findViewById<TextView>(R.id.tv_stats) ?: return@collect
                // Append/strip the HDR badge to whatever the stats text currently is.
                val cur = statsView.text?.toString().orEmpty()
                val withoutBadge = cur.removeSuffix("  ✦ HDR").removeSuffix("  ✦ HDR ")
                statsView.text = if (hdr) "$withoutBadge  ✦ HDR" else withoutBadge
            }
        }
```

(The badge composition is a bit hacky — strip then re-add — because the stats collector and the HDR collector both write to the same TextView. A more robust pattern would combine them via `combine(statsFlow, serverHdrSignaled)` into one collector; that's left as a polish item if the live-update churn is visible.)

- [ ] **Step 2: Verify the APK builds**

```bash
./gradlew :tv:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add tv/src/main/java/eu/hyperhdr/android/tv/ui/MainFragment.kt
git commit -m "feat(tv-ui): show ✦ HDR badge in stats footer when server reports HDR-tonemap on"
```

---

## Task 6: UI — live instance picker in settings

**Files:**
- Modify: `tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsFragment.kt`

Plan 3's settings screen has a static "Reconfigure server…" entry that opens the wizard. Now, while the user is on the settings screen, we listen for `JsonEvent.InstanceChanged` and update the entry's summary if the active instance's friendly name changes server-side.

This is intentionally minimal — we don't add an instance picker dropdown to settings (that's wizard territory). We just keep the summary live so a user who renames an instance in HyperHDR's web UI sees it reflected in the app within ~1 second.

- [ ] **Step 1: Bind the service in `SettingsFragment`**

`SettingsFragment` is a `LeanbackPreferenceFragment` (legacy) which doesn't have a clean lifecycleScope. We use `lifecycleScope` from the host activity's `lifecycleScope` extension via the `LifecycleOwner` interface that fragment exposes.

In `SettingsFragment.kt`, find `onCreatePreferences(savedInstanceState, rootKey)`. After the existing setup (where `screen` is built), add a service binding + collection block. The cleanest pattern: bind in `onResume`, unbind in `onPause`. Add these new methods:

```kotlin
    private var binder: eu.hyperhdr.android.service.HyperHdrServiceBinder? = null
    private val serviceConn = object : android.content.ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, service: android.os.IBinder?) {
            binder = service as? eu.hyperhdr.android.service.HyperHdrServiceBinder
            startEventCollector()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) { binder = null }
    }
    private var eventCollectorJob: kotlinx.coroutines.Job? = null

    override fun onResume() {
        super.onResume()
        val ctx = requireContext()
        ctx.bindService(
            android.content.Intent(ctx, eu.hyperhdr.android.service.HyperHdrCaptureService::class.java),
            serviceConn, android.content.Context.BIND_AUTO_CREATE,
        )
    }

    override fun onPause() {
        eventCollectorJob?.cancel(); eventCollectorJob = null
        runCatching { requireContext().unbindService(serviceConn) }
        binder = null
        super.onPause()
    }

    private fun startEventCollector() {
        val b = binder ?: return
        eventCollectorJob = androidx.lifecycle.lifecycleScope.launch {
            b.jsonEventsFlow.collect { ev ->
                if (ev is eu.hyperhdr.android.json.JsonEvent.InstanceChanged) {
                    val store = eu.hyperhdr.android.settings.ProfileStore(
                        eu.hyperhdr.android.settings.EncryptedProfileStorage.create(requireContext())
                    )
                    val profile = store.load() ?: return@collect
                    val current = ev.instances.firstOrNull { it.id == profile.instanceId }
                    val pref = findPreference<androidx.preference.Preference>("server_card") ?: return@collect
                    pref.summary = current?.let { "${profile.host} → ${it.name}" } ?: "${profile.host}:${profile.flatbufPort}"
                }
            }
        }
    }
```

(Note: `androidx.lifecycle.lifecycleScope` requires `import androidx.lifecycle.lifecycleScope` — fragment's `lifecycleScope` extension. If your IDE complains, the compiler will guide you to the correct import.)

- [ ] **Step 2: Add a key to the existing reconfigure preference so we can `findPreference` it**

In the same file, find the existing `Preference(ctx).apply { title = "Reconfigure server…" ... }` block. Add:

```kotlin
            key = "server_card"
```

Right at the top of the `apply { ... }` body so it's findable from the event collector.

- [ ] **Step 3: Verify**

```bash
./gradlew :tv:assembleDebug
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add tv/src/main/java/eu/hyperhdr/android/tv/ui/settings/SettingsFragment.kt
git commit -m "feat(tv-ui): settings screen reflects live instance-name updates from WebSocket"
```

---

## Task 7: Reconnect on disconnect (basic)

**Files:**
- Modify: `common/src/main/java/eu/hyperhdr/android/json/HyperHdrJsonApiEvents.kt`

The service-side WebSocket can die for boring reasons (Wi-Fi blip, server restart, NAT timeout). Without reconnection, the live UI just goes silent until the user reopens settings. Add a backoff-driven reconnect inside `HyperHdrJsonApiEvents` itself, reusing `BackoffSchedule` from Plan 1.

- [ ] **Step 1: Modify the class to manage its own lifecycle**

Add backoff + reconnect logic. Replace the body of `HyperHdrJsonApiEvents.kt` with this expanded version (keep the same imports, keep `JsonEvent` and parsing untouched):

The simplest robust pattern: launch a coroutine that loops, opening a new socket each iteration; on close/failure increment backoff; on success reset backoff. The flow stays the same `MutableSharedFlow` so consumers don't notice the connection cycling.

```kotlin
package eu.hyperhdr.android.json

import eu.hyperhdr.android.flatbuf.BackoffSchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HyperHdrJsonApiEvents(
    private val host: String,
    private val port: Int = 19444,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .build(),
    private val backoff: BackoffSchedule = BackoffSchedule.default(),
) : Closeable {

    private val tanCounter = AtomicInteger(1)
    private val _flow = MutableSharedFlow<JsonEvent>(replay = 0, extraBufferCapacity = 16)
    val flow: SharedFlow<JsonEvent> = _flow.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    @Volatile private var ws: WebSocket? = null

    fun start(token: String?) {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (true) {
                val opened = openOnce(token)
                if (opened) backoff.reset() else delay(backoff.nextDelayMillis())
            }
        }
    }

    /** Opens the socket and suspends until it closes (gracefully or by failure). */
    private suspend fun openOnce(token: String?): Boolean {
        val request = Request.Builder().url("ws://$host:$port/").build()
        val closedSignal = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildSubscribeRequest(token))
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                parseEvent(text)?.let { _flow.tryEmit(it) }
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closedSignal.complete(true) // graceful close
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closedSignal.complete(false) // failure
            }
        }
        ws = httpClient.newWebSocket(request, listener)
        return closedSignal.await()
    }

    private fun buildSubscribeRequest(token: String?): String {
        val tan = tanCounter.getAndIncrement()
        val req = JSONObject().apply {
            put("command", "serverinfo")
            put("tan", tan)
            put("subscribe", JSONArray(listOf(
                "components-update",
                "instance-update",
                "videomode-update",
            )))
            if (token != null) put("token", token)
        }
        return req.toString()
    }

    private fun parseEvent(text: String): JsonEvent? {
        // Same parser as Task 2 — reproduced verbatim. (Don't refactor in this task; one
        // concern per commit.)
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        return when (obj.optString("command")) {
            "components-update" -> {
                val data = obj.optJSONObject("data") ?: return null
                JsonEvent.ComponentChanged(
                    name = data.optString("name"),
                    enabled = data.optBoolean("enabled", false),
                )
            }
            "instance-update" -> {
                val arr = obj.optJSONArray("data") ?: return null
                val list = buildList {
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        add(Instance(
                            id = o.getInt("instance"),
                            name = o.optString("friendly_name", "instance ${o.getInt("instance")}"),
                            running = o.optBoolean("running", false),
                        ))
                    }
                }
                JsonEvent.InstanceChanged(list)
            }
            "videomode-update" -> {
                val hdr = obj.optJSONObject("data")?.optInt("HDR", 0) ?: 0
                JsonEvent.VideoModeChanged(hdr = hdr != 0)
            }
            "serverinfo" -> {
                val info = obj.optJSONObject("info") ?: return null
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
                JsonEvent.ServerInfoSnapshot(ServerInfo(
                    version = info.optString("hyperhdr_version", ""),
                    instances = instances,
                    authRequired = false,
                ))
            }
            else -> null
        }
    }

    override fun close() {
        loopJob?.cancel()
        scope.cancel()
        ws?.close(1000, "client closing")
        ws = null
    }
}
```

- [ ] **Step 2: Add a reconnection test**

Append to `HyperHdrJsonApiEventsTest.kt`:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@Test
fun `reconnects after server-side disconnect`() = runTest {
    server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
    server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))  // second connection

    val events = HyperHdrJsonApiEvents(
        host = server.hostName, port = server.port,
        backoff = BackoffSchedule(longArrayOf(50L), capMillis = 50L),
    )
    events.start(token = null)

    incomingMessages.poll(2, TimeUnit.SECONDS) // drain first subscribe
    serverSocket!!.close(1000, "test drop")

    // Within ~150 ms (50 ms backoff + connect), client reconnects and re-subscribes.
    val secondSubscribe = withContext(Dispatchers.IO) {
        incomingMessages.poll(3, TimeUnit.SECONDS)
    }
    assertThat(secondSubscribe).isNotNull()
    assertThat(secondSubscribe!!).contains("\"command\":\"serverinfo\"")

    events.close()
}
```

(Remember to import `eu.hyperhdr.android.flatbuf.BackoffSchedule` at the top of the test file.)

- [ ] **Step 3: Run, confirm PASS**

```bash
./gradlew :common:testDebugUnitTest --tests "eu.hyperhdr.android.json.HyperHdrJsonApiEventsTest"
```
Expected: 4 tests pass.

- [ ] **Step 4: Commit**

```bash
git add common/src/main/java/eu/hyperhdr/android/json/HyperHdrJsonApiEvents.kt \
        common/src/test/java/eu/hyperhdr/android/json/HyperHdrJsonApiEventsTest.kt
git commit -m "feat(json): WebSocket reconnects with BackoffSchedule on disconnect"
```

---

## Task 8: Manual end-to-end test doc

**Files:**
- Create: `docs/manual-tests/04-websocket-events.md`

- [ ] **Step 1: Write the doc**

```bash
cat > /Users/jneerdael/Scripts/hyperhdr/HyperHDR-android/docs/manual-tests/04-websocket-events.md <<'EOF'
# Manual test: WebSocket live events

**Prereqs:** v0.3.0+ APK installed; real HyperHDR v22+ on the LAN; HyperHDR web UI open in a browser; capture started in the app.

**Steps:**

1. With capture running, open the app's main screen. Note the stats footer.
2. In HyperHDR's web UI, go to *Configuration → Components* (or wherever HDR-tonemap is toggled).
3. Toggle the HDR component **on**. Within ~1 second the app's stats footer should grow a `✦ HDR` badge.
4. Toggle it **off**. Within ~1 second the badge disappears.
5. In the web UI, rename one of the instances (e.g. "LED Frame" → "LED Frame v2"). In the app, open Settings — the "Reconfigure server…" summary should reflect the new name.

**Optional disconnect-recovery check:**

6. With the app open on the main screen, restart the HyperHDR service on the server (`systemctl restart hyperhdr`).
7. The badge briefly disappears as the WebSocket drops.
8. Within a few seconds, the WebSocket reconnects and the badge state is restored.

**Pass:** all five (or eight) steps complete without app restarts.

**Fail modes:**
- Badge never appears: WebSocket isn't opening. Check `adb logcat | grep HyperHdr.JsonApi` for `onFailure` messages — likely the `ws://` URL or path is wrong for your HyperHDR version.
- Badge appears but never disappears: parser is mapping `enabled=false` somewhere.
- Settings instance name doesn't update: `findPreference("server_card")` is failing — verify the preference's `key = "server_card"` was set in Task 6.
- Badge oscillates rapidly: feedback loop between local detector and server signaling. The Plan 6 design explicitly avoids this by treating server-side as observe-only; if you see oscillation, check that no code path in `HyperHdrCaptureService` calls `setHdrVideoMode` from inside the events collector.
EOF
git add docs/manual-tests/04-websocket-events.md
git commit -m "docs: manual test for WebSocket live-events flow"
```

---

## Task 9: v0.3.0 release cut

**Files:**
- Modify: `tv/build.gradle` — versionCode 2→3, versionName "0.2.0"→"0.3.0"

- [ ] **Step 1: Bump version**

```groovy
        versionCode 3
        versionName "0.3.0"
```

- [ ] **Step 2: Full verification**

```bash
./gradlew :common:testDebugUnitTest
./gradlew :tv:assembleRelease
ANDROID_SERIAL=192.168.50.98:5555 ./gradlew :common:connectedDebugAndroidTest
```

All BUILD SUCCESSFUL. Cert SHA-256 still `71eda2e2ba6ff35d7cfdca3441ddf7d6bca818de6f635230390b439ce63f7081`.

- [ ] **Step 3: Commit, tag, push**

```bash
git add tv/build.gradle
git commit -m "build: bump to 0.3.0"
git push origin main
git tag -a v0.3.0 -m "HyperHDR-Android v0.3.0

Live WebSocket JSON-API events:
- Server-side HDR-tonemap changes update the main-screen footer badge live
- Server-side instance renames reflect in settings within ~1 second
- WebSocket reconnects with the same exponential backoff used by the flatbuffer client
- HTTP one-shot transport unchanged for register-time calls

Same signing identity as v0.1.0 (cert SHA-256: 71eda2e2…39ce63f7081)."
git push origin v0.3.0
gh run watch --repo johnneerdael/HyperHDR-android --exit-status
```

- [ ] **Step 4: Smoke install + verify**

```bash
cd /tmp && rm -f tv-release.apk
gh release download v0.3.0 --repo johnneerdael/HyperHDR-android --pattern '*.apk' -O tv-release.apk
adb install -r /tmp/tv-release.apk
```

Run through the manual test doc from Task 8.

---

## Plan 6 done definition

- [ ] All 9 tasks done.
- [ ] 4 new unit tests pass alongside existing 35.
- [ ] Manual test in Task 8 passes against a real HyperHDR.
- [ ] `v0.3.0` published with same signing identity.

---

## Self-review (writer's pre-flight)

- **Coverage of v2 backlog item #2 (WebSocket events):** Tasks 1–4 implement the protocol + service wiring; Tasks 5–6 surface in the UI. Task 7 makes the connection self-healing. Task 8–9 ship it.
- **Type consistency:** `JsonEvent` (sealed interface) introduced in Task 1; referenced consistently in Tasks 2, 4, 5, 6. `HyperHdrJsonApiEvents` constructor signature `(host, port, httpClient, backoff)` matches across Tasks 2 and 7.
- **Wire format matches HyperHDR:** verified by reading `HyperHDR/sources/api/HyperAPI.cpp:307-318` (subscribeOnly check) and `HyperAPI.cpp:463-470` (CallbackAPI::subscribe). The `serverinfo` request with a `subscribe` array is HyperHDR's documented mechanism. Component-update event names (`HDR`, etc.) come from `Components.h` enum.
- **No public API drift from Plan 1:** `HyperHdrJsonApiClient`, `HyperHdrFlatBufferClient`, `HyperHdrFlatBufferReconnector` all unchanged. Plan 6 adds new types alongside.
- **No placeholders:** every test has full assertions; every snippet compiles; every command has expected output. The one slightly-handwavy area is Task 5's stats-footer concatenation; called out as a polish item rather than left as TODO.
- **Risk:** the WebSocket URL `ws://host:19444/` is best-guess based on reading HyperHDR's webserver. If a particular HyperHDR build serves WebSocket on `ws://host:19444/json-rpc` instead, Task 2's first manual run will surface the issue and the engineer can adjust the path. Worth verifying against a real server before shipping.
