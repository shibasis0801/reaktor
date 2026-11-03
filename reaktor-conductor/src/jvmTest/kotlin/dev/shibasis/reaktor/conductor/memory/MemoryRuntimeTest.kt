package dev.shibasis.reaktor.conductor.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.conductor.workspace.BoardActor
import dev.shibasis.reaktor.conductor.workspace.BoardActorKind
import dev.shibasis.reaktor.db.DatabaseEvent
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import org.koin.dsl.koinApplication
import java.nio.file.Files
import kotlin.test.*

class MemoryRuntimeTest {
    private val scope = MemoryScope(MemoryScopeKind.Repository, "reaktor")
    private val person = BoardActor(BoardActorKind.Person, "owner")
    private fun draft(agent: String = "agent:codex:osprey", scope: MemoryScope = this.scope) = RememberFact(scope,
        MemoryKind.Gotcha, "Atomic memory", "Use the scope actor for writes", listOf("symbol:MemoryStore"),
        MemoryProvenance(agent, source = "plan:hangar:launch#memory-actors", sourceDate = 1))

    private fun graph() = Graph(dependencyAdapter = KoinDependencyAdapter(koinApplication {}), label = "Memory test")

    @Test fun factsRequireReviewAndSurviveReopening() = runBlocking {
        val path = Files.createTempFile("memory-runtime", ".db")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
            val first = graph()
            val runtime = MemoryRuntime(first, SqliteObjectDatabase(driver, "memory-test"))
            val fact = runtime.api.remember(draft())
            assertEquals(MemoryStatus.Proposed, fact.status)
            assertTrue(runtime.api.recall(RecallFacts(listOf(scope))).isEmpty())
            assertFailsWith<IllegalArgumentException> {
                runtime.api.review(ReviewFact(fact.id, fact.revision, true, BoardActor(BoardActorKind.Agent, "agent")))
            }
            val kept = runtime.api.review(ReviewFact(fact.id, fact.revision, true, person))
            assertEquals(listOf(kept), runtime.api.recall(RecallFacts(listOf(scope))))
            first.close()
            driver.close()
            val reopenedDriver = JdbcSqliteDriver("jdbc:sqlite:$path")
            val reopenedGraph = graph()
            try {
                val reopened = MemoryRuntime(reopenedGraph, SqliteObjectDatabase(reopenedDriver, "memory-test"))
                assertEquals(kept, reopened.api.get(kept.id))
                assertEquals(listOf(kept), reopened.api.search(MemoryQuery(listOf(scope), "actor", link = "symbol:MemoryStore")))
                assertTrue(reopened.api.search(MemoryQuery(listOf(MemoryScope(MemoryScopeKind.Agent, "other")))).isEmpty())
            } finally { reopenedGraph.close(); reopenedDriver.close() }
        } finally { Files.deleteIfExists(path) }
    }

    @Test fun concurrentAgentsFoldProvenanceAndRejectStaleRevisions() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val graph = graph()
        try {
            val runtime = MemoryRuntime(graph, SqliteObjectDatabase(driver, "memory-concurrent"))
            val facts = coroutineScope { (1..40).map { agent -> async { runtime.api.remember(draft("agent:codex:$agent")) } }.awaitAll() }
            assertEquals(1, facts.map { it.id }.distinct().size)
            val fact = runtime.api.get(facts.first().id)!!
            assertEquals(40, fact.provenance.size)
            assertEquals(40L, fact.revision)
            assertEquals(fact, runtime.api.remember(draft("agent:codex:40")))
            assertFailsWith<IllegalArgumentException> { runtime.api.review(ReviewFact(fact.id, 1, true, person)) }
            assertEquals(MemoryStatus.Proposed, runtime.api.get(fact.id)!!.status)
            val kept = runtime.api.review(ReviewFact(fact.id, fact.revision, true, person))
            val retired = runtime.api.retire(RetireFact(kept.id, kept.revision, person))
            assertEquals(MemoryStatus.Retired, retired.status)
            assertTrue(runtime.api.recall(RecallFacts(listOf(scope))).isEmpty())
            assertTrue(graph.shape().nodes.any { it.kind.name == "Actor" })
            assertTrue(graph.shape().nodes.any { it.label == "reaktor.memory" })
        } finally { graph.close(); driver.close() }
    }

    @Test fun committedFactsPublishChangesAndFailedTransitionsDoNotWrite() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val graph = graph()
        try {
            val database = SqliteObjectDatabase(driver, "memory-events", journalStorePrefix = "conductor.")
            val runtime = MemoryRuntime(graph, database)
            val changed = async(start = CoroutineStart.UNDISPATCHED) { database.events.first { it is DatabaseEvent.Invalidated } }
            val fact = runtime.api.remember(draft())
            assertEquals(fact.id, (changed.await() as DatabaseEvent.Invalidated).key)
            assertEquals(1, database.readChanges(runtime.store.objects.storeName, 0, 20).size)
            assertFailsWith<IllegalArgumentException> { runtime.api.revise(ReviseFact(fact.id, 99, draft().copy(text = "Changed"))) }
            assertEquals(fact, runtime.api.get(fact.id))
            assertEquals(1, database.readChanges(runtime.store.objects.storeName, 0, 20).size)
        } finally { graph.close(); driver.close() }
    }

    @Test fun invalidFactsFailBeforePersistence() {
        assertFailsWith<IllegalArgumentException> { MemoryScope(MemoryScopeKind.Workspace, "") }
        assertFailsWith<IllegalArgumentException> { MemoryProvenance("", source = "test", sourceDate = 1) }
        assertFailsWith<IllegalArgumentException> { MemoryQuery(emptyList()) }
    }
}
