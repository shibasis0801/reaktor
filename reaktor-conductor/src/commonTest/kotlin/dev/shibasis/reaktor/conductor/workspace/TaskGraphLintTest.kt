package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.WorkflowContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskGraphLintTest {
    private fun task(id: Int, kind: TaskKind = TaskKind.Agent, write: Boolean = false, after: List<Int> = emptyList(),
        scope: List<String> = listOf(":engine"), acceptance: List<String> = listOf("The sample parses"), review: Boolean = false): BoardTask =
        BoardTask("T$id", if (review) "Review source" else "Task $id", acceptance = acceptance, scope = scope,
            createdBy = BoardActor(BoardActorKind.Agent, "codex"), createdAt = 0, updatedAt = 0, stageChangedAt = 0,
            graph = TaskPlan("G1", kind = kind, dependsOn = after.map { TaskDependency("T$it") },
                seat = if (kind == TaskKind.Agent) TaskSeat(RuntimeKind.Codex, access = if (write) TaskAccess.Write else TaskAccess.Read) else null,
                contract = if (review) WorkflowContract.Decision else WorkflowContract.Text,
                check = if (kind == TaskKind.Check) ":engine:jvmTest" else null))

    private fun warnings(vararg tasks: BoardTask, modules: Map<String, List<String>> = emptyMap()) =
        TaskGraphRules.warnings(tasks.associateBy { it.id }, modules)

    @Test fun aVerifiedWriterAndTerminalCheckNeedNoWarnings() {
        assertTrue(warnings(task(1, write = true), task(2, review = true, after = listOf(1)),
            task(3, TaskKind.Check, after = listOf(1)), task(4, TaskKind.Gate, after = listOf(2, 3))).isEmpty())
    }

    @Test fun verificationMustFollowTheWriterAndBeIndependent() {
        listOf(task(2, TaskKind.Check), task(2, review = true), task(2, write = true, review = true, after = listOf(1))).forEach { verifier ->
            assertTrue(warnings(task(1, write = true), verifier).any {
                it.code == TaskGraphWarningCode.WriterWithoutVerification && it.tasks == listOf("T1")
            })
        }
        assertTrue(warnings(task(1, write = true), task(2, review = true, after = listOf(1))).none {
            it.code == TaskGraphWarningCode.WriterWithoutVerification
        })
    }

    @Test fun overlappingUnorderedWritersWarnAndOrderingOrDistinctScopesClearsIt() {
        val first = task(1, write = true, scope = listOf("modules/engine"))
        val second = task(2, write = true, scope = listOf("modules/engine/src"))
        assertEquals(listOf("T1", "T2"), warnings(first, second).single { it.code == TaskGraphWarningCode.OverlappingWriters }.tasks)
        listOf(task(2, write = true, after = listOf(1), scope = listOf("modules/engine/src")), task(2, write = true, scope = listOf("modules/kernel"))).forEach {
            assertTrue(warnings(first, it).none { warning -> warning.code == TaskGraphWarningCode.OverlappingWriters })
        }
    }

    @Test fun resolvedModuleAliasesMeetFileScopesAndLargeGraphsGroupOverlaps() {
        val modules = mapOf("T1" to listOf("ws/engine"), "T2" to listOf("ws/engine"))
        assertTrue(warnings(task(1, write = true), task(2, write = true, scope = listOf("modules/engine/src/Foo.kt")), modules = modules)
            .any { it.code == TaskGraphWarningCode.OverlappingWriters })
        assertTrue(warnings(task(1, write = true, scope = listOf("modules/engine/src/A.kt")),
            task(2, write = true, scope = listOf("modules/engine/src/B.kt")), modules = modules)
            .none { it.code == TaskGraphWarningCode.OverlappingWriters })
        val large = warnings(*(1..200).map { task(it, write = true) }.toTypedArray())
        assertEquals(199, large.count { it.code == TaskGraphWarningCode.OverlappingWriters })
        assertEquals(200, large.first { it.code == TaskGraphWarningCode.OverlappingWriters }.tasks.size)
    }

    @Test fun aCheckOnAnotherBranchDoesNotVerifyAnUncheckedTerminal() {
        val warnings = warnings(task(1), task(2, TaskKind.Check, after = listOf(1)), task(3, after = listOf(1)))
        assertEquals(listOf("T3"), warnings.single { it.code == TaskGraphWarningCode.TerminalWithoutCheck }.tasks)
    }

    @Test fun moduleCountsUseResolvedDistinctModulesAndSuggestASplit() {
        val modules = mapOf("T1" to listOf("d", "c", "b", "a", "a"), "T2" to listOf("a", "b", "c"))
        val warning = warnings(task(1), task(2), modules = modules).single { it.code == TaskGraphWarningCode.BroadScope }
        assertEquals(listOf("T1"), warning.tasks)
        assertEquals(listOf("a", "b", "c", "d"), warning.modules)
        assertTrue("Split" in warning.message)
        assertTrue(warnings(task(1)).none { it.code == TaskGraphWarningCode.BroadScope })
    }

    @Test fun acceptanceWarnsForVagueLinesAndNamesObservableChecksWithoutAnAgentCall() {
        listOf(emptyList(), listOf("Make it good"), listOf("Tests pass", "Improve quality")).forEach { acceptance ->
            assertTrue(warnings(task(1, acceptance = acceptance)).any { it.code == TaskGraphWarningCode.UnclearAcceptance })
        }
        listOf("Tests pass", "The output contains the expected timestamp", "The Run button remains enabled", ":engine:jvmTest").forEach { acceptance ->
            assertTrue(warnings(task(1, acceptance = listOf(acceptance))).none { it.code == TaskGraphWarningCode.UnclearAcceptance })
        }
        assertTrue(warnings(task(1, TaskKind.Check, acceptance = emptyList())).none { it.code == TaskGraphWarningCode.UnclearAcceptance })
    }


    @Test fun patchClassificationSeparatesLayoutFromEveryMaterialOperation() {
        assertTrue(!TaskGraphRules.material(TaskGraphOp.Update("T1", layout = TaskLayout(1.0, 2.0))))
        val seat = requireNotNull(task(1).graph?.seat)
        listOf(TaskGraphOp.Update("T1", title = "Changed"), TaskGraphOp.Update("T1", excludedContext = emptyList()),
            TaskGraphOp.Add("Added"), TaskGraphOp.Note("A note"), TaskGraphOp.Envelope(TaskEnvelope()),
            TaskGraphOp.Seat("T1", seat), TaskGraphOp.Connect("T1", "T2"), TaskGraphOp.Disconnect("T1", "T2"),
            TaskGraphOp.Split("T1", listOf(TaskGraphOp.Add("A"), TaskGraphOp.Add("B"))), TaskGraphOp.Remove("T1"),
            TaskGraphOp.Retry("T1"), TaskGraphOp.Restore("T1", emptyList(), seat, TaskState.Pending)).forEach {
            assertTrue(TaskGraphRules.material(it), it.toString())
        }
    }

    @Test fun affectedWorkIncludesTransitiveBranchesButExcludesLayoutNoOpsAndControls() {
        val first = task(1)
        val tasks = listOf(first, task(2, after = listOf(1)), task(3, after = listOf(1)),
            task(4, after = listOf(2, 3)), task(5)).associateBy { it.id }
        fun ids(vararg ops: TaskGraphOp) = TaskGraphRules.affected(ops.toList(), tasks).map { it.id }
        assertEquals(listOf("T1", "T2", "T3", "T4"), ids(TaskGraphOp.Update("T1", acceptance = listOf("New check passes"))))
        assertEquals(listOf("T2", "T4"), ids(TaskGraphOp.Disconnect("T1", "T2")))
        assertEquals(listOf("T2", "T4"), ids(TaskGraphOp.Connect("T5", "T2")))
        assertEquals(listOf("T1", "T2", "T3", "T4"), ids(TaskGraphOp.Seat("T1", TaskSeat(RuntimeKind.ClaudeCode))))
        assertEquals(emptyList(), ids(TaskGraphOp.Update("T1", title = first.title, intent = first.intent,
            acceptance = first.acceptance, scope = first.scope, layout = TaskLayout(3.0, 4.0)),
            TaskGraphOp.Seat("T1", requireNotNull(first.graph?.seat)), TaskGraphOp.Connect("T1", "T2"),
            TaskGraphOp.Disconnect("T5", "T2"), TaskGraphOp.Add("New task"), TaskGraphOp.Note("Context"),
            TaskGraphOp.Envelope(TaskEnvelope()), TaskGraphOp.Retry("T1")))
        val cyclic = tasks + ("T1" to task(1, after = listOf(4)))
        assertEquals(listOf("T1", "T2", "T3", "T4"),
            TaskGraphRules.affected(listOf(TaskGraphOp.Remove("T1")), cyclic).map { it.id })
    }

    @Test fun notesAreIgnoredAndWarningsAreDeterministicEvenForMalformedCycles() {
        val first = task(1, write = true, after = listOf(2))
        val second = task(2, after = listOf(1))
        val note = task(3, TaskKind.Note, acceptance = emptyList())
        assertEquals(warnings(first, second), warnings(second, note, first))
    }
}
