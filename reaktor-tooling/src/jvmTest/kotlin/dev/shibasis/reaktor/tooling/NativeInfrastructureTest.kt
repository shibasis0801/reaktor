package dev.shibasis.reaktor.tooling

import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.tooling.infra.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class NativeInfrastructureTest {
    @Test fun workerReadsRejectWrongAuthorityTargetsAndReceipts() {
        val operation = WorkerOperationDescriptor("d1.inspect", "d1")
        val catalog = WorkerOperationCatalog(worker = "fixture", environment = "production", operations = listOf(operation))
        catalog.requireRead("production", operation.id)
        assertFails { catalog.requireRead("dev", operation.id) }
        assertFails { catalog.copy(protocol = "future").requireRead("production", operation.id) }
        assertFails { catalog.copy(operations = listOf(operation, operation)).requireRead("production", operation.id) }
        assertFails { catalog.copy(operations = listOf(operation.copy(effect = "write"))).requireRead("production", operation.id) }
        val receipt = WorkerOperationResult(requestId = "request-one", operation = operation.id, worker = catalog.worker,
            environment = catalog.environment, ok = true, durationMillis = 1)
        receipt.requireMatches(catalog, "request-one", operation.id)
        listOf(receipt.copy(requestId = "other"), receipt.copy(environment = "dev"), receipt.copy(worker = "other"),
            receipt.copy(operation = "kv.inspect"), receipt.copy(protocol = "future"), receipt.copy(durationMillis = -1),
            receipt.copy(ok = false), receipt.copy(error = "provider_read_failed")).forEach {
            assertFails { it.requireMatches(catalog, "request-one", operation.id) }
        }
    }

    @Test fun closingSessionCancelsAnInFlightHttpRequest() = runBlocking {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()
        server.createContext("/") { exchange ->
            started.countDown()
            release.await(10, TimeUnit.SECONDS)
            exchange.close()
        }
        server.start()
        val session = InfrastructureSession()
        try {
            val request = async(Dispatchers.IO) { runCatching {
                BoundedHttp(session).request(URI("http://127.0.0.1:${server.address.port}/"))
            } }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            withTimeout(3_000) {
                withContext(Dispatchers.IO) { session.close() }
                assertTrue(request.await().isFailure)
            }
        } finally {
            release.countDown()
            session.close()
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).close()
        }
    }

    @Test fun nativePlansBindCredentialsAndDefinitionsWithoutPublishingSecrets() {
        val root = Files.createTempDirectory("native-plan").toFile()
        try {
            val config = root.resolve("config").apply { writeText("first") }
            val seal = ProcessDefinitionSeal.capture(listOf(config))
            val operation = InfrastructureOperation.KubernetesRead(config.path, "test", "Inventory")
            fun plan(password: String) = NativeExecutionRequest.create(operation, root, TaskId("native-test"),
                SafetyPolicy(SafetyClass.LiveRead), mapOf("PASSWORD" to password), 1000, seal)
            val first = plan("private-one")
            assertNotEquals(first.plan.fingerprint, plan("private-two").plan.fingerprint)
            assertFalse(Json.encodeToString(first.plan).contains("private-one"))
            first.verify()
            config.writeText("changed")
            assertFailsWith<IllegalStateException> { first.verify() }
        } finally { root.deleteRecursively() }
    }

    @Test fun kubernetesEventsUseTheApiAndRejectExecutableCredentialPlugins() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val paths = mutableListOf<String>()
        server.createContext("/") { exchange ->
            paths += exchange.requestURI.toString()
            val bytes = """{"apiVersion":"v1","kind":"EventList","items":[]}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val root = Files.createTempDirectory("native-kube").toFile()
        try {
            val config = root.resolve("config").apply { writeText("""
                apiVersion: v1
                kind: Config
                current-context: fixture
                clusters:
                - name: fixture
                  cluster:
                    server: http://127.0.0.1:${server.address.port}
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
            InfrastructureSession().use { session ->
                val output = KubernetesJvmClient(config, session).inspect("test", "Events", "pod-one")
                assertContains(output, "EventList")
                assertTrue(paths.any { it.startsWith("/api/v1/namespaces/test/events?") && it.contains("involvedObject.name") })
            }
            config.appendText("\n    exec:\n      command: forbidden-external-program\n")
            InfrastructureSession().use { session -> assertFailsWith<IllegalArgumentException> { KubernetesJvmClient(config, session) } }
        } finally { root.deleteRecursively(); server.stop(0) }
    }

    @Test fun sessionClosureReleasesCurrentAndLateResources() {
        val closes = AtomicInteger()
        val session = InfrastructureSession()
        session.own(AutoCloseable { closes.incrementAndGet() })
        session.close()
        session.close()
        assertEquals(1, closes.get())
        assertFailsWith<IllegalStateException> { session.own(AutoCloseable { closes.incrementAndGet() }) }
        assertEquals(2, closes.get())
    }

    @Test fun httpRejectsOversizedBodiesAndDoesNotFollowRedirects() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirected = AtomicInteger()
        server.createContext("/large") { exchange ->
            val bytes = "x".repeat(2000).toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.createContext("/redirect") { exchange ->
            exchange.responseHeaders.add("Location", "/secret")
            exchange.sendResponseHeaders(302, -1); exchange.close()
        }
        server.createContext("/secret") { exchange -> redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
        server.start()
        try {
            InfrastructureSession().use { session ->
                val http = BoundedHttp(session)
                assertFailsWith<IllegalStateException> { http.request(URI("http://127.0.0.1:${server.address.port}/large"), maxBytes = 1000) }
                assertFailsWith<IllegalStateException> { http.request(URI("http://127.0.0.1:${server.address.port}/redirect"), headers = mapOf("Authorization" to "Bearer private")) }
                assertEquals(0, redirected.get())
            }
        } finally { server.stop(0) }
    }
}
