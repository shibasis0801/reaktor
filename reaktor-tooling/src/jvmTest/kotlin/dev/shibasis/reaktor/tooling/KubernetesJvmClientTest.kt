package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.infra.InfrastructureSession
import dev.shibasis.reaktor.tooling.infra.KubernetesJvmClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class KubernetesJvmClientTest {
    @Test fun serviceTunnelResolvesNamedPortSkipsUnreadyPodsAndTransportsBothWays() = fixture { server, client, session ->
        enqueueService(server, "\"bolt\"")
        server.enqueue(json("""{"kind":"PodList","items":[
            {"metadata":{"name":"a-unready"},"status":{"phase":"Running"}},
            {"metadata":{"name":"b-ready"},"status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}]},
             "spec":{"containers":[{"name":"db","ports":[{"name":"bolt","containerPort":7687}]}]}}
        ]}"""))
        val closed = CountDownLatch(1)
        server.enqueue(websocket(echoListener(closed)))
        val tunnel = client.portForward("test", "graph", 7000)
        Socket("127.0.0.1", tunnel.localPort).use { socket ->
            socket.soTimeout = 5000
            val payload = "hello through official Kubernetes".toByteArray()
            socket.getOutputStream().write(payload)
            assertContentEquals(payload, socket.getInputStream().readNBytes(payload.size))
            assertEquals("/api/v1/namespaces/test/services/graph", server.takeRequest(5, TimeUnit.SECONDS)!!.path)
            val pods = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("app=graph", pods.requestUrl!!.queryParameter("labelSelector"))
            val forward = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/api/v1/namespaces/test/pods/b-ready/portforward?ports=7687", forward.path)
            assertEquals("Bearer fixture-token", forward.getHeader("Authorization"))
            session.close()
            assertTrue(closed.await(5, TimeUnit.SECONDS), "Operation close must terminate the remote WebSocket")
            assertTrue(runCatching { socket.getInputStream().read() }.getOrDefault(-1) == -1)
        }
        assertFails { Socket("127.0.0.1", tunnel.localPort).close() }
    }

    @Test fun cancellingBeforeRemotePortPrefaceClosesTheWebSocketAndLocalListener() = fixture { server, client, session ->
        enqueueService(server, "7687")
        enqueueReadyPod(server)
        val opened = CountDownLatch(1)
        val closed = CountDownLatch(1)
        server.enqueue(websocket(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { opened.countDown() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason); closed.countDown() }
        }))
        val tunnel = client.portForward("test", "graph", 7000)
        Socket("127.0.0.1", tunnel.localPort).use { socket ->
            assertTrue(opened.await(5, TimeUnit.SECONDS))
            session.close()
            assertTrue(closed.await(5, TimeUnit.SECONDS), "Handshake cancellation leaked the upgraded WebSocket")
            socket.soTimeout = 2000
            assertTrue(runCatching { socket.getInputStream().read() }.getOrDefault(-1) == -1)
        }
        assertFails { Socket("127.0.0.1", tunnel.localPort).close() }
    }

    @Test fun serviceTunnelRejectsMissingSelectorUnreadyPodsAndMissingNamedPort() = fixture { server, client, _ ->
        server.enqueue(json("""{"kind":"Service","spec":{"ports":[{"port":7000}]}}"""))
        assertFailsWith<IllegalArgumentException> { client.portForward("test", "graph", 7000) }
        enqueueService(server, "7687")
        server.enqueue(json("""{"kind":"PodList","items":[{"metadata":{"name":"pending"},"status":{"phase":"Pending"}}]}"""))
        assertFailsWith<IllegalArgumentException> { client.portForward("test", "graph", 7000) }
        enqueueService(server, "\"missing\"")
        enqueueReadyPod(server)
        assertFailsWith<IllegalArgumentException> { client.portForward("test", "graph", 7000) }
        enqueueService(server, "7687")
        server.enqueue(json("""{"kind":"PodList","metadata":{"continue":"next"},"items":[]}"""))
        assertFailsWith<IllegalArgumentException> { client.portForward("test", "graph", 7000) }
    }

    @Test fun credentialsAreDecodedOnceAndRedirectsAreNotFollowed() = fixture { server, client, _ ->
        server.enqueue(json("""{"kind":"Secret","data":{"connection":"cHJpdmF0ZQ=="}}"""))
        assertContentEquals("private".toByteArray(), client.secret("test", "inspector", "connection"))
        server.enqueue(json("""{"kind":"Secret","data":{"connection":"cHJpdmF0ZQ=="}}"""))
        assertFailsWith<IllegalArgumentException> { client.secret("test", "inspector", "connection", maxBytes = 3) }
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/redirected")))
        assertFails { client.secret("test", "inspector", "connection") }
        assertEquals(3, server.requestCount)
    }

    @Test fun partialInventoryAndEventsAreRejectedAndLogsHaveServerLimits() = fixture { server, client, _ ->
        server.enqueue(json("""{"kind":"PodList","metadata":{"continue":"next"},"items":[]}"""))
        assertFailsWith<IllegalArgumentException> { client.inspect("test", "Inventory", "") }
        server.enqueue(json("""{"kind":"EventList","metadata":{"continue":"next"},"items":[]}"""))
        assertFailsWith<IllegalArgumentException> { client.inspect("test", "Events", "pod") }
        server.enqueue(json("""{"kind":"Pod","spec":{"containers":[{"name":"worker"}]}}"""))
        server.enqueue(MockResponse().setBody("a log line"))
        assertContains(client.inspect("test", "Logs", "pod"), "[worker]\na log line")
        repeat(3) { server.takeRequest(5, TimeUnit.SECONDS) }
        val log = server.takeRequest(5, TimeUnit.SECONDS)!!.requestUrl!!
        assertEquals("262144", log.queryParameter("limitBytes"))
        assertEquals("300", log.queryParameter("tailLines"))
        assertEquals("true", log.queryParameter("timestamps"))
        assertEquals("worker", log.queryParameter("container"))
    }

    private fun enqueueService(server: MockWebServer, target: String) {
        server.enqueue(json("""{"kind":"Service","spec":{"selector":{"app":"graph"},"ports":[{"port":7000,"targetPort":$target}]}}"""))
    }
    private fun enqueueReadyPod(server: MockWebServer) {
        server.enqueue(json("""{"kind":"PodList","items":[{"metadata":{"name":"ready"},"status":{"phase":"Running","conditions":[{"type":"Ready","status":"True"}]}}]}"""))
    }
    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun websocket(listener: WebSocketListener) = MockResponse().withWebSocketUpgrade(listener).setHeader("Sec-WebSocket-Protocol", "v4.channel.k8s.io")
    private fun echoListener(closed: CountDownLatch) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            webSocket.send(byteArrayOf(0, 0x07, 0x1e).toByteString()) // Channel zero, port 7687 in little endian.
        }
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) { webSocket.send(bytes) }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { closed.countDown() }
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason); closed.countDown() }
    }
    private fun fixture(block: (MockWebServer, KubernetesJvmClient, InfrastructureSession) -> Unit) {
        val root = Files.createTempDirectory("official-kube-test").toFile()
        MockWebServer().use { server ->
            server.start()
            val config = root.resolve("config").apply { writeText("""
                apiVersion: v1
                kind: Config
                current-context: fixture
                clusters:
                - name: fixture
                  cluster:
                    server: ${server.url("/")}
                contexts:
                - name: fixture
                  context:
                    cluster: fixture
                    user: fixture
                users:
                - name: fixture
                  user:
                    token: fixture-token
            """.trimIndent()) }
            try { InfrastructureSession().use { block(server, KubernetesJvmClient(config, it), it) } }
            finally { root.deleteRecursively() }
        }
    }
}
