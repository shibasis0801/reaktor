package dev.shibasis.reaktor.work.inspection

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.registerProvider
import dev.shibasis.reaktor.work.Work
import dev.shibasis.reaktor.work.run.RunRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlin.time.Clock

class WorkInspector(
    graph: Graph,
    private val work: Work,
    val source: WorkSource,
    private val resolveAuth: suspend () -> AuthContext?,
    private val clock: Clock = Clock.System,
    private val controlActions: Set<WorkControlAction> = emptySet(),
) : BasicNode(graph), WorkInspectionEndpoint {
    init { require(source.scope == work.scope) { "The inspection source must name the scope of its Work node" } }

    val inspection = registerProvider<WorkInspectionEndpoint>("work.inspection", this)

    private val now: Long get() = clock.now().toEpochMilliseconds()

    internal suspend fun authorize(permission: String) {
        val auth = resolveAuth()
        require(auth != null && source.scope.matches(auth)) { "Work inspection scope denied" }
        check(LocalAuthorizer.authorize(auth, AuthRequirement(permissions = setOf(PermissionRef(name = permission)))
            .inApp(source.scope.appId).allowDelegatedActor()) is AuthDecision.Allow) { "Work inspection capability denied" }
    }

    suspend fun payload(id: String): RunRecord? {
        authorize("work.inspect.payload")
        return work.store.get(source.scope, id)
    }

    override suspend fun read(query: WorkQuery): WorkInspectionPage {
        require(query.source == source) { "Work host activation changed" }
        authorize("work.inspect.metadata")
        val normalized = query.copy(cursor = null)
        val cursor = query.cursor?.let { json.decodeFromString(PageCursor.serializer(), it) }
        require(cursor == null || cursor.query == normalized) { "This cursor belongs to another inspection" }
        return when (query.view) {
            WorkView.RUNS -> runs(normalized, cursor?.position)
            WorkView.DEFINITIONS -> definitions(normalized, cursor?.position?.toInt() ?: 0)
            WorkView.HISTORY -> history(normalized, cursor?.position?.toLong() ?: 0)
        }
    }

    override fun subscribe(query: WorkQuery): Flow<WorkInspectionPage> =
        if (query.cursor != null) flow { emit(read(query)) }
        else work.store.changes(source.scope).conflate().map { read(query) }

    private suspend fun runs(query: WorkQuery, after: String?): WorkInspectionPage {
        val page = work.store.page(source.scope, after, query.limit) { record ->
            (query.workflow == null || record.workflow == query.workflow) &&
                (query.state == null || record.state == query.state) && (query.runId == null || record.id == query.runId)
        }
        val runs = page.runs.map { it.metadata() }
        val used = runs.mapTo(HashSet()) { it.workflow }
        val definitions = work.plans().values.filter { it.id in used }.map { it.metadata() }.sortedBy { it.id }
        return WorkInspectionPage(source, now, runs.joinToString { "${it.id}@${it.revision}" }.hashCode().toString(), runs = runs, definitions = definitions,
            unreadableIds = page.unreadableIds, nextCursor = page.next?.let { cursor(query, it) }, partial = page.unreadableIds.isNotEmpty(),
            completeness = if (page.unreadableIds.isEmpty()) "Scoped runs, one page" else "Partial: some runs cannot be read",
            controlActions = controlActions)
    }

    private fun definitions(query: WorkQuery, start: Int): WorkInspectionPage {
        val definitions = work.plans().values.map { it.metadata() }
            .filter { query.workflow == null || it.id == query.workflow }.sortedBy { it.id }
        require(start in 0..definitions.size) { "This cursor is past the definitions" }
        val end = minOf(definitions.size, start + query.limit)
        return WorkInspectionPage(source, now, definitions.joinToString { it.digest }.hashCode().toString(),
            definitions = definitions.subList(start, end), nextCursor = if (end < definitions.size) cursor(query, end.toString()) else null,
            completeness = "Workflows installed in this host")
    }

    private suspend fun history(query: WorkQuery, after: Long): WorkInspectionPage {
        val events = work.store.history(source.scope, after, query.limit)
        val entries = events.filter { event ->
            (query.runId == null || event.id == query.runId) && (query.workflow == null || event.record?.workflow == query.workflow) &&
                (query.state == null || event.record?.state == query.state)
        }.map { it.entry() }
        val last = events.lastOrNull()?.sequence
        return WorkInspectionPage(source, now, (last ?: after).toString(), history = entries,
            unreadableIds = events.filter { it.unreadable }.map { it.id },
            nextCursor = last?.takeIf { events.size == query.limit }?.let { cursor(query, it.toString()) },
            partial = true, completeness = "Retained journal only; changes before the journal started are not listed")
    }

    private fun cursor(query: WorkQuery, position: String) = json.encodeToString(PageCursor.serializer(), PageCursor(query, position))
}

@Serializable
private data class PageCursor(val query: WorkQuery, val position: String)
