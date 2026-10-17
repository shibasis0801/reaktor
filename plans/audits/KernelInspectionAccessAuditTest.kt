package ai.bestbuds.reaktor.kernel

import ai.bestbuds.reaktor.workbench.operations.*
import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.conductor.store.FileThreadStore
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.UUID
import kotlin.test.*

class KernelInspectionAccessAuditTest {
    @Test fun unauthenticatedInspectionAudit() {
        val root = Files.createTempDirectory("kernel-inspection-audit-").toRealPath()
        val state = root.resolve("owner-state")
        val thread = UUID.randomUUID().toString()
        val task = OperationalTask(id = "audit/check", title = "Audit", summary = "Audit", domain = OperationalDomain.Testing,
            subpane = "checks", provider = "fixture", lifecycle = "verify", effect = TaskEffect.LocalArtifactWrite,
            command = listOf("audit-command-marker"), provenance = "fixture")
        val plane = OperationalControlPlane(BestBudsCatalog("audit", root.toFile(), listOf("local"), emptyList(), listOf(task), emptyList()),
            object : OperationExecutor {
                override fun start(spec: OperationExecutionSpec, onEvent: (OperationExecutionEvent) -> Unit): OperationExecutionHandle = error("Audit never executes")
            }, InMemoryControlPlaneStore())
        try {
            FileThreadStore.open(state.resolve("threads/$thread.json")).use { store ->
                store.checkpoint(ThreadDocument(ThreadId(thread), "Audit", events = listOf(
                    ThreadEvent(EventId("audit-event"), Author.Human(), EventKind.Note, "audit-transcript-marker"))))
            }
            ReaktorKernel(plane).use { kernel ->
                kernel.openAgentWorkspace(directory = state, runtimes = emptyMap()).use {
                    val server = kernel.mcp.startEngineering(0)
                    HttpClient.newHttpClient().use { http ->
                        fun inspect(operation: String, arguments: JsonObject = buildJsonObject {}): JsonObject {
                            val request = buildJsonObject {
                                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                                putJsonObject("params") {
                                    put("name", "workspace")
                                    putJsonObject("arguments") {
                                        put("workspaceRoot", root.toString()); put("operation", operation); put("arguments", arguments)
                                    }
                                }
                            }
                            val response = http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port()}/mcp"))
                                .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build(), HttpResponse.BodyHandlers.ofString())
                            assertEquals(200, response.statusCode())
                            val result = Json.parseToJsonElement(response.body()).jsonObject.getValue("result").jsonObject
                            assertFalse(result["isError"]?.jsonPrimitive?.booleanOrNull == true)
                            return result.getValue("structuredContent").jsonObject
                        }
                        val catalog = inspect("inspect.catalog", buildJsonObject { put("query", "agent_"); put("limit", 50) })
                        val names = catalog.getValue("operations").jsonArray.map { it.jsonObject.getValue("operation").jsonPrimitive.content }
                        assertTrue("agent_transcript" in names)
                        assertTrue("agent_check_catalog" in names)
                        val transcript = inspect("inspect.call", buildJsonObject {
                            put("operation", "agent_transcript"); putJsonObject("arguments") { put("threadId", thread) }
                        })
                        assertEquals(1, transcript.getValue("events").jsonArray.size)
                        assertTrue(transcript.toString().contains("audit-transcript-marker"))
                        val checks = inspect("inspect.call", buildJsonObject { put("operation", "agent_check_catalog"); putJsonObject("arguments") {} })
                        assertEquals(1, checks.getValue("tasks").jsonArray.size)
                        assertTrue(checks.toString().contains("audit-command-marker"))
                        println("Unauthenticated inspection audit: transcript events=1; unredacted check tasks=1")
                    }
                }
            }
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }
}
