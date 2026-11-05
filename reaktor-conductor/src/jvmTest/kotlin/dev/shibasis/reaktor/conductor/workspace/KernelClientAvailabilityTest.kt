package dev.shibasis.reaktor.conductor.workspace

import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.service.contract.ContractDrift
import dev.shibasis.reaktor.service.contract.ContractShape
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.atlas.AtlasApi
import dev.shibasis.reaktor.tooling.changes.ChangesApi
import dev.shibasis.reaktor.tooling.checks.ChecksApi
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.reaktor.tooling.kernel.KernelApi
import dev.shibasis.reaktor.tooling.kernel.KernelHello
import dev.shibasis.reaktor.tooling.operation.Invocation
import dev.shibasis.reaktor.tooling.operation.OperationId
import dev.shibasis.reaktor.tooling.operation.OperationJson
import dev.shibasis.reaktor.tooling.operation.Outcome
import dev.shibasis.reaktor.tooling.runs.RunsApi
import java.net.InetSocketAddress
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KernelClientAvailabilityTest {
    private val apis: List<Service> = listOf(KernelApi(), AtlasApi(), ChangesApi(), ChecksApi(), RunsApi(), PlansApi(), AgentPresenceApi())
    private val shapes get() = apis.map { it.shape }

    @Test fun everyCurrentOperationIsUsable() = runBlocking<Unit> {
        owner(shapes) { client, _, hello ->
            assertTrue(availability(apis, hello).values.all { it == null })
            assertIs<Outcome.Completed>(client.invoke(Invocation(OperationId("operation.catalog"), caller = CallCaller("fixture"))))
            assertTrue(client.unavailable().isEmpty())
        }
    }

    @Test fun anAbsentPresenceContractDisablesOnlyPresenceAndNeverSendsIt() = runBlocking<Unit> {
        owner(shapes.filterNot { it.id == "reaktor.presence" }) { client, requests, hello ->
            val missing = availability(apis, hello).filterValues { it != null }
            assertEquals(setOf("presence.heartbeat", "presence.read", "presence.state"), missing.keys.map { it.value }.toSet())
            assertEquals(setOf("The workspace owner does not serve reaktor.presence yet"), missing.values.toSet())
            assertIs<Outcome.Completed>(client.invoke(Invocation(OperationId("operation.catalog"), caller = CallCaller("fixture"))))
            val before = requests.get()
            missing.keys.forEach { operation ->
                val failure = assertIs<Outcome.Failed>(client.invoke(Invocation(operation, caller = CallCaller("fixture"))))
                assertEquals("contract-drift", failure.code)
                assertEquals("The workspace owner does not serve reaktor.presence yet. It arrives when the owner restarts into the current build.", failure.message)
            }
            assertEquals(before, requests.get())
            assertEquals(missing.mapKeys { it.key.value }.mapValues { requireNotNull(it.value) }, client.unavailable())
        }
    }

    @Test fun aMissingPlanReadDoesNotDisableOtherPlanOperations() = runBlocking<Unit> {
        val offered = shapes.map { shape ->
            if (shape.id == "reaktor.plan") shape.copy(fingerprint = "without-read", operations = shape.operations.filterNot { it.name == "read" }) else shape
        }
        owner(offered) { client, requests, hello ->
            val reason = "The workspace owner does not offer plan.read yet: read: the server does not offer it"
            assertEquals(mapOf(OperationId("plan.read") to reason), availability(apis, hello).filterValues { it != null })
            assertIs<Outcome.Completed>(client.invoke(Invocation(OperationId("operation.catalog"), caller = CallCaller("fixture"))))
            val before = requests.get()
            val failure = assertIs<Outcome.Failed>(client.invoke(Invocation(OperationId("plan.read"), caller = CallCaller("fixture"))))
            assertEquals("contract-drift", failure.code)
            assertEquals("$reason. It arrives when the owner restarts into the current build.", failure.message)
            assertEquals(before, requests.get())
            assertEquals(mapOf("plan.read" to reason), client.unavailable())
        }
    }

    @Test fun theKernelContractIsStillRequired() = runBlocking<Unit> {
        owner(shapes.filterNot { it.id == KernelApi.Id }) { client, requests, hello ->
            assertFailsWith<ContractDrift> { availability(apis, hello) }
            val failure = assertIs<Outcome.Failed>(client.invoke(Invocation(OperationId("operation.catalog"), caller = CallCaller("fixture"))))
            assertEquals("contract-drift", failure.code)
            assertEquals("The owner does not serve reaktor.kernel", failure.message)
            assertEquals(1, requests.get())
        }
    }

    private suspend fun owner(contracts: List<ContractShape>, block: suspend (KernelClient, AtomicInteger, KernelHello) -> Unit) {
        val root = Files.createTempDirectory("kernel-availability").toFile().canonicalFile
        val directory = root.toPath().resolve("owner")
        Files.createDirectory(directory)
        val incarnation = "11111111-1111-1111-1111-111111111111"
        val hello = KernelHello(root.path, incarnation, contracts)
        val requests = AtomicInteger()
        val api = KernelApi()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            val body = when (exchange.requestURI.path) {
                api.hello.binding.path -> OperationJson.encodeToString(KernelHello.serializer(), hello)
                api.catalog.binding.path -> "[]"
                else -> error("An unavailable operation reached the owner")
            }.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            Files.writeString(directory.resolve("owner.json"), buildJsonObject {
                put("version", 2); put("workspaceRoot", root.path); put("port", server.address.port)
                put("token", "f".repeat(32)); put("processId", ProcessHandle.current().pid())
                put("incarnation", incarnation); put("catalogDigest", "a".repeat(64)); put("hostKind", "service"); put("invocationVersion", 1)
            }.toString())
            FileChannel.open(directory.resolve("owner.lock"), CREATE, WRITE).use { channel ->
                channel.lock().use {
                    AgentWorkspaceConnection.open(root, directory, allowStart = false).use { connection ->
                        block(connection.kernel, requests, hello)
                    }
                }
            }
        } finally {
            server.stop(0)
            root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
        }
    }
}
