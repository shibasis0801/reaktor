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
}
