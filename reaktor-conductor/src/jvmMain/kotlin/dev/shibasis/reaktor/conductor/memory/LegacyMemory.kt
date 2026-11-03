package dev.shibasis.reaktor.conductor.memory

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.conductor.workspace.*
import dev.shibasis.reaktor.db.core.SqliteObjectDatabase
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.di.KoinDependencyAdapter
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import java.nio.file.Files
import java.nio.file.Path

internal class LegacyMemory(private val workspace: String, private val directory: Path) : AutoCloseable {
    private val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("memory.db")}")
    val graph = Graph(dependencyAdapter = KoinDependencyAdapter(koinApplication {}), label = "Shared memory")
    val runtime = MemoryRuntime(graph, SqliteObjectDatabase(driver, "memory", journalStorePrefix = "conductor."))
    val api get() = runtime.api

    fun migrate() = runBlocking {
        Files.list(directory).use { paths -> paths.filter { it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.json")) }.toList() }
            .forEach { path ->
                val memory = ConductorJson.decodeFromString(AgentMemory.serializer(), Files.readString(path))
                if (api.get(memory.id) == null) runtime.import(memory.fact())
            }
    }

    fun values(): List<AgentMemory> = runBlocking { runtime.store.all().mapNotNull { it.legacyValue() } }

    fun get(id: String): AgentMemory? = runBlocking { api.get(id)?.legacyValue() }

    fun retain(value: AgentMemory): AgentMemory = runBlocking { runtime.import(value.fact()).legacyValue()!! }

    fun review(id: String, keep: Boolean, by: BoardActor): AgentMemory = runBlocking {
        val fact = requireNotNull(api.get(id))
        api.review(ReviewFact(id, fact.revision, keep, by)).legacyValue()!!
    }

    fun retire(id: String) = runBlocking {
        api.get(id)?.let { api.retire(RetireFact(id, it.revision, BoardActor(BoardActorKind.Person, "local-operator"))) }
        Unit
    }

    private fun AgentMemory.fact(): MemoryFact {
        val scope = when (scope) {
            AgentMemoryScope.Workspace -> MemoryScope(MemoryScopeKind.Workspace, workspace)
            AgentMemoryScope.Graph -> MemoryScope(MemoryScopeKind.Plan, "${workspace}#${origin?.graph ?: "legacy:$runId"}")
            AgentMemoryScope.Task -> MemoryScope(MemoryScopeKind.Plan, "${workspace}#${origin?.let { "${it.graph}/${it.task}" } ?: "legacy:$runId"}")
        }
        return MemoryFact(id, scope, MemoryKind.Fact, title, text, subjectRefs,
            listOf(MemoryProvenance(participant.ifBlank { "legacy" }, run = runId.takeIf(String::isNotBlank),
                event = eventId.takeIf(String::isNotBlank), source = source ?: "run:$runId#$eventId",
                sourceDate = sourceDate ?: recordedAt, sourceRevision = sourceRevision)),
            when (status) {
                AgentMemoryStatus.Proposed -> MemoryStatus.Proposed
                AgentMemoryStatus.Kept -> MemoryStatus.Kept
                AgentMemoryStatus.Dropped -> MemoryStatus.Retired
            }, 1, recordedAt, recordedAt, reviewedBy, supersedes, importance, this)
    }

    private fun MemoryFact.legacyValue(): AgentMemory? {
        if (legacy == null && (scope.kind != MemoryScopeKind.Workspace || scope.key != workspace)) return null
        val evidence = provenance.first()
        val value = legacy ?: AgentMemory(id, title, text, evidence.run.orEmpty(), evidence.event.orEmpty(), evidence.agent,
            evidence.sourceRevision, links, recordedAt, source = evidence.source, sourceDate = evidence.sourceDate)
        return value.copy(title = title, text = text, subjectRefs = links, supersedes = supersedes, importance = importance,
            status = when (status) {
                MemoryStatus.Proposed -> AgentMemoryStatus.Proposed
                MemoryStatus.Kept -> AgentMemoryStatus.Kept
                MemoryStatus.Retired -> AgentMemoryStatus.Dropped
            }, reviewedBy = reviewedBy)
    }

    override fun close() {
        graph.close()
        runBlocking { graph.coroutineScope.coroutineContext[kotlinx.coroutines.Job]?.join() }
        driver.close()
    }
}
