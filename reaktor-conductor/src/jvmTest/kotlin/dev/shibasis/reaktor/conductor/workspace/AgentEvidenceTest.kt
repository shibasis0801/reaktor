package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import dev.shibasis.dependeasy.model.BuildModel
import dev.shibasis.dependeasy.model.BuildModelReader
import dev.shibasis.dependeasy.model.BuildIdentity
import dev.shibasis.dependeasy.model.Producer

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
            val accepted = store.accept("task", repaired.id)
            assertEquals(repaired.id, accepted.acceptedCandidate)
            assertFalse(accepted.approverVerified)
            assertFalse(AgentEvidenceStore(root, data).get("task").approverVerified)
            File(root, "untracked.kt").writeText("new input")
            val withNewFile = store.capture("task")
            assertTrue(store.artifact("task", withNewFile.diff!!.id).text.contains("+new input"))
            assertFailsWith<IllegalArgumentException> { store.accept("task", repaired.id) }
            val reopened = AgentEvidenceStore(root, data)
            assertEquals("attempt-1", reopened.get("task").findings.single().producerRunId)
            assertTrue(reopened.graph("task").edges.any { it.relation == "verifies" && it.to == repaired.id })
            assertTrue(reopened.artifact("task", repaired.diff!!.id).text.contains("repair"))
            assertFails { reopened.artifact("task", "../outside") }
        } finally { root.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); data.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }
    @Test fun worktreeCandidatesValidateAndAcceptTheirOwnMaterializedSourceRoots() {
        val parent = Files.createTempDirectory("candidate-owned").toFile()
        val data = Files.createTempDirectory("candidate-owned-data")
        val temporary = File(System.getProperty("java.io.tmpdir"))
        try {
            val root = File(parent, "app").apply { mkdirs() }
            val dependency = File(parent, "framework").apply { mkdirs() }
            listOf(root, dependency).forEach {
                git(it, "init", "-q")
                File(it, ".gitignore").writeText("build/\n")
                File(it, "Source.kt").writeText("val original = 1\n")
            }
            File(root, "settings.gradle.kts").writeText("includeBuild(\"../framework\")\n")
            root.resolve(BuildModelReader.RelativePath).apply {
                parentFile.mkdirs()
                writeText(BuildModelReader.encode(BuildModel(producer = Producer(version = "fixture"),
                    builds = listOf(BuildIdentity("app", "."), BuildIdentity("framework", "../framework")), nodes = emptyList())))
            }
            listOf(root, dependency).forEach {
                git(it, "add", ".")
                git(it, "-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-qm", "fixture")
            }
            val store = AgentEvidenceStore(root, data.resolve("evidence"))
            val worktrees = AgentWorktrees(root, data.resolve("worktrees"), store.artifacts)
            val owned = worktrees.prepare("writing-attempt", "workspace")
            val checkout = File(owned.roots.single { it.source == root.canonicalPath }.path)
            val framework = File(owned.roots.single { it.source == dependency.canonicalPath }.path)
            assertFalse(checkout.resolve(BuildModelReader.RelativePath).exists())
            File(checkout, "Source.kt").writeText("val repaired = 2\n")
            File(framework, "Source.kt").writeText("val included = 2\n")
            val first = worktrees.capture(owned.id)
            assertTrue(first.complete, first.notices.joinToString())
            assertEquals(setOf(checkout.canonicalPath, framework.canonicalPath), first.sourceRoots.toSet())
            assertEquals(setOf("Source.kt", "../framework/Source.kt"), first.changedFiles.toSet())
            store.recordCandidate("T1", first)
            File(root, "Source.kt").delete()
            File(dependency, "Source.kt").writeText("another session")
            store.finding("T1", AgentFinding("included", first.id, "review-attempt", "claudecode",
                "Included defect", "Repair the included source", "high", sourcePath = "../framework/Source.kt", line = 1))
            val escape = AgentFinding("escape", first.id, "review-attempt", "claudecode",
                "Outside", "Not this candidate", "high", sourcePath = "../../outside.kt", line = 1)
            assertFailsWith<IllegalArgumentException> { store.finding("T1", escape) }
            File(framework, "Source.kt").writeText("val included = 3\n")
            val second = worktrees.capture(owned.id)
            assertTrue(second.complete)
            assertNotEquals(first.id, second.id)
            store.recordCandidate("T1", second)
            store.resolve("T1", "included", second.id, "The second attempt repairs the included source")
            store.requireChecks("T1", listOf("source-test"))
            store.check("T1", AgentCandidateCheck("source-test", first.id,
                CheckResult(listOf("source-test"), CheckOutcome.Passed, revision = first.id), "kernel"))
            assertFailsWith<IllegalArgumentException> { store.accept("T1", second.id) }
            store.check("T1", AgentCandidateCheck("source-test", second.id,
                CheckResult(listOf("source-test"), CheckOutcome.Passed, revision = second.id), "kernel"))
            assertEquals(second.id, store.accept("T1", second.id).acceptedCandidate)
            File(framework, "Source.kt").writeText("val included = 4\n")
            assertFailsWith<IllegalArgumentException> { store.accept("T1", second.id) }
            owned.roots.forEach { entry -> git(File(entry.source), "worktree", "remove", "--force", entry.path) }
        } finally {
            parent.deleteTreeSafely(within = temporary)
            data.toFile().deleteTreeSafely(within = temporary)
        }
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
        } finally { data.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
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
            root.resolve(BuildModelReader.RelativePath).apply {
                parentFile.mkdirs()
                writeText(BuildModelReader.encode(BuildModel(producer = Producer(version = "fixture"),
                    builds = listOf(BuildIdentity("app", "."), BuildIdentity("framework", "../framework")), nodes = emptyList())))
            }
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
        } finally { parent.deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))); data.toFile().deleteTreeSafely(within = java.io.File(System.getProperty("java.io.tmpdir"))) }
    }
}
