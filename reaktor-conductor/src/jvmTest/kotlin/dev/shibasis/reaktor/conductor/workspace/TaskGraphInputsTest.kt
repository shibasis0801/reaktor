package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class TaskGraphInputsTest {
    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output.trim()
    }

    private fun fixture(body: (File, File, AgentWorktrees) -> Unit) {
        val directory = Files.createTempDirectory("task-inputs-").toFile().canonicalFile
        val root = File(directory, "project").apply { mkdir() }
        val data = File(directory, "data").apply { mkdir() }
        try {
            git(root, "init", "--quiet")
            File(root, "A.kt").writeText("val a = 0\n")
            File(root, "B.kt").writeText("val b = 0\n")
            git(root, "add", ".")
            git(root, "-c", "user.name=Fixture", "-c", "user.email=fixture@localhost", "commit", "--quiet", "-m", "Base")
            val store = AgentWorktrees(root, File(data, "worktrees").toPath(), LocalAgentArtifacts(File(data, "artifacts").toPath()))
            body(root, data, store)
        } finally { directory.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private fun input(store: AgentWorktrees, task: String, tree: AgentWorktree) =
        AgentWorktreeInput(task, tree.id, store.capture(tree.id).id)

    private fun checkout(tree: AgentWorktree) = File(tree.roots.single().path)

    @Test fun frozenDirtyBaseAndIndependentCandidatesComposeWithoutTouchingThePrimaryIndex() = fixture { root, _, store ->
        File(root, "A.kt").writeText("val a = 1\n")
        git(root, "add", "A.kt")
        File(root, "A.kt").writeText("val a = 2\n")
        val index = File(root, ".git/index").readBytes()
        val base = store.freeze("G1")
        File(root, "A.kt").writeText("val primary = 99\n")
        val first = store.prepareFrom("first", "workspace", base, emptyList())
        val second = store.prepareFrom("second", "workspace", base, emptyList())
        assertEquals("val a = 2\n", File(checkout(first), "A.kt").readText())
        File(checkout(first), "A.kt").writeText("val a = 3\n")
        File(checkout(second), "B.kt").writeText("val b = 4\n")
        val inputs = listOf(input(store, "T10", second), input(store, "T2", first))
        val combined = store.prepareFrom("combined", "workspace", base, inputs)
        assertEquals(listOf("T2", "T10"), combined.inputs.keys.toList())
        assertEquals("val a = 3\n", File(checkout(combined), "A.kt").readText())
        assertEquals("val b = 4\n", File(checkout(combined), "B.kt").readText())
        assertEquals(setOf("A.kt", "B.kt"), store.capture(combined.id).changedFiles.toSet())
        assertEquals("val primary = 99\n", File(root, "A.kt").readText())
        assertContentEquals(index, File(root, ".git/index").readBytes())
        assertEquals(base.getValue(root.path), git(checkout(combined), "rev-parse", "HEAD"))
    }

    @Test fun sharedAncestorsMergeOnceAndAChildCanChangeItsAncestorsLine() = fixture { _, _, store ->
        val base = store.freeze("G1")
        val ancestor = store.prepareFrom("ancestor", "workspace", base, emptyList())
        File(checkout(ancestor), "A.kt").writeText("val a = 1\n")
        val a = input(store, "T1", ancestor)
        val left = store.prepareFrom("left", "workspace", base, listOf(a))
        File(checkout(left), "A.kt").writeText("val a = 2\n")
        val right = store.prepareFrom("right", "workspace", base, listOf(a))
        File(checkout(right), "B.kt").writeText("val b = 3\n")
        val joined = store.prepareFrom("joined", "workspace", base, listOf(input(store, "T3", right), input(store, "T2", left), a))
        assertEquals("val a = 2\n", File(checkout(joined), "A.kt").readText())
        assertEquals("val b = 3\n", File(checkout(joined), "B.kt").readText())
    }

    @Test fun plantedConflictNamesTheFileAndLeavesTheInputsAndPrimaryUntouched() = fixture { root, _, store ->
        val base = store.freeze("G1")
        val first = store.prepareFrom("first", "workspace", base, emptyList())
        val second = store.prepareFrom("second", "workspace", base, emptyList())
        File(checkout(first), "A.kt").writeText("val a = 1\n")
        File(checkout(second), "A.kt").writeText("val a = 2\n")
        val failure = assertFailsWith<IllegalStateException> {
            store.prepareFrom("conflict", "workspace", base, listOf(input(store, "T1", first), input(store, "T2", second)))
        }
        assertTrue(failure.message.orEmpty().contains("A.kt"), failure.message)
        assertTrue(store.list("conflict").isEmpty())
        assertEquals("val a = 0\n", File(root, "A.kt").readText())
        assertEquals("val a = 1\n", File(checkout(first), "A.kt").readText())
        assertEquals("val a = 2\n", File(checkout(second), "A.kt").readText())
    }

    @Test fun changedInputIsRejectedBeforePreparingAnOwnedCheckout() = fixture { _, _, store ->
        val base = store.freeze("G1")
        val first = store.prepareFrom("first", "workspace", base, emptyList())
        File(checkout(first), "A.kt").writeText("val a = 1\n")
        val old = input(store, "T1", first)
        File(checkout(first), "A.kt").writeText("val a = 2\n")
        assertFailsWith<IllegalArgumentException> { store.prepareFrom("stale", "workspace", base, listOf(old)) }
        assertTrue(store.list("stale").isEmpty())
    }

    @Test fun restartedStoreReusesTheExactPreparedInputsAndRejectsAnotherManifest() = fixture { root, data, store ->
        val base = store.freeze("G1")
        val first = store.prepareFrom("first", "workspace", base, emptyList())
        File(checkout(first), "A.kt").writeText("val a = 1\n")
        val inputs = listOf(input(store, "T1", first))
        val prepared = store.prepareFrom("next", "workspace", base, inputs)
        File(checkout(prepared), "B.kt").writeText("val retained = 1\n")
        val reopened = AgentWorktrees(root, File(data, "worktrees").toPath(), LocalAgentArtifacts(File(data, "artifacts").toPath()))
        assertEquals(prepared, reopened.prepareFrom("next", "workspace", base, inputs))
        assertEquals("val retained = 1\n", File(checkout(prepared), "B.kt").readText())
        assertFailsWith<IllegalArgumentException> { reopened.prepareFrom("next", "workspace", base, emptyList()) }
    }

    @Test fun boardCollectsTheRequiredCandidateFrontierThroughReviewsAndRetainsItsBase() = fixture { root, data, _ ->
        val directory = File(data, "board").toPath()
        val store = AgentBoardStore(directory)
        val person = BoardActor(BoardActorKind.Person, "fixture")
        val graph = store.propose(TaskGraphDraft("Inputs", tasks = listOf(
            TaskGraphOp.Add("First", key = "first", seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write)),
            TaskGraphOp.Add("Optional", key = "optional", seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write)),
            TaskGraphOp.Add("Review", key = "review", contract = WorkflowContract.Decision, seat = TaskSeat(RuntimeKind.Codex),
                dependsOn = listOf(TaskDependency("first"))),
            TaskGraphOp.Add("Next", key = "next", seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write),
                dependsOn = listOf(TaskDependency("review"), TaskDependency("optional", required = false)))
        ), envelope = TaskEnvelope(writes = listOf("*"))), person)
        val tasks = graph.tasks.associateBy { it.title }
        listOf("First", "Optional").forEach { title ->
            store.advance(graph.graph.id, tasks.getValue(title).id) { value, plan ->
                value.copy(graph = plan.copy(state = TaskState.Succeeded, worktreeId = title,
                    outcome = TaskOutcome(candidateId = title)))
            }
        }
        val base = mapOf(root.path to git(root, "rev-parse", "HEAD"))
        assertEquals(base, store.freezeBase(graph.graph.id, base))
        assertEquals(listOf(tasks.getValue("First").id), store.sourceInputs(tasks.getValue("Next").id).map { it.id })
        assertEquals(base, AgentBoardStore(directory).graph(graph.graph.id).baseCommits)
        assertFailsWith<IllegalArgumentException> { store.freezeBase(graph.graph.id, mapOf(root.path to "a".repeat(40))) }
    }

    @Test fun downstreamNativeWriterReceivesTheRequiredCandidateAndIgnoresLaterPrimaryEdits() = fixture { root, data, _ -> runBlocking {
        var calls = 0
        lateinit var owner: AgentWorkspace
        val runtime = object : AgentRuntime {
            override val kind = RuntimeKind.Codex
            override fun run(request: AgentRequest) = flow {
                calls++
                val working = File(request.workingDirectory)
                val run = owner.list(50).single { it.status == AgentRunStatus.Running && it.workingDirectory == working.canonicalPath }
                assertEquals(working.canonicalFile.toPath(), owner.sourceRoots(run.id, writing = true).first())
                emit(AgentEvent.Started(request.agent.id, ProviderSession(kind, "inputs-$calls")))
                if (calls == 1) {
                    File(working, "A.kt").writeText("val a = 8\n")
                    File(root, "B.kt").writeText("val outside = 99\n")
                } else {
                    assertEquals("val a = 8\n", File(working, "A.kt").readText())
                    assertEquals("val b = 0\n", File(working, "B.kt").readText())
                    File(working, "B.kt").writeText("val b = 9\n")
                }
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "Done", true)))
            }
        }
        AgentWorkspaceConnection.open(root, data.toPath(), mapOf(runtime.kind to runtime), discover = { ProviderCapability(it) },
            extraTools = { owner = it; emptyList() }).use { connection ->
            val person = BoardActor(BoardActorKind.Person, "fixture")
            val proposed = connection.proposeTaskGraph(TaskGraphDraft("Sequence", tasks = listOf(
                TaskGraphOp.Add("First", key = "first", scope = listOf("A.kt"), seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write)),
                TaskGraphOp.Add("Next", key = "next", scope = listOf("B.kt"), seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write),
                    dependsOn = listOf(TaskDependency("first")))
            ), envelope = TaskEnvelope(writes = listOf("*"))), person)
            connection.startTaskGraph(proposed.graph.id, proposed.graph.revision, person)
            val done = withTimeout(20000) {
                var view = connection.taskGraph(proposed.graph.id)
                while (view.graph.status == TaskGraphStatus.Running) { delay(25); view = connection.taskGraph(proposed.graph.id) }
                view
            }
            assertEquals(TaskGraphStatus.Finished, done.graph.status, done.tasks.toString())
            assertEquals(2, calls)
            assertTrue(done.tasks.all { it.graph?.state == TaskState.Succeeded })
            assertEquals("val a = 0\n", File(root, "A.kt").readText())
            assertEquals("val outside = 99\n", File(root, "B.kt").readText())
        }
    } }
}
