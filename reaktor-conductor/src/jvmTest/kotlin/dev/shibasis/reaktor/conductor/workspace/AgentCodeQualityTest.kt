package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentCodeQualityTest {
    private val epoch = 1_700_000_000_000L
    private val day = 86_400_000L

    @Test fun windowsTrackRewritesDeletesAndRenamesWithoutCountingUnchangedBaselineLines() = fixture { root ->
        File(root, "Source.kt").writeText("baseline\n")
        commit(root, -1)
        File(root, "Source.kt").writeText("baseline\naddedA\naddedB\naddedC\n")
        val landed = commit(root, 0)
        File(root, "Source.kt").writeText("baseline\nchangedA\naddedB\naddedC\n")
        commit(root, 7)
        File(root, "Source.kt").writeText("baseline\nchangedA\naddedC\n")
        commit(root, 10)
        git(root, "mv", "Source.kt", "Renamed.kt")
        commit(root, 20)
        File(root, "Renamed.kt").writeText("baseline\nchangedA\nchangedC\n")
        commit(root, 25)
        val seat = ResolvedTaskSeat(TaskSeat(RuntimeKind.Codex, "fixture", profile = "writer"), 7)
        val receipt = AgentLanding("candidate", epoch, mapOf(root.canonicalPath to landed), seat)
        val report = AgentCodeQuality.measure(listOf("T1" to receipt), { listOf("declared:module") }, epoch + 31 * day)
        val file = report.files.single()
        assertEquals("Source.kt", file.path)
        assertEquals(listOf("declared:module"), file.modules)
        assertEquals("writer", file.profile)
        assertEquals(7L, file.profileRevision)
        assertEquals(3, file.windows.first().added)
        assertEquals(2, file.windows.first().changed)
        assertEquals(3, file.windows.last().changed)
        assertTrue(file.windows.all { it.mature && it.reason == null })
        val early = AgentCodeQuality.measure(listOf("T1" to receipt), { emptyList() }, epoch + 5 * day)
        assertTrue(early.files.single().windows.all { !it.mature && it.changed == null })
    }

    @Test fun missingOrUnrelatedHistoryStaysUnknownAndLeavesTheCheckoutUntouched() = fixture { root ->
        File(root, "Source.kt").writeText("baseline\n")
        val landed = commit(root, 0)
        val before = git(root, "status", "--porcelain")
        val receipt = AgentLanding("candidate", epoch, mapOf(root.canonicalPath to "0".repeat(40)), null)
        val report = AgentCodeQuality.measure(listOf("T1" to receipt), { emptyList() }, epoch + 40 * day)
        assertTrue(report.files.single().windows.all { it.changed == null && it.reason != null })
        assertEquals(before, git(root, "status", "--porcelain"))
        assertEquals(landed, git(root, "rev-parse", "HEAD"))
        val rootReceipt = receipt.copy(commits = mapOf(root.canonicalPath to landed))
        val noChurn = AgentCodeQuality.measure(listOf("T1" to rootReceipt), { emptyList() }, epoch + 40 * day)
        assertEquals(listOf(0, 0), noChurn.files.single().windows.map { it.changed })
    }

    @Test fun deletionOnlyLandingHasNoEligibleAddedLines() = fixture { root ->
        File(root, "Source.kt").writeText("baseline\n")
        commit(root, -1)
        File(root, "Source.kt").delete()
        val landed = commit(root, 0)
        File(root, "Other.kt").writeText("later\n")
        commit(root, 10)
        val receipt = AgentLanding("candidate", epoch, mapOf(root.canonicalPath to landed), null)
        val report = AgentCodeQuality.measure(listOf("T1" to receipt), { emptyList() }, epoch + 40 * day)
        assertEquals("Source.kt", report.files.single().path)
        assertTrue(report.files.single().windows.all { it.mature && it.added == 0 && it.changed == 0 && it.reason == null })
    }

    private fun fixture(body: (File) -> Unit) {
        val root = Files.createTempDirectory("agent-churn-test").toRealPath().toFile()
        try {
            git(root, "init", "-q")
            git(root, "config", "user.name", "Fixture")
            git(root, "config", "user.email", "fixture@example.invalid")
            body(root)
        } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    private fun commit(root: File, days: Int): String {
        git(root, "add", ".")
        val date = "@${(epoch + days * day) / 1000} +0000"
        val process = ProcessBuilder("git", "-C", root.path, "commit", "-qm", "Fixture $days")
        process.environment().putAll(mapOf("GIT_AUTHOR_DATE" to date, "GIT_COMMITTER_DATE" to date))
        val running = process.redirectErrorStream(true).start()
        val output = running.inputStream.bufferedReader().readText()
        check(running.waitFor() == 0) { output }
        return git(root, "rev-parse", "HEAD")
    }

    private fun git(root: File, vararg args: String): String {
        val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        check(process.waitFor() == 0) { output }
        return output
    }
}
