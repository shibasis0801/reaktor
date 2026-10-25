package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class TaskGraphLandingTest {
    private val person = BoardActor(BoardActorKind.Person, "Operator")
    private val agent = BoardActor(BoardActorKind.Agent, "codex")
    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
        return output
    }
    private fun File.line(number: Int, text: String) = writeText(readLines().mapIndexed { index, value ->
        if (index + 1 == number) text else value
    }.joinToString("\n", postfix = "\n"))
    private fun fixture(body: (File, File, AgentWorktrees) -> Unit) {
        val root = Files.createTempDirectory("landing-source-").toFile().canonicalFile
        val data = Files.createTempDirectory("landing-data-").toFile().canonicalFile
        try {
            git(root, "init", "-q")
            git(root, "config", "user.name", "Test"); git(root, "config", "user.email", "test@localhost")
            File(root, "Source.kt").writeText((1..24).joinToString("\n", postfix = "\n") { "val line$it = $it" })
            File(root, "Other.kt").writeText("val other = 1\n")
            git(root, "add", "."); git(root, "commit", "-qm", "base")
            body(root, data, AgentWorktrees(root, File(data, "worktrees").toPath(), LocalAgentArtifacts(File(data, "artifacts").toPath())))
        } finally {
            data.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")).canonicalFile)
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")).canonicalFile)
        }
    }
    private fun build(root: File, store: AgentWorktrees): Pair<AgentWorktree, File> {
        File(root, "Source.kt").line(1, "val line1 = 100")
        val tree = store.prepare("writing", "builder")
        val checkout = File(tree.roots.single().path)
        File(checkout, "Source.kt").line(20, "val line20 = 200")
        return tree to checkout
    }

    @Test fun onlyCandidateHunksCommitWhileUnrelatedEditsAndStagingSurvive() = fixture { root, _, store ->
        val (tree, _) = build(root, store)
        val review = store.review(tree.id)
        File(root, "Other.kt").writeText("val other = 2\n")
        git(root, "add", "Other.kt")
        File(root, "untracked.txt").writeText("keep\n")
        val staged = git(root, "diff", "--cached")
        assertEquals(review.sourceRevision, store.review(tree.id).sourceRevision)
        assertTrue(store.review(tree.id).conflicts.isEmpty())
        val commits = store.land(tree.id, review.patchDigest, review.sourceRevision, "Land test candidate")
        assertEquals(git(root, "rev-parse", "HEAD").trim(), commits.getValue(root.name))
        assertContains(git(root, "show", "HEAD:Source.kt"), "val line1 = 1\n")
        assertContains(git(root, "show", "HEAD:Source.kt"), "val line20 = 200\n")
        assertContains(File(root, "Source.kt").readText(), "val line1 = 100\n")
        assertEquals(staged, git(root, "diff", "--cached"))
        assertEquals("", git(root, "diff", "--cached", "--", "Source.kt"))
        assertEquals("keep\n", File(root, "untracked.txt").readText())
        store.remove(tree.id)
        assertFalse(File(tree.roots.single().path).exists())
    }

    @Test fun changedCandidateFilesRefuseBeforeAnyApply() = fixture { root, _, store ->
        val (tree, _) = build(root, store)
        File(root, "Source.kt").line(4, "val line4 = 400")
        val review = store.review(tree.id)
        assertTrue(review.conflicts.any { "Source.kt" in it })
        assertFails { store.land(tree.id, review.patchDigest, review.sourceRevision, "Refused") }
        assertContains(File(root, "Source.kt").readText(), "val line20 = 20\n")
    }

    @Test fun stagedCandidateFilesRefuseAndKeepTheirIndex() = fixture { root, _, store ->
        val (tree, _) = build(root, store)
        git(root, "add", "Source.kt")
        val staged = git(root, "diff", "--cached")
        val review = store.review(tree.id)
        assertContains(assertFails { store.land(tree.id, review.patchDigest, review.sourceRevision, "Refused") }.message.orEmpty(), "Staged changes")
        assertEquals(staged, git(root, "diff", "--cached"))
        assertFalse(File(root, ".git/index.lock").exists())
        assertContains(File(root, "Source.kt").readText(), "val line20 = 20\n")
    }

    @Test fun aPatchDependingOnAnUncommittedEditNamesItsFileAndDoesNotApply() = fixture { root, _, store ->
        File(root, "Source.kt").line(19, "val line19 = 190")
        val tree = store.prepare("dependency", "builder")
        File(tree.roots.single().path, "Source.kt").line(20, "val line20 = 200")
        val head = git(root, "rev-parse", "HEAD")
        val review = store.review(tree.id)
        val failure = assertFails { store.land(tree.id, review.patchDigest, review.sourceRevision, "Refused") }
        assertContains(failure.message.orEmpty(), "Source.kt")
        assertContains(failure.message.orEmpty(), "uncommitted")
        assertEquals(head, git(root, "rev-parse", "HEAD"))
        assertContains(File(root, "Source.kt").readText(), "val line20 = 20\n")
    }

    @Test fun aChangedWorktreeRefusesTheEarlierReview() = fixture { root, _, store ->
        val (tree, checkout) = build(root, store)
        val review = store.review(tree.id)
        File(checkout, "Source.kt").line(21, "val line21 = 210")
        assertContains(assertFails { store.land(tree.id, review.patchDigest, review.sourceRevision, "Refused") }.message.orEmpty(), "stale")
        assertContains(File(root, "Source.kt").readText(), "val line20 = 20\n")
    }

    @Test fun additionsDeletionsAndExecutableModesReachHeadAndTheRealIndex() = fixture { root, _, store ->
        val tree = store.prepare("files", "builder")
        val checkout = File(tree.roots.single().path)
        Files.delete(File(checkout, "Other.kt").toPath())
        File(checkout, "new-tool").apply { writeText("run\n"); setExecutable(true) }
        val review = store.review(tree.id)
        store.land(tree.id, review.patchDigest, review.sourceRevision, "Land files")
        assertFalse(File(root, "Other.kt").exists())
        assertTrue(File(root, "new-tool").canExecute())
        assertEquals("", git(root, "diff", "--cached"))
        assertEquals("", git(root, "diff"))
    }

    private fun graph(root: File, data: File, radar: suspend (String, JsonObject) -> JsonElement,
        runtimes: Map<RuntimeKind, AgentRuntime> = emptyMap(), body: suspend (AgentWorkspace, String) -> Unit) = runBlocking {
        AgentWorkspace(root, File(data, "owner").toPath(), runtimes, kernelRead = radar).use { owner ->
            owner.board.propose(TaskGraphDraft("Land", tasks = listOf(
                TaskGraphOp.Add("Build", key = "build", seat = TaskSeat(RuntimeKind.Codex, access = TaskAccess.Write)),
                TaskGraphOp.Add("Review", seat = TaskSeat(RuntimeKind.ClaudeCode), contract = WorkflowContract.Decision,
                    dependsOn = listOf(TaskDependency("build"))),
            )), person)
            val tree = owner.worktrees.prepare("graph-writing", "builder")
            File(tree.roots.single().path, "Source.kt").line(20, "val line20 = 200")
            owner.board.advance("G1", "T1") { task, plan -> task.copy(stage = BoardStage.Review,
                graph = plan.copy(state = TaskState.Succeeded, worktreeId = tree.id)) }
            val candidate = owner.captureCandidate("T1").id
            owner.board.advance("G1", "T1") { task, plan -> task.copy(graph = plan.copy(outcome = TaskOutcome(candidateId = candidate))) }
            owner.board.advance("G1", "T2") { task, plan -> task.copy(graph = plan.copy(state = TaskState.Succeeded,
                outcome = TaskOutcome(verdict = "pass", candidateId = candidate))) }
            owner.evidence.requireChecks("T1", listOf("verify"))
            owner.evidence.check("T1", AgentCandidateCheck("verify", candidate,
                CheckResult(listOf("verify"), CheckOutcome.Passed, 0, revision = candidate, coverage = CheckCoverage.Complete), "kernel"))
            body(owner, candidate)
        }
    }
    private fun emptyRadar() = buildJsonObject { putJsonArray("sessions") {}; putJsonArray("modules") {} }

    @Test fun personAcceptanceAndExactReviewAreRequiredBeforeLandingAndCleanup() = fixture { root, data, _ ->
        graph(root, data, { _, _ -> emptyRadar() }) { owner, candidate ->
            fun revision() = owner.board.graph("G1").revision
            assertFails { owner.acceptTaskCandidate("G1", "T1", candidate, revision(), agent) }
            assertFails { owner.landTaskCandidate("G1", "T1", candidate, revision(), person) }
            owner.evidence.accept("T1", candidate)
            assertFalse(owner.taskEvidence("T1").approverVerified)
            assertFails { owner.landTaskCandidate("G1", "T1", candidate, revision(), person) }
            val before = revision()
            owner.acceptTaskCandidate("G1", "T1", candidate, before, person)
            assertTrue(owner.taskEvidence("T1").approverVerified)
            assertFails { owner.landTaskCandidate("G1", "T1", candidate, before, person) }
            val tree = requireNotNull(owner.board.get("T1").graph?.worktreeId)
            val landed = owner.landTaskCandidate("G1", "T1", candidate, revision(), person)
            assertEquals(BoardStage.Done, landed.tasks.single { it.id == "T1" }.stage)
            assertContains(owner.board.get("T1").graph?.note.orEmpty(), "Landed $candidate:")
            assertFails { owner.worktrees.read(tree) }
            assertEquals("", git(root, "diff", "--cached"))
            assertEquals(landed, owner.landTaskCandidate("G1", "T1", candidate, before, person))
        }
    }

    @Test fun aBusyRadarSessionOnCandidateFilesBlocksButAnUnrelatedSessionDoesNot() = fixture { root, data, _ ->
        var busy = true
        graph(root, data, { name, args ->
            assertEquals("agent.radar", name)
            assertTrue(args.getValue("paths").jsonArray.any { it.jsonPrimitive.content.endsWith("Source.kt") })
            buildJsonObject { putJsonArray("modules") {}; putJsonArray("sessions") { addJsonObject {
                put("state", "Working"); put("title", "Other task")
                putJsonArray("sharedFiles") { if (busy) add(File(root, "Source.kt").path) }
            } } }
        }) { owner, candidate ->
            owner.acceptTaskCandidate("G1", "T1", candidate, owner.board.graph("G1").revision, person)
            val revision = owner.board.graph("G1").revision
            assertContains(assertFails { owner.landTaskCandidate("G1", "T1", candidate, revision, person) }.message.orEmpty(), "busy")
            assertContains(File(root, "Source.kt").readText(), "val line20 = 20\n")
            busy = false
            assertEquals(BoardStage.Done, owner.landTaskCandidate("G1", "T1", candidate, revision, person).tasks.single { it.id == "T1" }.stage)
        }
    }

    @Test fun ownedRunsBlockOnlyTheirDeclaredCandidateFiles() {
        listOf("Source.kt", "Other.kt").forEach { path -> fixture { root, data, _ ->
            val started = CompletableDeferred<Unit>()
            val runtime = object : AgentRuntime {
                override val kind = RuntimeKind.Codex
                override fun run(request: AgentRequest) = flow {
                    emit(AgentEvent.Started(request.agent.id, null))
                    started.complete(Unit)
                    awaitCancellation()
                }
            }
            graph(root, data, { _, _ -> emptyRadar() }, mapOf(RuntimeKind.Codex to runtime)) { owner, candidate ->
                owner.submit(AgentSubmission("busy-$path", RuntimeKind.Codex, "Read the declared file", context =
                    ContextPacket(workspaceId = root.path, principalId = "fixture", source = "test", entries =
                        listOf(ContextEntry(path, "source", path, "", "Declared scope")))))
                withTimeout(5_000) { started.await() }
                owner.acceptTaskCandidate("G1", "T1", candidate, owner.board.graph("G1").revision, person)
                val revision = owner.board.graph("G1").revision
                if (path == "Source.kt") {
                    assertContains(assertFails { owner.landTaskCandidate("G1", "T1", candidate, revision, person) }.message.orEmpty(), "Running task")
                    assertContains(File(root, "Source.kt").readText(), "val line20 = 20\n")
                } else assertEquals(BoardStage.Done,
                    owner.landTaskCandidate("G1", "T1", candidate, revision, person).tasks.single { it.id == "T1" }.stage)
            }
        } }
    }

    @Test fun staleCandidatesAndReviewsForAnotherCandidateCannotBeAccepted() = fixture { root, data, _ ->
        graph(root, data, { _, _ -> emptyRadar() }) { owner, candidate ->
            owner.board.advance("G1", "T2") { task, plan -> task.copy(graph = plan.copy(outcome = plan.outcome?.copy(candidateId = "another"))) }
            assertContains(assertFails { owner.acceptTaskCandidate("G1", "T1", candidate, owner.board.graph("G1").revision, person) }.message.orEmpty(), "passing review")
            owner.board.advance("G1", "T2") { task, plan -> task.copy(graph = plan.copy(outcome = plan.outcome?.copy(candidateId = candidate))) }
            val tree = owner.worktrees.read(requireNotNull(owner.board.get("T1").graph?.worktreeId))
            File(tree.roots.single().path, "Source.kt").line(21, "val line21 = 210")
            assertContains(assertFails { owner.acceptTaskCandidate("G1", "T1", candidate, owner.board.graph("G1").revision, person) }.message.orEmpty(), "candidate changed")
        }
    }
}
