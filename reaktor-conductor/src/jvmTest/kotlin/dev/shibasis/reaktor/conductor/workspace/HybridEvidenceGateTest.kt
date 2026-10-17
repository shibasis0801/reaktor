package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/**
 * Finishing is the moment a claim becomes the record, so it has to carry evidence or an admission.
 *
 * The failures this seat has shipped were all confident and wrong. A gate that can be walked past in
 * silence would not have caught any of them; one that forces either a passing check or a sentence
 * saying what could not be checked at least makes the gap visible.
 */
class HybridEvidenceGateTest {

    @Test fun agatedTaskCannotFinishOnAnUnsupportedClaim() = runBlocking {
        drive(allowedChecks = listOf("./ok.sh")) { workspace, run, handoff ->
            val bare = HybridReply(handoff.id, handoff.revision, handoff.phase, "Looks right to me", next = HybridNext.Finish)
            val refused = assertFailsWith<IllegalArgumentException> { workspace.replyHandoff(run.id, bare) }
            assertTrue(refused.message.orEmpty().contains("none has passed since something was changed"), refused.message.orEmpty())

            // An honest admission is a valid way through — it just has to be said out loud.
            val admitted = workspace.replyHandoff(run.id, bare.copy(unverified = "No check covers the visual layout"))
            assertEquals(HybridPhase.Completed, admitted.phase)
            assertEquals("No check covers the visual layout", admitted.review?.unverified)
        }
    }

    @Test fun apassingCheckIsEvidenceEnough() = runBlocking {
        drive(allowedChecks = listOf("./ok.sh"), plan = { handoff ->
            HybridReply(handoff.id, handoff.revision, handoff.phase, "Make the change", next = HybridNext.Execute,
                checks = listOf(HybridCheck("it builds", "./ok.sh")))
        }) { workspace, run, handoff ->
            assertTrue(handoff.observation?.checks.orEmpty().any { it.ok }, "Reaktor ran the check itself")
            val finished = workspace.replyHandoff(run.id,
                HybridReply(handoff.id, handoff.revision, handoff.phase, "Done and proven", next = HybridNext.Finish))
            assertEquals(HybridPhase.Completed, finished.phase)
        }
    }

    @Test fun ataskWithNoPermittedChecksIsNotForcedToDeclareAnything() = runBlocking {
        drive(allowedChecks = emptyList()) { workspace, run, handoff ->
            val finished = workspace.replyHandoff(run.id,
                HybridReply(handoff.id, handoff.revision, handoff.phase, "Done", next = HybridNext.Finish))
            assertEquals(HybridPhase.Completed, finished.phase, "an operator who permitted no checks opted out of the gate")
        }
    }

    /** Runs one plan/execute pass, then hands the reviewing handoff to [assertions]. */
    private suspend fun drive(
        allowedChecks: List<String>,
        plan: (HybridHandoff) -> HybridReply = { h ->
            HybridReply(h.id, h.revision, h.phase, "Make the change", next = HybridNext.Execute)
        },
        assertions: suspend (AgentWorkspace, AgentRunRecord, HybridHandoff) -> Unit,
    ) {
        val root = source(); val directory = Files.createTempDirectory("gate")
        File(root, "ok.sh").also { it.writeText("#!/bin/sh\necho fine\nexit 0\n"); it.setExecutable(true) }
        val gemini = object : AgentRuntime {
            override val kind = RuntimeKind.Gemini
            override fun run(request: AgentRequest) = flow {
                File(request.workingDirectory, "touched.kt").writeText("val x = 1")
                emit(AgentEvent.Finished(request.agent.id, AgentOutcome(request.agent.id, "did the work", true)))
            }
        }
        AgentWorkspace(root, directory, mapOf(RuntimeKind.Gemini to gemini), discover = { ProviderCapability(it) }).use { workspace ->
            val run = workspace.submit(AgentSubmission("gate", RuntimeKind.ChatGptGemini, "Change something",
                allowWrites = true, allowedChecks = allowedChecks))
            stopped(workspace, run.id)
            workspace.replyHandoff(run.id, plan(workspace.handoffs(run.id).single()))
            workspace.resume(run.id); stopped(workspace, run.id)
            assertions(workspace, run, workspace.handoffs(run.id).single())
        }
        removeTree(root); removeTree(directory.toFile())
    }

    private fun removeTree(root: File) {
        if (!root.exists()) return
        java.nio.file.Files.walkFileTree(root.toPath(), object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes) =
                java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(file) }
            override fun postVisitDirectory(dir: java.nio.file.Path, failure: java.io.IOException?) =
                java.nio.file.FileVisitResult.CONTINUE.also { java.nio.file.Files.deleteIfExists(dir) }
        })
    }
    private fun git(root: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args).directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText(); check(process.waitFor() == 0) { output }
    }
    private fun source(): File = Files.createTempDirectory("gate-source").toFile().also {
        git(it, "init", "-q"); File(it, "graph.kt").writeText("base"); git(it, "add", ".")
        git(it, "-c", "user.name=T", "-c", "user.email=t@localhost", "commit", "-qm", "base")
    }
    private suspend fun stopped(workspace: AgentWorkspace, id: String) =
        withTimeout(60000) { while (workspace.get(id).status == AgentRunStatus.Running) delay(10) }
}
