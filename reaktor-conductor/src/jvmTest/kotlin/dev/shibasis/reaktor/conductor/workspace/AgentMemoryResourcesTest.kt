package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.memory.*
import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class AgentMemoryResourcesTest {
    private val person = BoardActor(BoardActorKind.Person, "owner")
    private val falcon = BoardActor(BoardActorKind.Agent, "Codex", "codex:falcon", agent = "agent:codex:falcon")
    private val tower = BoardActor(BoardActorKind.Agent, "Claude", "claude:tower", agent = "agent:claude:tower")

    private fun fixture(body: suspend (File, MemoryApi) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("memory-door-root").toFile()
        val directory = Files.createTempDirectory("memory-door-state")
        try { AgentWorkspace(root, directory, emptyMap()).use { body(root, it.memory.api) } }
        finally {
            root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
            directory.toFile().deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }

    private suspend fun kept(api: MemoryApi, scope: MemoryScope, title: String, sourceRevision: String? = "source-1"): MemoryFact {
        val fact = api.remember(RememberFact(scope, MemoryKind.Fact, title, "Quoted evidence for $title", listOf("symbol:Parser"),
            MemoryProvenance("falcon", source = "test:$title", sourceDate = 1, sourceRevision = sourceRevision)))
        return api.review(ReviewFact(fact.id, fact.revision, true, person))
    }

    @Test fun resourcesIncludeOnlyReviewedFactsInTheSelectedSessionScopes(): Unit = fixture { root, api ->
        val workspace = kept(api, MemoryScope(MemoryScopeKind.Workspace, root.canonicalPath), "Workspace")
        val repository = kept(api, MemoryScope(MemoryScopeKind.Repository, root.canonicalPath), "Repository")
        val plan = kept(api, MemoryScope(MemoryScopeKind.Plan, "hangar:launch"), "Plan")
        val mine = kept(api, MemoryScope(MemoryScopeKind.Agent, requireNotNull(falcon.agent)), "Mine")
        val foreign = kept(api, MemoryScope(MemoryScopeKind.Agent, requireNotNull(tower.agent)), "Foreign")
        val proposal = api.remember(RememberFact(workspace.scope, MemoryKind.Fact, "Pending", "Unreviewed assertion",
            provenance = MemoryProvenance("falcon", source = "test", sourceDate = 1)))
        val resources = AgentMemoryResources(root, api) { "source-1" }
        fun uri(fact: MemoryFact) = "memory://fact/${fact.id}"
        assertEquals(setOf(workspace, repository, plan, mine).map(::uri).toSet(), resources.list(falcon, "hangar:launch").toSet())
        assertEquals(mine, resources.read(uri(mine), falcon, null))
        for (invalid in listOf("memory://user@fact/${mine.id}", "memory://fact:80/${mine.id}", uri(mine) + "?scope=other", uri(mine) + "#other"))
            assertFailsWith<IllegalArgumentException> { resources.read(invalid, falcon, null) }
        assertFailsWith<IllegalArgumentException> { resources.read(uri(foreign), falcon, null) }
        assertFailsWith<IllegalArgumentException> { resources.read(uri(proposal), falcon, null) }
        assertFailsWith<IllegalArgumentException> { resources.read(uri(plan), falcon, "hangar:other") }
        assertFalse(uri(mine) in resources.list(tower, null))
        api.retire(RetireFact(mine.id, mine.revision, person))
        assertFalse(uri(mine) in resources.list(falcon, null))
        assertFailsWith<IllegalArgumentException> { resources.read(uri(mine), falcon, null) }
    }

    @Test fun linkedFactsUseExactReferencesAndMemoryGraphFreshness(): Unit = fixture { root, api ->
        val scope = MemoryScope(MemoryScopeKind.Workspace, root.canonicalPath)
        val matching = kept(api, scope, "Matching")
        val stale = kept(api, scope, "Stale", "old-source")
        val unknown = kept(api, scope, "Unknown", null)
        val resources = AgentMemoryResources(root, api) { "source-1" }
        assertTrue(resources.linked(listOf("symbol:Parse"), falcon, null).facts.isEmpty())
        val linked = resources.linked(listOf("symbol:Parser"), falcon, null)
        assertEquals(setOf(matching.id, stale.id, unknown.id), linked.facts.map { it.id }.toSet())
        val labels = linked.graph.nodes.associate { it.id to it.attributes["freshness"] }
        assertEquals("matching source", labels["memory:${matching.id}"])
        assertEquals("stale", labels["memory:${stale.id}"])
        assertEquals("unknown", labels["memory:${unknown.id}"])
        assertTrue(linked.facts.all { it.reviewedBy == person && it.provenance.single().source.startsWith("test:") })
        assertEquals(setOf("symbol:Parser"), linked.graph.wires.map { it.provider }.toSet())
    }
}
