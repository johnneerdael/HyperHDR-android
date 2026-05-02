package eu.hyperhdr.android.json

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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
    private lateinit var sharedClient: OkHttpClient
    private val incomingMessages = LinkedBlockingDeque<String>()
    @Volatile private var serverSocket: WebSocket? = null

    private val serverListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            serverSocket = webSocket
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            incomingMessages.put(text)
        }
    }

    @Before fun setUp() {
        sharedClient = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .build()
        server = MockWebServer().also { it.start() }
    }

    @After fun tearDown() {
        // Gracefully close any open server-side WebSocket so MockWebServer can shut down cleanly.
        serverSocket?.close(1000, "test teardown")
        serverSocket = null
        incomingMessages.clear()
        // Give the graceful close a moment to propagate.
        Thread.sleep(200)
        server.shutdown()
    }

    /** Wait for the server-side WebSocket to open (up to 1 second). */
    private fun awaitServerSocket(): WebSocket {
        var attempts = 0
        while (serverSocket == null && attempts++ < 50) Thread.sleep(20)
        return checkNotNull(serverSocket) { "server WebSocket did not open in time" }
    }

    @Test
    fun `parses components-update push as ComponentChanged event`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))

        val events = HyperHdrJsonApiEvents(
            host = server.hostName, port = server.port, httpClient = sharedClient,
        )
        events.start(token = null)

        // Drain the subscribe request the client sends.
        val subscribeRequest = withContext(Dispatchers.IO) {
            incomingMessages.poll(2, TimeUnit.SECONDS)
        }
        assertThat(subscribeRequest).isNotNull()
        assertThat(subscribeRequest!!).contains("\"command\":\"serverinfo\"")
        assertThat(subscribeRequest).contains("\"subscribe\"")
        assertThat(subscribeRequest).contains("components-update")

        // Start collecting BEFORE the push so tryEmit isn't dropped (replay=0).
        val deferred = async(Dispatchers.IO) { events.flow.first() }
        // Give the collector a moment to subscribe to the SharedFlow before we push.
        withContext(Dispatchers.IO) { Thread.sleep(100) }

        val sock = withContext(Dispatchers.IO) { awaitServerSocket() }
        sock.send("""{"command":"components-update","data":{"name":"HDR","enabled":true}}""")

        val event = deferred.await()
        assertThat(event).isInstanceOf(JsonEvent.ComponentChanged::class.java)
        val cc = event as JsonEvent.ComponentChanged
        assertThat(cc.name).isEqualTo("HDR")
        assertThat(cc.enabled).isTrue()

        events.close()
    }

    @Test
    fun `parses instance-update push as InstanceChanged event`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        val events = HyperHdrJsonApiEvents(
            host = server.hostName, port = server.port, httpClient = sharedClient,
        )
        events.start(token = null)

        withContext(Dispatchers.IO) { incomingMessages.poll(2, TimeUnit.SECONDS) } // drain subscribe

        val deferred = async(Dispatchers.IO) { events.flow.first() }
        withContext(Dispatchers.IO) { Thread.sleep(100) }

        val sock = withContext(Dispatchers.IO) { awaitServerSocket() }
        sock.send("""
            {"command":"instance-update","data":[
                {"instance":0,"running":true,"friendly_name":"LED Frame"},
                {"instance":1,"running":false,"friendly_name":"Bias Lights"}
            ]}
        """.trimIndent())

        val event = deferred.await()
        assertThat(event).isInstanceOf(JsonEvent.InstanceChanged::class.java)
        val ic = event as JsonEvent.InstanceChanged
        assertThat(ic.instances).hasSize(2)
        assertThat(ic.instances[0]).isEqualTo(Instance(0, "LED Frame", true))
        assertThat(ic.instances[1]).isEqualTo(Instance(1, "Bias Lights", false))

        events.close()
    }

    @Test
    fun `parses videomode-update HDR=1 as VideoModeChanged(true)`() = runTest {
        server.enqueue(MockResponse().withWebSocketUpgrade(serverListener))
        val events = HyperHdrJsonApiEvents(
            host = server.hostName, port = server.port, httpClient = sharedClient,
        )
        events.start(token = null)

        withContext(Dispatchers.IO) { incomingMessages.poll(2, TimeUnit.SECONDS) } // drain subscribe

        val deferred = async(Dispatchers.IO) { events.flow.first() }
        withContext(Dispatchers.IO) { Thread.sleep(100) }

        val sock = withContext(Dispatchers.IO) { awaitServerSocket() }
        sock.send("""{"command":"videomode-update","data":{"HDR":1}}""")

        val event = deferred.await()
        assertThat(event).isInstanceOf(JsonEvent.VideoModeChanged::class.java)
        assertThat((event as JsonEvent.VideoModeChanged).hdr).isTrue()

        events.close()
    }
}
