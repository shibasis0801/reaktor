package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentWorktreeRouterTest {
    @Test fun registrationSurvivesReuseAndRemovalPreservesDirtyWork() {
        val workspace = Files.createTempDirectory("reaktor-worktree-router-").toFile().canonicalFile
        val root = File(workspace, "bestbuds").apply { mkdirs() }
        val data = File(workspace, "agents").toPath()
        fun git(vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", root.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            return output
        }
        try {
            File(workspace, ".remote-dev").mkdir()
            File(workspace, ".remote-dev/config.json").writeText("""{"projects":["bestbuds"]}""")
            git("init", "-q")
            File(root, "source.txt").writeText("source\n")
            git("add", ".")
            git("-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "base")
            val store = AgentWorktrees(root, data.resolve("worktrees"), LocalAgentArtifacts(data.resolve("artifacts")))
            val tree = store.prepare("a".repeat(64), "builder")
            val registry = File(workspace, ".remote-dev/worktrees").listFiles()!!.single()
            assertEquals(tree, ConductorJson.decodeFromString(AgentWorktree.serializer(), registry.readText()))
            registry.delete()
            assertEquals(tree, store.prepare(tree.runId, tree.participant))
            assertTrue(registry.exists())
            val checkout = File(tree.roots.single().path)
            val changed = File(checkout, "changed.txt").apply { writeText("agent work\n") }
            assertFailsWith<IllegalStateException> { store.remove(tree.id) }
            assertTrue(changed.exists())
            assertTrue(registry.exists())
            changed.delete()
            store.remove(tree.id)
            assertFalse(checkout.exists())
            assertFalse(registry.exists())
            assertTrue(store.list(tree.runId).isEmpty())
            assertEquals("source\n", File(root, "source.txt").readText())
        } finally {
            workspace.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    @Test fun sparsePairsPreserveDirtySourceAndExcludeOnlyBestbudsEvidence() {
        val workspace = Files.createTempDirectory("reaktor-sparse-pair-").toFile().canonicalFile
        val root = File(workspace, "bestbuds").apply { mkdirs() }
        val framework = File(workspace, "reaktor").apply { mkdirs() }
        val data = File(workspace, "agents").toPath()
        fun git(directory: File, vararg args: String): String {
            val process = ProcessBuilder(listOf("git", "-C", directory.path) + args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor() == 0) { output }
            return output
        }
        try {
            File(workspace, ".remote-dev").mkdir()
            File(workspace, ".remote-dev/config.json").writeText("""{"projects":["bestbuds","reaktor"]}""")
            for (directory in listOf(root, framework)) {
                git(directory, "init", "-q")
                File(directory, "source.txt").writeText("base\n")
                File(directory, "agent").mkdir()
                File(directory, "agent/Source.kt").writeText("tracked evidence or source\n")
                git(directory, "add", ".")
                git(directory, "-c", "user.name=Test", "-c", "user.email=test@localhost", "commit", "-qm", "base")
            }
            File(root, "source.txt").writeText("staged source\n")
            git(root, "add", "source.txt")
            File(root, "source.txt").writeText("dirty authored source\n")
            File(root, "new.kt").writeText("new authored source\n")
            val index = File(root, ".git/index").readBytes().toList()
            val artifacts = LocalAgentArtifacts(data.resolve("artifacts"))
            val candidates = SourceCandidates(root, artifacts, listOf(root, framework))
            val before = candidates.capture()
            assertTrue(before.complete, before.notices.toString())
            File(root, "agent/Source.kt").writeText("changed evidence\n")
            File(root, "agent/untracked.kt").writeText("untracked evidence\n")
            val after = candidates.capture()
            assertEquals(before.sourceDigest, after.sourceDigest)
            assertTrue(after.changedFiles.none { it.startsWith("agent/") })
            assertEquals(setOf("source.txt", "new.kt"), candidates.dirtyManifest().entries.keys)
            val scoped = candidates.diffOf(listOf("source.txt", "agent/Source.kt", "agent/untracked.kt"))
            assertTrue(scoped.text.contains("dirty authored source"))
            assertFalse(scoped.text.contains("evidence"))
            val store = AgentWorktrees(root, data.resolve("worktrees"), artifacts)
            val tree = store.prepare("b".repeat(64), "builder", { candidates.capture() })
            assertEquals(tree, store.prepare(tree.runId, tree.participant, { candidates.capture() }))
            val checkout = File(tree.roots.single { it.source == root.path }.path)
            val included = File(tree.roots.single { it.source == framework.path }.path)
            assertFalse(File(checkout, "agent").exists())
            assertEquals("tracked evidence or source\n", File(included, "agent/Source.kt").readText())
            assertEquals("dirty authored source\n", File(checkout, "source.txt").readText())
            assertEquals("new authored source\n", File(checkout, "new.kt").readText())
            assertTrue(git(checkout, "ls-files", "-t", "--", "agent/Source.kt").startsWith("S "))
            val captured = store.capture(tree.id)
            assertTrue(captured.complete, captured.notices.toString())
            assertTrue(captured.changedFiles.isEmpty(), captured.changedFiles.toString())
            assertTrue(store.review(tree.id).changedFiles.isEmpty())
            File(checkout, "source.txt").writeText("implemented source\n")
            val review = store.review(tree.id)
            assertEquals(listOf("bestbuds/source.txt"), review.changedFiles)
            assertTrue(review.conflicts.isEmpty(), review.conflicts.toString())
            assertTrue(review.diff.contains("implemented source"))
            assertFalse(review.diff.contains("agent/Source.kt"))
            assertEquals(index, File(root, ".git/index").readBytes().toList())
            assertEquals("dirty authored source\n", File(root, "source.txt").readText())
            assertTrue(File(workspace, ".remote-dev/worktrees").listFiles()!!.single().isFile)
            File(checkout, "source.txt").writeText("dirty authored source\n")
            store.remove(tree.id)
            assertFalse(checkout.exists())
            assertFalse(included.exists())
            assertTrue(File(workspace, ".remote-dev/worktrees").listFiles()!!.isEmpty())
            assertEquals(index, File(root, ".git/index").readBytes().toList())
        } finally {
            workspace.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
