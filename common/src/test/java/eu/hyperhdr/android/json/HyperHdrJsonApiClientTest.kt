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

    @Test
    fun `serverInfo treats missing flatbuffer block as stock - no P010 support`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"success":true,"command":"serverinfo","tan":1,
             "info":{"hyperhdr_version":"21.0.0","instance":[]}}
        """.trimIndent()))

        val client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
        val info = client.serverInfo()

        assertThat(info.flatbufferFormats).isEmpty()
        assertThat(info.flatbufferWireVersion).isNull()
        assertThat(info.supportsP010).isFalse()
    }

    @Test
    fun `serverInfo parses flatbuffer block from P010-capable fork`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"success":true,"command":"serverinfo","tan":1,
             "info":{"hyperhdr_version":"22.0.0","instance":[],
                     "flatbuffer":{
                       "imageFormats":["RawImage","NV12Image","P010Image"],
                       "wireVersion":2}}}
        """.trimIndent()))

        val client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
        val info = client.serverInfo()

        assertThat(info.flatbufferFormats)
            .containsExactly("RawImage", "NV12Image", "P010Image")
        assertThat(info.flatbufferWireVersion).isEqualTo(2)
        assertThat(info.supportsP010).isTrue()
    }

    @Test
    fun `serverInfo tolerates flatbuffer block without wireVersion`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"success":true,"command":"serverinfo","tan":1,
             "info":{"hyperhdr_version":"22.0.0","instance":[],
                     "flatbuffer":{"imageFormats":["RawImage","NV12Image"]}}}
        """.trimIndent()))

        val client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
        val info = client.serverInfo()

        assertThat(info.flatbufferFormats).containsExactly("RawImage", "NV12Image")
        assertThat(info.flatbufferWireVersion).isNull()
        assertThat(info.supportsP010).isFalse()
    }

    @Test
    fun `serverInfo treats flatbuffer with imageFormats not an array as empty capabilities`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"success":true,"command":"serverinfo","tan":1,
             "info":{"hyperhdr_version":"22.0.0","instance":[],
                     "flatbuffer":{"imageFormats":"oops","wireVersion":2}}}
        """.trimIndent()))

        val client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
        val info = client.serverInfo()

        assertThat(info.flatbufferFormats).isEmpty()
        // wireVersion still parses since it's a valid int — only the malformed
        // imageFormats field is ignored.
        assertThat(info.flatbufferWireVersion).isEqualTo(2)
        assertThat(info.supportsP010).isFalse()
    }

    @Test
    fun `serverInfo silently drops non-string entries from imageFormats`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"success":true,"command":"serverinfo","tan":1,
             "info":{"hyperhdr_version":"22.0.0","instance":[],
                     "flatbuffer":{
                       "imageFormats":["RawImage", 42, null, "P010Image"],
                       "wireVersion":2}}}
        """.trimIndent()))

        val client = HyperHdrJsonApiClient(host = server.hostName, port = server.port)
        val info = client.serverInfo()

        assertThat(info.flatbufferFormats).containsExactly("RawImage", "P010Image")
        assertThat(info.flatbufferWireVersion).isEqualTo(2)
        assertThat(info.supportsP010).isTrue()
    }
}
