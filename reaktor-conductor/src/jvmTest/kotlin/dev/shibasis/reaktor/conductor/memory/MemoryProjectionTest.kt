package dev.shibasis.reaktor.conductor.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.conductor.workspace.BoardActor
import dev.shibasis.reaktor.conductor.workspace.BoardActorKind
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import java.nio.file.Files
import kotlin.test.*

class MemoryProjectionTest {
    private val scope = MemoryScope(MemoryScopeKind.Repository, "reaktor")
    private val person = BoardActor(BoardActorKind.Person, "owner")
    private suspend fun fixture(body: suspend (MemoryApi) -> Unit) {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koinApplication {}))
        try { body(MemoryRuntime(graph, SqliteObjectDatabase(driver, "projection")).api) }
        finally { graph.close(); driver.close() }
    }
    private fun draft() = RememberFact(scope, MemoryKind.Fact, "Scope writes", "Use actors\n<!-- reaktor-memory:end -->",
        listOf("symbol:MemoryStore", "file:MemoryStore.kt", "plan:hangar:launch#memory-store"),
        MemoryProvenance("osprey", source = "test", sourceDate = 1, sourceRevision = "first"))

    @Test fun factsLinkToCodeAndPlansAndExposeFreshness() = runBlocking { fixture { api ->
        val fact = api.remember(draft())
        MemoryGraph(listOf(fact), "first").use { projection ->
            val shape = projection.shape()
            assertEquals("matching source", shape.nodes.single { it.id == "memory:${fact.id}" }.attributes["freshness"])
            assertEquals(draft().links.toSet(), shape.wires.filter { it.consumer == "memory:${fact.id}" }.map { it.provider }.toSet())
        }
        MemoryGraph(listOf(fact), "second").use { assertEquals("stale", it.shape().nodes.first { it.id == "memory:${fact.id}" }.attributes["freshness"]) }
        MemoryGraph(listOf(fact), null).use { assertEquals("unknown", it.shape().nodes.first { it.id == "memory:${fact.id}" }.attributes["freshness"]) }
    } }

    @Test fun harnessExportPreservesManualTextAndImportsOnlyProposals() = runBlocking { fixture { api ->
        val directory = Files.createTempDirectory("memory-harness")
        val path = directory.resolve("AGENTS.md")
        try {
            Files.writeString(path, "Keep this manual rule.\n")
            val harness = MemoryHarness(api, scope, directory)
            val proposal = api.remember(draft())
            harness.export(path)
            assertFalse(Files.readString(path).contains("Scope writes"))
            api.review(ReviewFact(proposal.id, proposal.revision, true, person))
            harness.export(path)
            val exported = Files.readString(path)
            assertTrue(exported.startsWith("Keep this manual rule.\n"))
            assertContains(exported, "&lt;!-- reaktor-memory:end --&gt;")
            harness.export(path)
            assertEquals(exported, Files.readString(path))
            val imported = harness.import(path, "claude:tower")
            assertEquals(listOf("Keep this manual rule.\n\n".trim()), imported.map { it.text.trim() })
            assertTrue(imported.all { it.status == MemoryStatus.Proposed })
            assertEquals(imported, harness.import(path, "claude:tower"))
            val outside = Files.createTempFile("outside-memory", ".md")
            try { assertFailsWith<IllegalArgumentException> { harness.import(outside, "osprey") } }
            finally { Files.deleteIfExists(outside) }
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(directory) }
    } }

    @Test fun resourcesRecallReviewedFactsAndRejectForeignScopes() = runBlocking { fixture { api ->
        val fact = api.remember(draft())
        val resources = MemoryResources(api, listOf(scope))
        assertTrue(resources.list().isEmpty())
        assertTrue(resources.sessionStart(emptyList()).isEmpty())
        val kept = api.review(ReviewFact(fact.id, fact.revision, true, person))
        val uri = resources.list().single()
        assertContains(resources.read(uri), kept.id)
        assertEquals(listOf(kept), resources.sessionStart(listOf("symbol:MemoryStore")))
        assertTrue(resources.sessionStart(listOf("symbol:Unrelated")).isEmpty())
        val foreign = MemoryResources(api, listOf(MemoryScope(MemoryScopeKind.Agent, "other")))
        assertFailsWith<IllegalArgumentException> { foreign.read(uri) }
        assertFailsWith<IllegalArgumentException> { resources.read("file:///etc/passwd") }
    } }
}
