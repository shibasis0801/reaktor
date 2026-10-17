package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * What a task changed, what a check proves, and what a cycle costs.
 *
 * All three used to be answered by the wrong measurement. The diff was the whole dirty checkout, so
 * a twenty-line change arrived buried in the operator's own unrelated work. Verification could only
 * be bought with an execution cycle, so the loop spent development iterations asking the executor to
 * read exit codes back to it. And a permission the executor never had cost the same as a failed
 * attempt at the actual task.
 */
class HybridTaskScopeTest {

    @Test fun theDiffIsWhatThisTaskChangedNotWhatIsDirtyInTheCheckout() = runBlocking {
        val root = source()
        // The operator's own uncommitted work, present before the task starts. Every earlier diff
        // put this in front of the reviewer as though the agent had done it.
        File(root, "unrelated.png").writeText("operator's own edit")
        drive(root) { workspace, run, handoff ->
            val observation = requireNotNull(handoff.observation)
            assertEquals(listOf("touched.kt"), observation.changedPaths)
            assertEquals(ChangeScope.Turn, observation.scope)
            val diff = workspace.handoffArtifact(run.id, requireNotNull(observation.diffArtifact).id).text
            assertTrue(diff.contains("touched.kt"), diff)
            assertFalse(diff.contains("unrelated.png"), "the reviewer is shown this task's work, not the checkout's")
        }
    }

    @Test fun aCheckRunBeforeAnyChangeIsABaselineAndLaterFailuresAreClassifiedAgainstIt() = runBlocking {
        val root = source()
        breaks(root)
        drive(root, allowedChecks = listOf("./check.sh"), beforeExecuting = { workspace, run, handoff ->
            // No cycle spent, no executor involved: the planner simply asks Reaktor to run it.
            val baseline = suite(workspace, run, handoff)
            assertTrue(baseline.ok); assertTrue(baseline.baseline)
            assertEquals(CheckClassification.Baseline, baseline.classification)
            assertFalse(baseline.proves, "a green tree before the work is not evidence the work is good")
        }, writes = { workspace -> File(workspace, "broken").writeText("x") }) { workspace, run, handoff ->
            val after = suite(workspace, run, handoff)
            assertFalse(after.ok); assertFalse(after.baseline)
            assertEquals(CheckClassification.Regression, after.classification)
            val refused = assertFailsWith<IllegalArgumentException> {
                workspace.replyHandoff(run.id, HybridReply(handoff.id, handoff.revision, handoff.phase, "Done", next = HybridNext.Finish))
            }
            assertTrue(refused.message.orEmpty().contains("none has passed"), refused.message.orEmpty())
        }
    }

    @Test fun aFailureThatWasAlreadyThereIsNotChargedToThisTask() = runBlocking {
        val root = source()
        breaks(root)
        File(root, "broken").writeText("x")
        drive(root, allowedChecks = listOf("./check.sh"), beforeExecuting = { workspace, run, handoff ->
            val baseline = suite(workspace, run, handoff)
            assertFalse(baseline.ok); assertEquals(CheckClassification.Baseline, baseline.classification)
        }) { workspace, run, handoff ->
            val after = suite(workspace, run, handoff)
            assertEquals(CheckClassification.PreExisting, after.classification,
                "the same command failed on the untouched tree; neither model should have to argue about it")
        }
    }

    @Test fun aCheckThatPassesAfterTheWorkSatisfiesTheGateWithoutSpendingACycle() = runBlocking {
        val root = source()
        breaks(root)
        drive(root, allowedChecks = listOf("./check.sh")) { workspace, run, handoff ->
            val proof = suite(workspace, run, handoff)
            assertTrue(proof.proves)
            val finished = workspace.replyHandoff(run.id,
                HybridReply(handoff.id, handoff.revision, handoff.phase, "Done and proven", next = HybridNext.Finish))
            assertEquals(HybridPhase.Completed, finished.phase)
            assertEquals(1, finished.completedCycles, "one execution cycle, and the proof cost none of it")
        }
    }

    @Test fun aPermissionTheExecutorNeverHadDoesNotSpendADevelopmentIteration() = runBlocking {
        val root = source()
        val denied = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow {
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "", false,
                    failure = "Antigravity could not obtain required permissions: {\"action\":\"command\"}")))
            }
        }
        val directory = Files.createTempDirectory("scope-harness")
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Gemini to denied), discover = { ProviderCapability(it) }).use { workspace ->
            val run = workspace.submit(AgentSubmission("harness", RuntimeKind.ChatGptGemini, "Change something", allowWrites = true))
            stopped(workspace, run.id)
            val plan = workspace.handoffs(run.id).single()
            workspace.replyHandoff(run.id, HybridReply(plan.id, plan.revision, plan.phase, "Run the build", next = HybridNext.Execute))
            workspace.resume(run.id); stopped(workspace, run.id)
            val review = workspace.handoffs(run.id).single()
            assertEquals("permission", review.observation?.harness)
            val again = workspace.replyHandoff(run.id,
                HybridReply(review.id, review.revision, review.phase, "Try it another way", next = HybridNext.Execute))
            assertEquals(1, again.cycles.size, "the pass happened")
            assertEquals(0, again.chargedCycles, "and cost the task nothing, because it never reached the code")
            assertFalse(again.cycles.single().counted)
        }
        removeTree(root); removeTree(directory.toFile())
        Unit
    }

    /** Plans one pass, executes it, then hands the reviewing handoff to [assertions]. */
    private suspend fun drive(
        root: File,
        allowedChecks: List<String> = emptyList(),
        beforeExecuting: suspend (AgentWorkspace, AgentRunRecord, HybridHandoff) -> Unit = { _, _, _ -> },
        writes: (File) -> Unit = {},
        assertions: suspend (AgentWorkspace, AgentRunRecord, HybridHandoff) -> Unit,
    ) {
        val directory = Files.createTempDirectory("scope")
        val gemini = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow {
                File(request.workingDirectory, "touched.kt").writeText("val x = 1")
                writes(File(request.workingDirectory))
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "did the work", true)))
            }
        }
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Gemini to gemini), discover = { ProviderCapability(it) }).use { workspace ->
            val run = workspace.submit(AgentSubmission("scope", RuntimeKind.ChatGptGemini, "Change something",
                allowWrites = true, allowedChecks = allowedChecks))
            stopped(workspace, run.id)
            val plan = workspace.handoffs(run.id).single()
            beforeExecuting(workspace, run, plan)
            workspace.replyHandoff(run.id, HybridReply(plan.id, plan.revision, plan.phase, "Make the change", next = HybridNext.Execute))
            workspace.resume(run.id); stopped(workspace, run.id)
            assertions(workspace, run, workspace.handoffs(run.id).single())
        }
        removeTree(root); removeTree(directory.toFile())
    }

    /** Runs the task's one permitted check and waits for it: these finish in milliseconds. */
    private fun suite(workspace: AgentWorkspace, run: AgentRunRecord, handoff: HybridHandoff): HybridCheckRun =
        workspace.checkHandoff(run.id, handoff.id, listOf(HybridCheck("suite", "./check.sh")))
            .get(60, java.util.concurrent.TimeUnit.SECONDS).last()

    /** A check that passes until something drops a `broken` marker next to it. */
    private fun breaks(root: File) = File(root, "check.sh").also {
        it.writeText("#!/bin/sh\n[ -f broken ] && exit 1\nexit 0\n"); it.setExecutable(true)
    }

    private fun removeTree(root: File) {
        if (!root.exists()) return
        Files.walkFileTree(root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                java.nio.file.FileVisitResult.CONTINUE.also { Files.deleteIfExists(file) }
            override fun postVisitDirectory(dir: java.nio.file.Path, failure: java.io.IOException?) =
                java.nio.file.FileVisitResult.CONTINUE.also { Files.deleteIfExists(dir) }
        })
    }
    private fun git(root: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }
    }
    private fun source(): File = Files.createTempDirectory("scope-source").toFile().also {
        git(it, "init", "-q")
        File(it, "graph.kt").writeText("base"); File(it, "unrelated.png").writeText("committed")
        git(it, "add", ".")
        git(it, "-c", "user.name=T", "-c", "user.email=t@localhost", "commit", "-qm", "base")
    }
    private suspend fun stopped(workspace: AgentWorkspace, id: String) =
        withTimeout(60000) { while (workspace.get(id).status == AgentRunStatus.Running) delay(10) }
}
