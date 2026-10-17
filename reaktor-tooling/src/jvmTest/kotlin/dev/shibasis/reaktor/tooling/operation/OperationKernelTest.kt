package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.mcp.Tool
import dev.shibasis.reaktor.mcp.ToolDefinition
import dev.shibasis.reaktor.mcp.ToolNode
import dev.shibasis.reaktor.mcp.ToolResult
import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.mcp.door.CallLog
import dev.shibasis.reaktor.tooling.mcp.door.DoorApprovals
import dev.shibasis.reaktor.tooling.mcp.door.DoorPolicy
import dev.shibasis.reaktor.tooling.mcp.door.SeatPolicy
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.koin.dsl.koinApplication
import java.nio.file.Files
import kotlin.jvm.JvmInline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Serializable @JvmInline value class NodeRef(val value: String)

@Serializable enum class Detail { Summary, Full }

@Serializable sealed interface Target {
    @Serializable @SerialName("node") data class Node(val ref: NodeRef) : Target
    @Serializable @SerialName("all") data object All : Target
}

@Serializable
@Describe("Show one build node")
data class ShowRequest(
    @Subject @Describe("Declared build node id") val node: NodeRef,
    val detail: Detail = Detail.Summary,
    val tags: List<String> = emptyList(),
    val limits: Map<String, Int> = emptyMap(),
    val note: String?,
    val target: Target = Target.All,
)

@Serializable data class Shown(val node: String, val detail: Detail)

@Serializable data class Write(val path: String)

class OperationKernelTest {
    @Test
    fun schemaFollowsTheSerializer() {
        val schema = operation<ShowRequest, Shown>("build.show", "Show") { Shown(it.node.value, it.detail) }.describe("build")
        val properties = schema.input.getValue("properties").jsonObject
        assertEquals("node", schema.subject)
        assertEquals("Show one build node", schema.input.getValue("description").jsonPrimitive.content)
        assertEquals(JsonPrimitive("string"), properties.getValue("node").jsonObject["type"])
        assertEquals("Declared build node id", properties.getValue("node").jsonObject.getValue("description").jsonPrimitive.content)
        assertEquals(listOf("Summary", "Full"), properties.getValue("detail").jsonObject.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(JsonPrimitive("array"), properties.getValue("tags").jsonObject["type"])
        assertEquals(JsonPrimitive("integer"), properties.getValue("limits").jsonObject.getValue("additionalProperties").jsonObject["type"])
        assertEquals(JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))), properties.getValue("note").jsonObject["type"])
        assertEquals(listOf("node", "note"), schema.input.getValue("required").jsonArray.map { it.jsonPrimitive.content })
        val variants = properties.getValue("target").jsonObject.getValue("oneOf").jsonArray.map { it.jsonObject }
        assertEquals(setOf("node", "all"), variants.map { it.getValue("properties").jsonObject.getValue("type").jsonObject.getValue("const").jsonPrimitive.content }.toSet())
        assertEquals(JsonPrimitive(false), schema.input["additionalProperties"])
    }

    @Test
    fun everySurfaceGoesThroughOneGate() = kernel { kernel, approvals, written ->
        val read = kernel.invoke(Invocation(OperationId("build.show"), buildJsonObject { put("node", "bestbuds:app:check"); put("note", "x") }, CallCaller("cli")))
        assertEquals(JsonPrimitive("bestbuds:app:check"), assertIs<Outcome.Completed>(read).result.jsonObject["node"])

        val request = Invocation(OperationId("code.write"), buildJsonObject { put("path", "a.kt") }, CallCaller("claude"))
        val asked = assertIs<Outcome.ApprovalRequired>(kernel.invoke(request))
        assertEquals(SafetyClass.SourceWrite, asked.effect)
        assertTrue(written.isEmpty())
        approvals.decide(asked.approval, approve = true, by = "terminal")
        assertIs<Outcome.Completed>(kernel.invoke(request))
        assertEquals(listOf("a.kt"), written)
        assertIs<Outcome.ApprovalRequired>(kernel.invoke(request))

        val other = request.copy(arguments = buildJsonObject { put("path", "b.kt") })
        approvals.decide(assertIs<Outcome.ApprovalRequired>(kernel.invoke(other)).approval, approve = false, by = "terminal")
        assertEquals("terminal", assertIs<Outcome.Denied>(kernel.invoke(other)).by)
        assertEquals(listOf("a.kt"), written)
    }

    @Test
    fun invalidArgumentsFailBeforeAnyEffect() = kernel { kernel, _, _ ->
        val failed = kernel.invoke(Invocation(OperationId("build.show"), buildJsonObject { put("nodes", "x") }))
        assertTrue(assertIs<Outcome.Failed>(failed).message.startsWith("Invalid arguments for build.show"))
        assertIs<Outcome.Failed>(kernel.invoke(Invocation(OperationId("build.missing"))))
    }

    @Test
    fun existingToolNodesJoinTheCatalogWithTheirOwnEffect() = kernel { kernel, _, _ ->
        val ids = kernel.catalog().associate { it.id.value to it.effect }
        assertEquals("Snapshot", kernel.describe(OperationId("graph.snapshot"))?.summary)
        assertEquals(SafetyClass.ReadOnly, ids["graph.snapshot"])
        assertEquals(SafetyClass.UnknownRemoteEffect, ids["tool.workspace"])
        val read = kernel.invoke(Invocation(OperationId("graph.snapshot")))
        assertEquals(JsonPrimitive("apps"), assertIs<Outcome.Completed>(read).result)
        assertIs<Outcome.ApprovalRequired>(kernel.invoke(Invocation(OperationId("tool.workspace"))))
    }

    @Test
    fun aRetriedRequestReturnsTheOriginalOutcome() = kernel { kernel, approvals, written ->
        val request = Invocation(OperationId("code.write"), buildJsonObject { put("path", "c.kt") }, CallCaller("cli"), requestId = "r-1")
        approvals.decide(assertIs<Outcome.ApprovalRequired>(kernel.invoke(request.copy(requestId = null))).approval, approve = true, by = "terminal")
        val first = kernel.invoke(request)
        assertEquals(first, kernel.invoke(request))
        assertEquals(listOf("c.kt"), written)
        assertNull(kernel.describe(OperationId("code.absent")))
    }

    @Test
    fun discoveryIsItselfAnOperation() = kernel { kernel, _, _ ->
        val listed = assertIs<Outcome.Completed>(kernel.invoke(Invocation(OperationId("operation.catalog"), buildJsonObject { put("domain", "code") }))).result.jsonArray
        assertEquals(listOf("code.write"), listed.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        val described = assertIs<Outcome.Completed>(kernel.invoke(Invocation(OperationId("operation.describe"), buildJsonObject { put("operation", "build.show") }))).result.jsonObject
        assertEquals("node", described.getValue("subject").jsonPrimitive.content)
        assertEquals("build", described.getValue("provider").jsonPrimitive.content)
    }

    @Test
    fun theEffectCanDependOnTheArguments() = kernel { kernel, _, _ ->
        val read = Invocation(OperationId("provider.call"), buildJsonObject { put("tool", "list") }, CallCaller("codex"))
        assertEquals(JsonPrimitive("list"), assertIs<Outcome.Completed>(kernel.invoke(read)).result)
        val write = read.copy(arguments = buildJsonObject { put("tool", "deploy") })
        assertEquals(SafetyClass.ProductionReversibleWrite, assertIs<Outcome.ApprovalRequired>(kernel.invoke(write)).effect)
        assertEquals(SafetyClass.UnknownRemoteEffect, kernel.catalog().single { it.id.value == "provider.call" }.effect)
    }

    @Test
    fun theSpentApprovalReachesTheOperation() = kernel { kernel, approvals, _ ->
        val request = Invocation(OperationId("source.sign"), buildJsonObject { put("path", "d.kt") }, CallCaller("claude"))
        val asked = assertIs<Outcome.ApprovalRequired>(kernel.invoke(request))
        approvals.decide(asked.approval, approve = true, by = "Hangar")
        assertEquals(JsonPrimitive("${asked.approval} Used Hangar"), assertIs<Outcome.Completed>(kernel.invoke(request)).result)
    }

    @Test
    fun accessShowsWhatEachSeatMayDo() = kernel(DoorPolicy(mapOf("codex" to SeatPolicy(allow = SafetyClass.SourceWrite), "gemini" to SeatPolicy(SafetyClass.ReadOnly, SafetyClass.ReadOnly)))) { kernel, _, _ ->
        val access = kernel.access(listOf("*", "codex", "gemini")).associateBy { it.id.value }
        assertEquals(mapOf("*" to Access.Allowed, "codex" to Access.Allowed, "gemini" to Access.Allowed), access.getValue("build.show").seats)
        assertEquals(mapOf("*" to Access.Allowed, "codex" to Access.Allowed, "gemini" to Access.Refused), access.getValue("code.write").seats)
        assertEquals(Access.Ask, access.getValue("tool.workspace").seats["codex"])
        assertEquals(kernel.catalog().map { it.id }, access.values.map { it.id })
    }

    @Test
    fun aNodesOperationsCanChangeWhileItIsAttached() = runTest {
        val dependencies = koinApplication {}
        val graph = Graph(dispatcher = StandardTestDispatcher(testScheduler), dependencyAdapter = KoinDependencyAdapter(dependencies))
        val offered = mutableListOf<Operation<*, *>>()
        graph.attach(object : OperationNode(graph, "hangar") { override val operations: List<Operation<*, *>> get() = offered.toList() })
        val kernel = OperationKernel(graph, OperationAuthority())
        try {
            assertTrue(kernel.catalog().none { it.provider == "hangar" })
            offered += operation<Write, String>("hangar.read", "Read") { it.path }
            assertEquals(listOf("hangar.read"), kernel.catalog().filter { it.provider == "hangar" }.map { it.id.value })
            assertEquals(JsonPrimitive("e"), assertIs<Outcome.Completed>(kernel.invoke(Invocation(OperationId("hangar.read"), buildJsonObject { put("path", "e") }))).result)
        } finally { graph.close(); dependencies.close() }
    }

    private fun kernel(policy: DoorPolicy = DoorPolicy.Guarded, body: suspend (OperationKernel, DoorApprovals, MutableList<String>) -> Unit) = runTest {
        val directory = Files.createTempDirectory("operation-kernel")
        val dependencies = koinApplication {}
        val graph = Graph(dispatcher = StandardTestDispatcher(testScheduler), dependencyAdapter = KoinDependencyAdapter(dependencies))
        val written = mutableListOf<String>()
        graph.attach(OperationNode(graph, "build", listOf(
            operation<ShowRequest, Shown>("build.show", "Show one build node") { Shown(it.node.value, it.detail) },
            operation<Write, Unit>("code.write", "Write a file", SafetyClass.SourceWrite) { written += it.path },
            operation<Write, String>("source.sign", "Sign a file", SafetyClass.SourceWrite) { "${approval?.id} ${approval?.state} ${approval?.decidedBy}" },
            rawOperation("provider.call", "Call a provider tool", SafetyClass.UnknownRemoteEffect, { JsonObject(emptyMap()) },
                effectFor = { if (it["tool"] == JsonPrimitive("list")) SafetyClass.ReadOnly else SafetyClass.ProductionReversibleWrite }) { it.getValue("tool") },
        )))
        graph.attach(ToolNode(graph, listOf(
            Tool(ToolDefinition("graph_snapshot", "Snapshot", readOnly = true)) { ToolResult(JsonPrimitive("apps")) },
            Tool(ToolDefinition("workspace", "Everything")) { ToolResult(JsonObject(emptyMap())) },
        )))
        val approvals = DoorApprovals(directory.resolve("approvals"))
        try {
            body(OperationKernel(graph, OperationAuthority(policy, approvals, CallLog(directory.resolve("calls.jsonl"), CallCaller()))), approvals, written)
        } finally {
            graph.close(); dependencies.close(); directory.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir")))
        }
    }
}
