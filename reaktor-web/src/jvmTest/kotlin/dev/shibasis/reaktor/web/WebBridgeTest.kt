package dev.shibasis.reaktor.web

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.service.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*
import org.koin.dsl.koinApplication
import kotlin.test.*

@Serializable private class EchoRequest(val text: String) : Request()
@Serializable private class EchoResponse(val text: String) : Response()

class WebBridgeTest {
    private class Echo : Service() {
        override val contract = ServiceContract("web.test")
        val entered = Channel<Request>(8)
        val events = MutableSharedFlow<String>(extraBufferCapacity = 4)
        val echo = server(PostHandler, "/echo", "document.echo", EchoRequest.serializer(), EchoResponse.serializer()) {
            entered.send(it)
            EchoResponse(it.text)
        }
    }
    private class Host : WebViewHost, WebViewController {
        lateinit var context: WebHostContext
        var configuration: JsonObject? = null
        val output = Channel<String>(64)
        var closes = 0
        override val features = WebFeature.entries.toSet()
        override fun create(): WebViewController = this
        override fun configure(context: WebHostContext) { this.context = context }
        override fun load(content: WebContent) = Unit
        override fun executeJavaScript(script: String) = Unit
        override fun configureBridge(configuration: JsonObject) { this.configuration = configuration }
        override suspend fun postMessage(body: String) { output.send(body) }
        override fun close() { closes++ }
    }
    private suspend fun fixture(block: suspend (Echo, Host, WebSession, MutableStateFlow<WebGrant?>) -> Unit) {
        val koin = koinApplication {}
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koin))
        val service = Echo()
        val app = WebApp("test", "v1", WebAssets(mapOf("/index.html" to WebAsset("<p>test</p>".encodeToByteArray(), "text/html"))))
        val grants = MutableStateFlow<WebGrant?>(WebGrant("test", "v1", "alice", "workspace", Environment.STAGE, setOf("document.echo", "document.changed")))
        val bridge = graph.Node { WebBridge(it, service, grants, authorize = { grant, _ -> grant.principal == "alice" },
            events = listOf(WebEventBinding(service.contract, "document.changed", String.serializer(), service.events))) }
        val runtime = graph.Node { WebRuntime(it) }
        val host = Host()
        val session = runtime.open(host, bridge = bridge)
        try {
            session.load(WebContent.Bundle(app))
            host.context.navigationStarted(app.origin + "/v1/index.html")
            host.context.navigationCommitted(app.origin + "/v1/index.html", "Test", false, false)
            block(service, host, session, grants)
        } finally { runtime.close(); bridge.close(); graph.close(); koin.close() }
    }
    private fun request(host: Host, service: Echo, id: String = "r_1"): WebMessageEnvelope {
        val config = requireNotNull(host.configuration)
        return WebMessageEnvelope(id = id, kind = "invoke", session = config.getValue("session").jsonPrimitive.content,
            epoch = config.getValue("epoch").jsonPrimitive.long, operation = "document.echo",
            contract = service.contract.id, schema = service.echo.descriptor(service.contract).request.fingerprint,
            payload = buildJsonObject { put("text", "नमस्ते 🌍") })
    }
    private fun send(host: Host, message: WebMessageEnvelope, url: String = "https://test.reaktor.invalid/v1/index.html", main: Boolean = true) =
        host.context.message(Json.encodeToString(message), url, main)
    private suspend fun answer(host: Host): WebMessageEnvelope = withTimeout(2_000) {
        val body = host.output.receive()
        assertEquals(1, Json.parseToJsonElement(body).jsonObject.getValue("protocol").jsonPrimitive.int)
        Json.decodeFromString(body)
    }

    @Test fun typedCallsBindHostAuthorityAndRepeatedCommitsPreserveConnection() = runBlocking {
        fixture { service, host, session, _ ->
            val config = host.configuration
            host.context.navigationCommitted("https://test.reaktor.invalid/v1/index.html", "New title", true, false)
            assertSame(config, host.configuration)
            send(host, request(host, service))
            val entered = withTimeout(2_000) { service.entered.receive() }
            assertEquals(Environment.STAGE, entered.environment)
            assertEquals("alice", (entered.attributes["reaktor.web.grant"] as WebGrant).principal)
            assertEquals("नमस्ते 🌍", answer(host).payload.jsonObject["text"]?.jsonPrimitive?.content)
            assertEquals("New title", session.state.value.title)
            send(host, request(host, service))
            assertEquals("duplicate_request", answer(host).error)
            assertTrue(service.entered.tryReceive().isFailure)
        }
    }
    @Test fun framesOriginsEpochAndSchemaCannotForgeAuthority() = runBlocking {
        fixture { service, host, _, _ ->
            val request = request(host, service)
            send(host, request, main = false)
            send(host, request, url = "https://evil.example/index.html")
            send(host, request.copy(epoch = request.epoch + 1))
            assertNull(withTimeoutOrNull(100) { service.entered.receive() })
            send(host, request.copy(schema = "forged"))
            assertEquals("schema_mismatch", answer(host).error)
            send(host, request.copy(id = "r_2", operation = "shell.execute"))
            assertEquals("unknown_operation", answer(host).error)
            assertTrue(service.entered.tryReceive().isFailure)
        }
    }
    @Test fun navigationAndGrantRevocationInvalidateOldChannels() = runBlocking {
        fixture { service, host, session, grants ->
            val request = request(host, service)
            grants.value = grants.value?.copy(principal = "mallory")
            send(host, request)
            assertNull(withTimeoutOrNull(100) { service.entered.receive() })
            host.context.navigationStarted(null)
            send(host, request.copy(id = "r_2"))
            assertNull(withTimeoutOrNull(100) { service.entered.receive() })
            assertNull(session.state.value.committedUrl)
        }
    }
    @Test fun policiesAndAssetPathsRejectTraversalAndAmbientLoopback() {
        assertFalse(WebNavigationPolicy().allows("http://127.0.0.1:9280"))
        assertTrue(WebNavigationPolicy(loopbackOrigins = setOf("http://127.0.0.1:9280")).allows("http://127.0.0.1:9280/path"))
        listOf("/../secrets", "/a/%2e%2e/secrets", "/a%2fb.js", "/a%5cb.js", "/%252e").forEach {
            assertFailsWith<IllegalArgumentException> { webAssetPath(it) }
        }
        assertEquals("/x.js", webAssetPath("/x.js?v=1"))
        assertFalse(boundedWebJson("[".repeat(65) + "]".repeat(65)))
        assertFalse(boundedWebJson("\"" + "x".repeat(65_536) + "\""))
    }
    @Test fun eventSubscriptionCancellationAndRevocationReleaseCollectors() = runBlocking {
        fixture { service, host, _, grants ->
            val definition = requireNotNull(host.configuration)["events"]!!.jsonArray.first().jsonObject
            val subscription = request(host, service).copy(kind = "subscribe", operation = "document.changed",
                schema = definition.getValue("schema").jsonPrimitive.content, payload = JsonNull)
            send(host, subscription)
            assertNull(answer(host).error)
            withTimeout(2_000) { service.events.subscriptionCount.first { it == 1 } }
            service.events.emit("changed")
            val event = answer(host)
            assertEquals("event", event.kind)
            assertEquals(subscription.id, event.replyTo)
            assertEquals("changed", event.payload.jsonPrimitive.content)
            send(host, subscription.copy(id = "r_2", kind = "unsubscribe", replyTo = subscription.id))
            withTimeout(2_000) { service.events.subscriptionCount.first { it == 0 } }
            while (answer(host).replyTo != "r_2") Unit
            send(host, subscription.copy(id = "r_3"))
            answer(host)
            withTimeout(2_000) { service.events.subscriptionCount.first { it == 1 } }
            grants.value = null
            withTimeout(2_000) { service.events.subscriptionCount.first { it == 0 } }
        }
    }
    @Test fun assetManifestOwnsItsBytesAndRejectsCollisions() {
        val bytes = "original".encodeToByteArray()
        val manifest = mutableMapOf("/index.html" to WebAsset(bytes, "text/html"))
        val assets = WebAssets(manifest)
        bytes[0] = 0
        manifest.clear()
        assertEquals("original", assets.read("/index.html")!!.bytes.decodeToString())
        assets.read("/index.html")!!.bytes[0] = 0
        assertEquals("original", assets.read("/index.html")!!.bytes.decodeToString())
        assertFailsWith<IllegalArgumentException> { assets + assets }
    }
}
