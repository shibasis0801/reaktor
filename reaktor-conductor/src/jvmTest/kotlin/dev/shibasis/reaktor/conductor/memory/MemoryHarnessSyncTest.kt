package dev.shibasis.reaktor.conductor.memory

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.ProviderCapability
import dev.shibasis.reaktor.conductor.workspace.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import java.nio.file.Files
import kotlin.test.*

class MemoryHarnessSyncTest {
    @Test fun reviewedFactsExportToEveryHarnessWithoutReimportingTheSharedSection() {
        val root = Files.createTempDirectory("memory-sync-root").toFile()
        val state = Files.createTempDirectory("memory-sync-state")
        val home = Files.createTempDirectory("memory-sync-home").toFile()
        try {
            AgentWorkspace(root, state, emptyMap(), background = false, discover = { ProviderCapability(it) }).use { workspace ->
                val store = AgentMemoryStore(root, state.resolve("sync"), workspace.evidence.artifacts, home, emptyMap())
                try {
                    val author = BoardActor(BoardActorKind.Agent, "Osprey", "codex:osprey")
                    val person = BoardActor(BoardActorKind.Person, "Owner")
                    val proposal = store.propose(AgentMemoryDraft("Scope ownership", "Actor writes are serialized", "test:scope", 1), author)
                    val files = listOf(
                        RuntimeKind.ClaudeCode to home.resolve(".claude/projects/workspace/memory/MEMORY.md"),
                        RuntimeKind.Codex to root.resolve("AGENTS.md"),
                        RuntimeKind.Gemini to root.resolve("GEMINI.md"),
                    )
                    files.forEach { (provider, file) ->
                        file.parentFile.mkdirs()
                        file.writeText("Manual ${provider.name} rule\n")
                        assertTrue(store.exportFile(provider, file.path).isEmpty())
                        assertFalse(file.readText().contains(proposal.text))
                    }
                    store.review(proposal.id, true, person)
                    files.forEach { (provider, file) ->
                        assertEquals(listOf(proposal.id), store.exportFile(provider, file.path).map { it.id })
                        val exported = file.readText()
                        assertTrue(exported.startsWith("Manual ${provider.name} rule\n"))
                        assertContains(exported, proposal.text)
                        store.exportFile(provider, file.path)
                        assertEquals(exported, file.readText())
                        val imported = store.importFile(provider, file.path, author)
                        assertEquals(listOf("Manual ${provider.name} rule"), imported.map { it.text.trim() })
                        assertTrue(imported.all { it.status == AgentMemoryStatus.Proposed })
                        assertTrue(imported.none { it.text.contains(proposal.text) })
                    }
                    assertFailsWith<IllegalArgumentException> { store.exportFile(RuntimeKind.Gemini, home.resolve("secrets.md").path) }
                    assertFailsWith<IllegalArgumentException> { store.importFile(RuntimeKind.Gemini, root.resolve("README.md").path, author) }
                } finally { store.close() }
            }
        } finally {
            root.deleteTreeSafely(within = root.parentFile)
            state.toFile().deleteTreeSafely(within = state.parent.toFile())
            home.deleteTreeSafely(within = home.parentFile)
        }
    }
}
