package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentEvidenceTest {
    private fun git(root: File, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { output }
    }
    @Test fun dirtyCandidatesInvalidateChecksAndFindingsRemainBoundToTheirProducer() {
        val root = Files.createTempDirectory("candidate-root").toFile()
        val data = Files.createTempDirectory("candidate-data")
        try {
            git(root, "init", "-q")
            File(root, "Tabs.kt").writeText("initial")
            git(root, "add", ".")
            git(root, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "initial")
            val store = AgentEvidenceStore(root, data)
            val subject = AgentGraphSubject("graph/tabs", "definition-1", "Tabs.kt")
            val initial = store.capture("task", listOf(subject))
            assertTrue(initial.complete)
            File(root, "Tabs.kt").writeText("repair")
            val repaired = store.capture("task", listOf(subject))
            assertNotEquals(initial.id, repaired.id, "HEAD stayed the same; dirty source must change identity")
            store.requireChecks("task", listOf("tabs-test"))
            store.check("task", AgentCandidateCheck("tabs-test", initial.id, CheckResult(listOf("tabs-test"), CheckOutcome.Passed, revision = initial.id), "kernel"))
            assertFailsWith<IllegalArgumentException> { store.accept("task", repaired.id) }
            store.finding("task", AgentFinding("f1", repaired.id, "attempt-1", "claude", "Lost draft", "Restore the tab draft", "high", subject, "Tabs.kt", 1))
            store.check("task", AgentCandidateCheck("tabs-test", repaired.id, CheckResult(listOf("tabs-test"), CheckOutcome.Passed, revision = repaired.id), "kernel"))
            assertFailsWith<IllegalArgumentException> { store.accept("task", repaired.id) }
            store.resolve("task", "f1", repaired.id, "Verified draft restoration in tabs-test")
            assertEquals(repaired.id, store.accept("task", repaired.id).acceptedCandidate)
            File(root, "untracked.kt").writeText("new input")
            assertFailsWith<IllegalArgumentException> { store.accept("task", repaired.id) }
            val reopened = AgentEvidenceStore(root, data)
            assertEquals("attempt-1", reopened.get("task").findings.single().producerRunId)
            assertTrue(reopened.graph("task").edges.any { it.relation == "verifies" && it.to == repaired.id })
            assertTrue(reopened.artifact("task", repaired.diff!!.id).text.contains("repair"))
            assertFails { reopened.artifact("task", "../outside") }
        } finally { root.deleteRecursively(); data.toFile().deleteRecursively() }
    }
    @Test fun artifactRangesReassembleUnicodeWithoutDroppingBytes() {
        val data = Files.createTempDirectory("artifact-unicode")
        try {
            val store = LocalAgentArtifacts(data)
            val text = "abc🙂देवनागरी\n".repeat(20)
            val ref = store.put(text, "log")
            var offset = 0L
            val output = StringBuilder()
            do {
                val page = store.read(ref, offset, 7)
                output.append(page.text)
                val next = page.nextOffset ?: break
                assertTrue(next > offset)
                offset = next
            } while (true)
            assertEquals(text, output.toString())
        } finally { data.toFile().deleteRecursively() }
    }

    @Test fun anIncludedBuildEditChangesTheRootCandidate() {
        val parent = Files.createTempDirectory("candidate-composite").toFile()
        val data = Files.createTempDirectory("candidate-composite-data")
        try {
            val root = File(parent, "app").apply { mkdirs() }
            val dependency = File(parent, "framework").apply { mkdirs() }
            listOf(root, dependency).forEach { directory ->
                git(directory, "init", "-q")
                File(directory, "Source.kt").writeText("initial")
            }
            File(root, "settings.gradle.kts").writeText("includeBuild(\"../framework\")\n")
            listOf(root, dependency).forEach {
                git(it, "add", ".")
                git(it, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture")
            }
            val store = AgentEvidenceStore(root, data)
            val before = store.capture("task")
            assertTrue(before.complete)
            File(dependency, "Source.kt").writeText("changed")
            val after = store.capture("task")
            assertNotEquals(before.id, after.id)
            assertTrue(dependency.canonicalPath in after.sourceRoots)
            assertTrue(after.changedFiles.any { it.endsWith("framework/Source.kt") })
        } finally { parent.deleteRecursively(); data.toFile().deleteRecursively() }
    }
}
