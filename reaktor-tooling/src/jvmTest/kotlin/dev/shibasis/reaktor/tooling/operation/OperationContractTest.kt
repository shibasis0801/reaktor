package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.graph.intercept
import dev.shibasis.reaktor.graph.serve
import dev.shibasis.reaktor.service.ServiceSpan
import dev.shibasis.reaktor.service.SpanSink
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.contract.Spans
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.exchange
import dev.shibasis.reaktor.tooling.changes.ChangesApi
import dev.shibasis.reaktor.tooling.changes.ChangeApplyRequest
import dev.shibasis.reaktor.tooling.changes.ChangeApplied
import kotlinx.serialization.json.JsonObject
import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.reaktor.tooling.mcp.door.DoorApprovals
import dev.shibasis.reaktor.tooling.mcp.door.DoorPolicy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.dsl.koinApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OperationContractTest {
    private open class CodeApi : Service("reaktor.code") {
        open val write by call<Write, String>(effect = Effect.SourceWrite, summary = "Write a file")
    }

    @Test
    fun anAppliedChangeReturnsItsReceiptWithoutAnotherApproval() = runTest {
        val directory = Files.createTempDirectory("change-contract")
        val dependencies = koinApplication {}
        val graph = Graph(dispatcher = StandardTestDispatcher(testScheduler), dependencyAdapter = KoinDependencyAdapter(dependencies))
        val approvals = DoorApprovals(directory.resolve("approvals"))
        val kernel = OperationKernel(graph, OperationAuthority(DoorPolicy.Guarded, approvals))
        graph.intercept(kernel.gate)
        val receipts = mutableSetOf<String>()
        var writes = 0
        val server = object : ChangesApi(), OperationGate.Classifier {
            override val apply = super.apply.handle { request ->
                val replayed = request.plan in receipts
                if (!replayed) {
                    writes++
                    receipts += request.plan
                }
                ChangeApplied(request.plan, "receipt-" + request.plan, listOf("file.kt"), replayed)
            }
            override fun effect(operation: String, arguments: JsonObject, caller: CallCaller): SafetyClass =
                if (OperationJson.decodeFromJsonElement(ChangeApplyRequest.serializer(), arguments).plan in receipts) SafetyClass.ReadOnly
                else SafetyClass.SourceWrite
        }
        graph.serve(server)
        kernel.connectContracts()
        try {
            val request = Invocation(OperationId("change.apply"), buildJsonObject { put("plan", "plan-1") }, CallCaller("codex"), "fresh")
            val approval = assertIs<Outcome.ApprovalRequired>(kernel.invoke(request))
            assertEquals(SafetyClass.SourceWrite, approval.effect)
            assertEquals(0, writes)
            approvals.decide(approval.approval, approve = true, by = "Hangar")
            assertIs<Outcome.Completed>(kernel.invoke(request))
            val replay = assertIs<Outcome.Completed>(kernel.invoke(request.copy(requestId = "receipt-observer")))
            assertTrue(OperationJson.decodeFromJsonElement(ChangeApplied.serializer(), replay.result).replayed)
            assertEquals(1, writes)
            assertEquals(SafetyClass.SourceWrite, kernel.describe(OperationId("change.apply"))?.effect)
            assertIs<Outcome.ApprovalRequired>(kernel.invoke(request.copy(requestId = "other", arguments = buildJsonObject { put("plan", "plan-2") })))
            assertEquals(1, writes)
        } finally {
            graph.close()
            dependencies.close()
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test
    fun contractProjectionPreservesDiscoveryAndUsesOneGuardedGate() = runTest {
        val directory = Files.createTempDirectory("operation-contract")
        val dependencies = koinApplication {}
        val graph = Graph(dispatcher = StandardTestDispatcher(testScheduler), dependencyAdapter = KoinDependencyAdapter(dependencies))
        val approvals = DoorApprovals(directory.resolve("approvals"))
        val legacy = operation<Write, String>("code.write", "Write a file", SafetyClass.SourceWrite) { error("Legacy handler was selected") }
        graph.attach(OperationNode(graph, "code", listOf(legacy)))
        val kernel = OperationKernel(graph, OperationAuthority(DoorPolicy.Guarded, approvals))
        val spans = mutableListOf<ServiceSpan>()
        graph.intercept(Spans("reaktor-kernel", SpanSink { spans += it }), kernel.gate)
        val written = mutableListOf<String>()
        val server = object : CodeApi() {
            override val write = super.write.handle { request ->
                val call = assertNotNull(exchange().attributes[OperationGate.CallAttribute] as? OperationCall)
                assertEquals("codex", call.invocation.caller.seat)
                assertNotNull(call.approval)
                written += request.path
                request.path
            }
        }
        graph.serve(server)
        kernel.connectContracts()
        try {
            assertEquals(legacy.describe("code"), kernel.describe(OperationId("code.write")))
            assertEquals(1, kernel.catalog().count { it.id == OperationId("code.write") })
            val request = Invocation(OperationId("code.write"), buildJsonObject { put("path", "contract.kt") }, CallCaller("codex"), "contract-1")
            val asked = assertIs<Outcome.ApprovalRequired>(kernel.invoke(request))
            assertTrue(written.isEmpty())
            approvals.decide(asked.approval, approve = true, by = "Hangar")
            val completed = assertIs<Outcome.Completed>(kernel.invoke(request))
            assertEquals(JsonPrimitive("contract.kt"), completed.result)
            val span = spans.last { it.phase == ServiceExecutionPhase.SERVER && it.status == 200 }
            assertTrue(span.node.isNotBlank())
            assertTrue(span.port.isNotBlank())
            assertTrue(span.edge.isNotBlank())
            assertTrue(span.activation.isNotBlank())
            assertEquals("reaktor-kernel", span.island)
            assertEquals(completed, kernel.invoke(request))
            assertEquals(listOf("contract.kt"), written)
            val invalid = kernel.invoke(request.copy(requestId = "invalid", arguments = buildJsonObject {
                put("path", "other.kt")
                put("caller", "hangar")
            }))
            assertTrue(assertIs<Outcome.Failed>(invalid).message.startsWith("Invalid arguments for code.write"))
            assertEquals(listOf("contract.kt"), written)
        } finally {
            graph.close()
            dependencies.close()
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}

