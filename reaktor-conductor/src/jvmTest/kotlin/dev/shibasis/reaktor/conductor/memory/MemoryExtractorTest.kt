package dev.shibasis.reaktor.conductor.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.workspace.AgentRunRecord
import dev.shibasis.reaktor.conductor.workspace.AgentRunStatus
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import kotlin.test.*

class MemoryExtractorTest {
    @Test fun completedRunsProposeIdempotentFactsAndRejectInventedLinks() = runBlocking {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koinApplication {}))
        try {
            val runtime = MemoryRuntime(graph, SqliteObjectDatabase(driver, "extract"))
            val scope = MemoryScope(MemoryScopeKind.Workspace, "workspace")
            val run = AgentRunRecord("run", "thread", RuntimeKind.Codex, "fingerprint", "Run", status = AgentRunStatus.Completed,
                startedAt = 1, updatedAt = 2, output = "Actor writes passed their checks")
            val extractor = MemoryExtractor(runtime.api) { """[{"kind":"Gotcha","title":"Actor writes","text":"Route writes through the scope actor","links":[]}]""" }
            val facts = extractor.extract(run, scope, "osprey", listOf("Use actors"))
            assertEquals(MemoryStatus.Proposed, facts.single().status)
            assertTrue(runtime.api.recall(RecallFacts(listOf(scope))).isEmpty())
            assertEquals(facts, extractor.extract(run, scope, "osprey", emptyList()))
            assertFailsWith<IllegalArgumentException> { extractor.extract(run.copy(status = AgentRunStatus.Failed), scope, "osprey", emptyList()) }
            val invented = MemoryExtractor(runtime.api) { """[{"kind":"Fact","title":"Bad","text":"Unsupported","links":["file:invented"]}]""" }
            assertFailsWith<IllegalArgumentException> { invented.extract(run, scope, "osprey", emptyList()) }
            assertEquals(1, runtime.store.all().size)
        } finally { graph.close(); driver.close() }
    }
}
