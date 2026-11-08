package dev.shibasis.reaktor.conductor.workspace

import com.sun.net.httpserver.HttpServer
import dev.shibasis.reaktor.service.contract.ContractDrift
import dev.shibasis.reaktor.service.contract.ContractShape
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.atlas.AtlasApi
import dev.shibasis.reaktor.tooling.changes.ChangesApi
import dev.shibasis.reaktor.tooling.checks.ChecksApi
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.reaktor.tooling.kernel.KernelApi
import dev.shibasis.reaktor.tooling.kernel.KernelHello
import dev.shibasis.reaktor.tooling.kernel.KernelInvocation
import dev.shibasis.reaktor.tooling.operation.Invocation
import dev.shibasis.reaktor.tooling.operation.OperationId
import dev.shibasis.reaktor.tooling.operation.OperationJson
import dev.shibasis.reaktor.tooling.operation.Outcome
import dev.shibasis.reaktor.tooling.runs.RunsApi
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.File
import java.net.InetSocketAddress
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KernelClientAvailabilityTest {
    private val apis: List<Service> = listOf(KernelApi(), AtlasApi(), ChangesApi(), ChecksApi(), RunsApi(), PlansApi(), AgentPresenceApi(), KeepWorkingApi())
    private val shapes get() = apis.map { it.shape }

    @Test fun attachedConnectionsReleaseTheirTransportsWithoutClosingTheSharedClient() {
        val output = Files.createTempFile("kernel-client-lifecycle", ".log")
        var process: Process? = null
        try {
            val classpath = buildList {
                addAll(System.getProperty("java.class.path").split(File.pathSeparator))
                var loader: ClassLoader? = Thread.currentThread().contextClassLoader
                while (loader != null) {
                    if (loader is URLClassLoader) addAll(loader.urLs.map { java.nio.file.Path.of(it.toURI()).toString() })
                    loader = loader.parent
                }
            }.distinct().joinToString(File.pathSeparator)
            val child = ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").path, "-Xmx256m",
                "-cp", classpath, KernelClientAvailabilityTest::class.java.name, "transport-lifecycle",
            ).redirectErrorStream(true).redirectOutput(output.toFile()).start()
            process = child
            child.outputStream.close()
            assertTrue(child.waitFor(10, TimeUnit.SECONDS), "Attached clients retained JVM resources: ${Files.readString(output).take(16000)}")
            assertEquals(0, child.exitValue(), Files.readString(output).take(16000))
        } finally {
            process?.takeIf { it.isAlive }?.let { child ->
                child.destroyForcibly()
                child.waitFor(5, TimeUnit.SECONDS)
            }
            Files.deleteIfExists(output)
        }
    }

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

    @Test fun keepWorkingCallsUseTheirDomainInsteadOfTheRejectedGenericRoute() = runBlocking<Unit> {
        owner(shapes) { client, requests, _ ->
            val caller = CallCaller("fixture")
            val configured = assertIs<Outcome.Completed>(client.invoke(Invocation(OperationId("seat.keep-working"),
                buildJsonObject { put("plan", "codex:shared-plan") }, caller)))
            val status = OperationJson.decodeFromJsonElement(KeepWorkingStatus.serializer(), configured.result)
            assertEquals("codex:shared-plan", status.seat?.plan)
            val read = assertIs<Outcome.Completed>(client.invoke(Invocation(OperationId("seat.keep-working-status"), caller = caller)))
            assertEquals(status, OperationJson.decodeFromJsonElement(KeepWorkingStatus.serializer(), read.result))
            val stopped = assertIs<Outcome.Completed>(client.invoke(Invocation(OperationId("seat.stop"),
                buildJsonObject { put("latestInput", "Finish the shared plan") }, caller)))
            val decision = OperationJson.decodeFromJsonElement(KeepWorkingResult.serializer(), stopped.result)
            assertTrue(decision.block)
            assertEquals("Continue codex:shared-plan: Finish the shared plan", decision.reason)
            assertEquals(status.seat, decision.seat)
            val generic = OperationJson.encodeToJsonElement(KernelInvocation.serializer(),
                KernelInvocation(OperationId("seat.stop"))).jsonObject
            val refused = assertIs<Outcome.Refused>(client.invoke(Invocation(OperationId("kernel.invoke"), generic, caller)))
            assertEquals("seat.stop is served by its domain contract", refused.reason)
            assertEquals(5, requests.get())
        }
    }

    @Test fun anAbsentKeepWorkingContractDisablesOnlyContinuationAndNeverSendsIt() = runBlocking<Unit> {
        owner(shapes.filterNot { it.id == "reaktor.seat" }) { client, requests, hello ->
            val missing = availability(apis, hello).filterValues { it != null }
            assertEquals(setOf("seat.keep-working", "seat.keep-working-status", "seat.stop"), missing.keys.map { it.value }.toSet())
            val plans = assertIs<Outcome.Completed>(client.invoke(Invocation(OperationId("plan.list"), caller = CallCaller("fixture"))))
            assertEquals("""{"plans":[]}""", plans.result.toString())
            val before = requests.get()
            missing.keys.forEach { operation ->
                val failure = assertIs<Outcome.Failed>(client.invoke(Invocation(operation, caller = CallCaller("fixture"))))
                assertEquals("contract-drift", failure.code)
                assertTrue(failure.message.contains("The workspace owner does not serve reaktor.seat yet"))
            }
            assertEquals(before, requests.get())
            assertEquals(missing.mapKeys { it.key.value }.mapValues { requireNotNull(it.value) }, client.unavailable())
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

    companion object {
        @JvmStatic fun main(arguments: Array<String>) = runBlocking<Unit> {
            require(arguments.contentEquals(arrayOf("transport-lifecycle")))
            val fixture = KernelClientAvailabilityTest()
            val shared = dev.shibasis.reaktor.io.network.http
            try {
                fixture.owner(fixture.shapes) { _, _, hello ->
                    val root = File(hello.workspaceRoot)
                    val directory = root.toPath().resolve("owner")
                    val descriptor = OperationJson.parseToJsonElement(Files.readString(directory.resolve("owner.json"))).jsonObject
                    val url = "http://127.0.0.1:${descriptor.getValue("port").jsonPrimitive.content}${KernelApi().catalog.binding.path}"
                    assertEquals("[]", shared.get(url).bodyAsText())
                    repeat(2) {
                        AgentWorkspaceConnection.open(root, directory, allowStart = false).use { connection ->
                            assertIs<Outcome.Completed>(connection.kernel.invoke(Invocation(OperationId("operation.catalog"), caller = CallCaller("fixture"))))
                        }
                    }
                    assertEquals("[]", shared.get(url).bodyAsText())
                }
            } finally {
                shared.close()
            }
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
        val seatApi = KeepWorkingApi()
        val plansApi = PlansApi()
        var seat: KeepWorkingSeat? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            val input = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            var status = 200
            val body = when (exchange.requestURI.path) {
                api.hello.binding.path -> OperationJson.encodeToString(KernelHello.serializer(), hello)
                api.catalog.binding.path -> "[]"
                plansApi.list.binding.path -> """{"plans":[]}"""
                seatApi.keepWorking.binding.path -> {
                    val update = OperationJson.decodeFromString(KeepWorkingUpdate.serializer(), input)
                    seat = KeepWorkingSeat("fixture", update.plan, update.paused)
                    OperationJson.encodeToString(KeepWorkingStatus.serializer(), KeepWorkingStatus(seat))
                }
                seatApi.keepWorkingStatus.binding.path -> {
                    OperationJson.decodeFromString(KeepWorkingRead.serializer(), input)
                    OperationJson.encodeToString(KeepWorkingStatus.serializer(), KeepWorkingStatus(seat))
                }
                seatApi.stop.binding.path -> {
                    val stop = OperationJson.decodeFromString(KeepWorkingStop.serializer(), input)
                    OperationJson.encodeToString(KeepWorkingResult.serializer(), KeepWorkingResult(true, "Continue ${seat?.plan}: ${stop.latestInput}", seat))
                }
                api.invoke.binding.path -> {
                    val invocation = OperationJson.decodeFromString(KernelInvocation.serializer(), input)
                    status = 409
                    buildJsonObject {
                        put("error", "Use the domain contract")
                        put("problem", OperationJson.encodeToJsonElement(Outcome.serializer(),
                            Outcome.Refused(SafetyClass.ReadOnly, "${invocation.operation} is served by its domain contract")))
                    }.toString()
                }
                else -> error("An unavailable operation reached the owner")
            }.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, body.size.toLong())
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
