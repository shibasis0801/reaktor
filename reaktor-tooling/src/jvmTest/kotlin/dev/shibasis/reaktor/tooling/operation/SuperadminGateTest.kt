package dev.shibasis.reaktor.tooling.operation

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import dev.shibasis.reaktor.tooling.CallCaller
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.reaktor.tooling.mcp.door.CallLog
import dev.shibasis.reaktor.tooling.mcp.door.DoorApprovals
import dev.shibasis.reaktor.tooling.mcp.door.DoorPolicy
import dev.shibasis.reaktor.tooling.mcp.door.SeatPolicy
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.koin.dsl.koinApplication
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SuperadminGateTest {
    private val administrator = SeatPolicy(superadmin = true)
    private val policy = DoorPolicy(mapOf("*" to SeatPolicy.Guarded, "codex" to administrator, "claude-code" to administrator))
    private fun invocation(seat: String, plan: String = "reviewed") = Invocation(OperationId("ship.apply"),
        buildJsonObject { put("plan", plan); put("environment", "dev") }, CallCaller(seat))

    @Test fun bothExplicitSuperadminSeatsShipWithoutAPromptAndKeepReceipts() = fixture(policy) { kernel, approvals, calls, log ->
        for (seat in listOf("codex", "claude-code")) {
            assertIs<Outcome.Completed>(kernel.invoke(invocation(seat)))
            assertEquals(Access.Allowed, kernel.access(listOf(seat)).single { it.id.value == "ship.apply" }.seats[seat])
        }
        assertEquals(2, calls.size)
        assertTrue(calls.all { it.superadmin && it.approval == null })
        assertTrue(approvals.pending().isEmpty())
        assertTrue(log.readText().contains("superadmin policy"))
        assertIs<Outcome.Failed>(kernel.invoke(invocation("codex", "stale")))
        assertEquals(2, calls.size)
    }

    @Test fun guardedAndOrdinaryFullAccessSeatsKeepTheirApprovalPath() = fixture(
        policy.copy(seats = policy.seats + ("ordinary" to SeatPolicy()))) { kernel, approvals, calls, _ ->
        for (seat in listOf("spawned", "ordinary")) assertIs<Outcome.ApprovalRequired>(kernel.invoke(invocation(seat)))
        assertEquals(2, approvals.pending().size)
        assertTrue(calls.isEmpty())
    }

    @Test fun superadminCannotExceedItsEffectCeilingOrAnEnforcedModelDenial() = fixture(
        policy.copy(seats = policy.seats + ("limited" to SeatPolicy(SafetyClass.ReadOnly, SafetyClass.ReadOnly, superadmin = true))),
        OperationModel { caller, _, _ -> ModelVerdict(caller.seat != "codex", enforced = true, rule = "fixture grant", record = JsonObject(emptyMap())) }
    ) { kernel, approvals, calls, _ ->
        assertIs<Outcome.Refused>(kernel.invoke(invocation("limited")))
        assertIs<Outcome.Refused>(kernel.invoke(invocation("codex")))
        assertTrue(calls.isEmpty())
        assertTrue(approvals.pending().isEmpty())
    }

    @Test fun workspacePolicyCannotGrantMachineSuperadminAndCanNarrowIt() {
        val root = Files.createTempDirectory("superadmin-policy").toFile()
        try {
            val home = File(root, "home").apply { mkdirs() }
            val workspace = File(root, "workspace").apply { mkdirs() }
            fun write(folder: File, enabled: Boolean) {
                File(folder, DoorPolicy.PATH).apply { parentFile.mkdirs() }.writeText(
                    """{"seats":{"codex":{"superadmin":$enabled}}}""")
            }
            write(workspace, true)
            assertFalse(DoorPolicy.load(workspace, home).seat("codex").superadmin)
            write(home, false)
            assertFalse(DoorPolicy.load(workspace, home).seat("codex").superadmin)
            write(home, true)
            assertTrue(DoorPolicy.load(workspace, home).seat("codex").superadmin)
            write(workspace, false)
            assertFalse(DoorPolicy.load(workspace, home).seat("codex").superadmin)
            File(workspace, DoorPolicy.PATH).delete()
            assertTrue(DoorPolicy.load(workspace, home).seat("codex").superadmin)
        } finally {
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    private fun fixture(policy: DoorPolicy, model: OperationModel? = null,
        body: suspend (OperationKernel, DoorApprovals, List<OperationCall>, File) -> Unit) = runTest {
        val directory = Files.createTempDirectory("superadmin-gate")
        val dependencies = koinApplication {}
        val graph = Graph(dispatcher = StandardTestDispatcher(testScheduler), dependencyAdapter = KoinDependencyAdapter(dependencies))
        val calls = mutableListOf<OperationCall>()
        graph.attach(OperationNode(graph, "workspace", listOf(Operation(OperationId("ship.apply"), "Ship fixture",
            SafetyClass.ProductionReversibleWrite, JsonObject.serializer(), JsonObject.serializer(),
            effectFor = { arguments, _ ->
                require(arguments["plan"] == JsonPrimitive("reviewed") && arguments["environment"] == JsonPrimitive("dev"))
                SafetyClass.ProductionReversibleWrite
            }, confirmationPhraseFor = { "ship fixture" }) { arguments -> calls += this; arguments })))
        val approvals = DoorApprovals(directory.resolve("approvals"))
        val log = directory.resolve("calls.jsonl").toFile()
        try {
            body(OperationKernel(graph, OperationAuthority(policy, approvals, CallLog(log.toPath(), CallCaller()), model = model)), approvals, calls, log)
        } finally {
            graph.close()
            dependencies.close()
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
