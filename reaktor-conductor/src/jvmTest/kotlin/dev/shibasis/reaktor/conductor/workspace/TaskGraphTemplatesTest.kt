package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class TaskGraphTemplatesTest {
    private fun fixture(body: (AgentRunbooks, TaskSeatResolver, java.nio.file.Path) -> Unit) {
        val folder = Files.createTempDirectory("graph-templates")
        try {
            val profiles = AgentProfileStore(folder.resolve("profiles"))
            val resolver = TaskSeatResolver(profiles::read)
            body(AgentRunbooks(folder.resolve("runbooks")) { resolver.resolve(it).seat }, resolver, folder)
        } finally { folder.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun fivePatternsBindProfilesAndProduceTheSameDefinitionsFromEitherProvider() = fixture { books, resolver, folder ->
        assertEquals(5, books.templates().size)
        books.templates().forEachIndexed { index, template ->
            val draft = template.draft.copy(tasks = template.draft.tasks.map { task ->
                task.copy(scope = listOf("Source.kt"), check = if (task.kind == TaskKind.Check) "unit" else task.check,
                    alternatives = task.alternatives?.copy(check = "unit"))
            })
            val slots = TaskGraphTemplates.slots(draft)
            val bound = TaskGraphTemplates.bind(draft, slots.mapValues { (_, seat) -> seat.copy(model = "fixture-model", effort = "high") })
            assertTrue(TaskGraphTemplates.slots(bound).values.all { it.model == "fixture-model" && it.effort == "high" })
            assertFailsWith<IllegalArgumentException> { TaskGraphTemplates.bind(draft, mapOf("missing" to TaskSeat(RuntimeKind.Codex))) }
            val definitions = listOf("codex", "claude").map { provider ->
                val board = AgentBoardStore(folder.resolve("board-$index-$provider"), resolveSeat = resolver::resolve)
                val view = board.propose(bound, BoardActor(BoardActorKind.Agent, provider, "$provider:fixture"))
                assertTrue(view.tasks.all { it.graph?.attempt == 0 && it.graph?.outcome == null && it.graph?.runId == null })
                TaskGraphTemplates.definitions(view)
            }
            assertEquals(definitions[0], definitions[1])
        }
    }

    @Test fun runbookConversionPreservesConditionalBranchJoinsAndProviderInstructions() {
        val original = WorkflowRunbooks.reviewRepair(RuntimeKind.ClaudeCode, RuntimeKind.Codex, "unit", listOf("graph:parser"))
        val draft = TaskGraphTemplates.convert(original)
        val byKey = draft.tasks.associateBy { it.key }
        original.stages.forEachIndexed { index, stage ->
            val task = byKey.getValue("stage-${index + 1}")
            assertEquals(stage.instruction, task.intent.substringAfterLast("\n\n"))
            assertEquals(stage.contract, task.contract)
            assertEquals(stage.checkId, task.check)
            val edges = original.edges.filter { it.to == stage.id }
            assertEquals(edges.map { TaskCondition.valueOf(it.whenResult.name) }, task.dependsOn.map { it.condition })
            assertTrue(task.dependsOn.none { it.required }, "Runbook joins use alternatives after all predecessors settle")
        }
        assertEquals(RuntimeKind.ClaudeCode, draft.tasks.first().seat?.agent)
        assertTrue(original.participants.first().instructions in draft.tasks.first().intent)
        assertTrue("graph:parser" in draft.goal)
        assertEquals(WorkflowContract.Decision, draft.tasks.single { it.title == "Independent review" }.contract)
    }

    @Test fun unsupportedRunbookSettingsAreRefusedInsteadOfDropped() {
        val original = WorkflowRunbooks.reviewRepair(RuntimeKind.Codex, RuntimeKind.ClaudeCode)
        val agent = original.participants.first()
        listOf(
            original.copy(participants = listOf(agent.copy(harnessArgs = listOf("--custom"))) + original.participants.drop(1)),
            original.copy(participants = listOf(agent.copy(budget = agent.budget.copy(maxCostUsd = 2.0))) + original.participants.drop(1)),
            original.copy(participants = listOf(agent.copy(tools = agent.tools.copy(deny = listOf("shell")))) + original.participants.drop(1)),
            original.copy(stages = original.stages.mapIndexed { index, stage -> if (index == 0) stage.copy(candidateId = "pinned") else stage }),
        ).forEach { assertFailsWith<IllegalArgumentException> { TaskGraphTemplates.convert(it) } }
    }

    @Test fun savedDefinitionsRoundTripPrivatelyWithRevisionChecksAndNoAttemptEvidence() = fixture { books, resolver, folder ->
        val board = AgentBoardStore(folder.resolve("board"), resolveSeat = resolver::resolve)
        val draft = TaskGraphTemplates.builtIns.first().draft.copy(tasks = TaskGraphTemplates.builtIns.first().draft.tasks.map {
            it.copy(scope = listOf("Source.kt"), check = if (it.kind == TaskKind.Check) "unit" else it.check)
        })
        val view = board.propose(draft, BoardActor(BoardActorKind.Person, "owner"))
        val writer = view.tasks.first()
        board.advance(view.graph.id, writer.id) { task, plan -> task.copy(graph = plan.copy(attempt = 3, state = TaskState.Succeeded,
            runId = "old-run", worktreeId = "old-tree", outcome = TaskOutcome(summary = "Old result", attempt = 3, candidateId = "old-candidate"))) }
        val saved = books.saveTemplate("parser", TaskGraphTemplates.definitions(board.view(view.graph.id)))
        assertEquals(saved, books.template("parser"))
        assertEquals(saved, AgentRunbooks(folder.resolve("runbooks")) { resolver.resolve(it).seat }.template("parser"))
        val text = Files.readString(folder.resolve("runbooks/templates/parser.json"))
        assertTrue(listOf("old-run", "old-tree", "old-candidate", "Old result").none { it in text })
        assertEquals(setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(folder.resolve("runbooks/templates/parser.json")))
        val next = saved.draft.copy(goal = "Changed goal")
        assertFailsWith<IllegalArgumentException> { books.saveTemplate("parser", next) }
        val updated = books.saveTemplate("parser", next, saved.revision)
        assertNotEquals(saved.revision, updated.revision)
        assertFailsWith<IllegalArgumentException> { books.saveTemplate("parser", next.copy(goal = "Stale overwrite"), saved.revision) }
        assertFailsWith<IllegalArgumentException> { books.saveTemplate("../escape", draft) }
        assertFailsWith<IllegalArgumentException> { books.saveTemplate("builtin-council", draft) }
    }

    @Test fun templateOrderingRefusesCyclesAndRetainsDefinitionsFromHiddenAlternativeBranches() = fixture { _, resolver, folder ->
        val a = TaskGraphOp.Add("First", "a", seat = TaskSeat(RuntimeKind.Codex))
        val b = TaskGraphOp.Add("Second", "b", seat = TaskSeat(RuntimeKind.ClaudeCode), dependsOn = listOf(TaskDependency("a")))
        assertEquals(listOf(a, b), TaskGraphTemplates.ordered(TaskGraphDraft("Order", tasks = listOf(b, a))).tasks)
        assertFailsWith<IllegalStateException> { TaskGraphTemplates.ordered(TaskGraphDraft("Cycle", tasks = listOf(a.copy(dependsOn = listOf(TaskDependency("b"))), b))) }
        val board = AgentBoardStore(folder.resolve("board"), resolveSeat = resolver::resolve)
        val view = board.propose(TaskGraphTemplates.builtIns[2].draft.copy(tasks = TaskGraphTemplates.builtIns[2].draft.tasks.map {
            it.copy(scope = listOf("Source.kt"), alternatives = it.alternatives?.copy(check = "unit"))
        }), BoardActor(BoardActorKind.Person, "owner"))
        val writer = view.tasks.first { it.graph?.seat?.access == TaskAccess.Write }
        board.advance(view.graph.id, writer.id) { task, plan -> task.copy(graph = plan.copy(excludedContext = listOf("private"))) }
        assertFailsWith<IllegalArgumentException> { TaskGraphTemplates.definitions(board.view(view.graph.id)) }
    }
}
