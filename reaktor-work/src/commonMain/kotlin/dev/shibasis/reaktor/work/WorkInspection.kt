package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.auth.kernel.*
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Clock

@Serializable
data class WorkLinks(val traceId: String? = null, val deploymentId: String? = null, val resourceIds: Set<String> = emptySet())

@Serializable
data class WorkSource(val hostId: String, val activation: String, val scope: WorkScope) {
    init { require(hostId.isNotBlank() && activation.isNotBlank() && hostId.length <= 256 && activation.length <= 256) }
}

@Serializable
enum class WorkView { RECORDS, RUNS, DEFINITIONS, HISTORY }

@Serializable
data class WorkQuery(
    val source: WorkSource, val view: WorkView = WorkView.RECORDS, val limit: Int = 64,
    val cursor: String? = null, val definition: String? = null, val state: WorkState? = null,
    val recordId: String? = null,
) { init { require(limit in 1..128 && (cursor?.length ?: 0) <= 8192) } }

@Serializable
data class WorkMetadata(
    val id: String, val definition: String, val definitionVersion: Int, val state: WorkState,
    val revision: Long, val createdAtMillis: Long, val nextRunAtMillis: Long,
    val attempt: Int, val maxAttempts: Int, val fence: Long, val leaseOwner: String?, val leaseUntilMillis: Long?,
    val hasCheckpoint: Boolean, val hasReceipt: Boolean, val handoff: WorkHandoff?, val links: WorkLinks,
)

fun WorkRecord.metadata() = WorkMetadata(intent.id, intent.definition, intent.definitionVersion, state, revision,
    createdAtMillis, nextRunAtMillis, attempt, intent.maxAttempts, fence, leaseOwner, leaseUntilMillis,
    checkpoint != null, receipt != null, handoff, intent.links)

@Serializable
data class WorkHistoryEntry(
    val sequence: Long, val id: String, val atMillis: Long, val revision: Long?, val state: WorkState?,
    val attempt: Int?, val fence: Long?, val deleted: Boolean = false, val unreadable: Boolean = false,
    val definition: String? = null,
)

@Serializable
data class WorkDagNode(val id: String, val outputSchema: String, val definition: String,
    val version: Int?, val payloadSchema: String?, val dependencies: List<String>)

@Serializable
data class WorkDagDefinition(val id: String, val version: Int, val inputSchema: String, val nodes: List<WorkDagNode>) {
    companion object {
        fun decode(run: WorkGraphRun): WorkDagDefinition = decodeManifest(run.definition, run.version, run.manifest).also { definition ->
            require(run.target in definition.nodes.map { it.id })
            require(run.completed.all { id -> definition.nodes.any { it.id == id } })
            require(run.selectedBranches.all { (id, selected) -> definition.nodes.any { it.id == id && it.definition == "branch" && selected in it.dependencies.drop(1) } })
        }
        fun decodeManifest(id: String, version: Int, manifest: String): WorkDagDefinition {
            val lines = json.decodeFromString(ListSerializer(String.serializer()), manifest)
            require(lines.size in 1..65)
            val prefix = "$id:$version:"
            require(lines.first().startsWith(prefix) && lines.first().length > prefix.length)
            val seen = mutableSetOf<String>()
            val nodes = lines.drop(1).map { encoded ->
                val fields = json.decodeFromString(ListSerializer(String.serializer()), encoded)
                require(fields.size >= 5 && fields[0].isNotBlank() && fields[0] !in seen)
                val dependencies = fields.drop(5)
                require(dependencies.all { it in seen })
                val version = fields[3].takeIf(String::isNotEmpty)?.toInt()
                require(fields[2] != "branch" || dependencies.size == 3)
                seen += fields[0]
                WorkDagNode(fields[0], fields[1], fields[2], version, fields[4].takeIf(String::isNotEmpty), dependencies)
            }
            return WorkDagDefinition(id, version, lines.first().removePrefix(prefix), nodes)
        }
    }
}

@Serializable
data class WorkRunMetadata(val id: String, val definition: WorkDagDefinition, val target: String,
    val state: WorkState, val revision: Long, val completed: Set<String>, val selectedBranches: Map<String, String>, val links: WorkLinks = WorkLinks())

@Serializable
data class WorkProviderObservation(val workId: String, val provider: String, val executionId: String,
    val status: String, val observedAtMillis: Long, val source: String)

@Serializable
data class WorkInspectionPage(
    val source: WorkSource, val observedAtMillis: Long, val revision: String,
    val records: List<WorkMetadata> = emptyList(), val runs: List<WorkRunMetadata> = emptyList(),
    val definitions: List<WorkDagDefinition> = emptyList(), val history: List<WorkHistoryEntry> = emptyList(),
    val providers: List<WorkProviderObservation> = emptyList(), val unreadableIds: List<String> = emptyList(),
    val nextCursor: String? = null, val completeness: String, val partial: Boolean = false,
    val controlActions: Set<WorkControlAction> = emptySet(),
)

fun interface WorkInspectionEndpoint {
    suspend fun read(query: WorkQuery): WorkInspectionPage
    fun subscribe(query: WorkQuery): Flow<WorkInspectionPage> = flow {
        do {
            emit(read(query))
            if (query.cursor != null) break
            delay(15_000)
        } while (true)
    }
}

interface WorkGraphInspectionSource {
    suspend fun inspectRuns(): Pair<List<WorkGraphRun>, List<String>>
    fun definition(): WorkDagDefinition? = null
}

@Serializable
private data class WorkPageCursor(val query: WorkQuery, val revision: String, val position: String)

class WorkInspector(
    graph: Graph, private val runtime: WorkRuntime, val source: WorkSource,
    private val resolveAuth: suspend () -> AuthContext?,
    private val providerRead: suspend (List<WorkMetadata>) -> List<WorkProviderObservation> = { emptyList() },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val controlActions: Set<WorkControlAction> = emptySet(),
) : BasicNode(graph), WorkInspectionEndpoint {
    init { require(graph === runtime.graph && source.scope == runtime.scope) }
    val inspection = registerProvider<WorkInspectionEndpoint>("work.inspection", this)

    internal suspend fun authorize(permission: String) {
        val auth = resolveAuth()
        require(auth != null && source.scope.matches(auth)) { "Work inspection scope denied" }
        check(LocalAuthorizer.authorize(auth, AuthRequirement(permissions = setOf(PermissionRef(name = permission)))
            .inApp(source.scope.appId).allowDelegatedActor()) is AuthDecision.Allow) { "Work inspection capability denied" }
    }

    suspend fun payload(id: String): WorkRecord? {
        authorize("work.inspect.payload")
        return runtime.store.get(source.scope, id)?.also { require(it.intent.scope == source.scope && it.intent.id == id) }
    }

    override suspend fun read(query: WorkQuery): WorkInspectionPage {
        require(query.source == source) { "Work host activation changed" }
        authorize("work.inspect.metadata")
        val normalized = query.copy(cursor = null)
        val cursor = query.cursor?.let { json.decodeFromString(WorkPageCursor.serializer(), it) }
        require(cursor == null || cursor.query == normalized) { "Cursor belongs to another inspection" }
        if (query.view == WorkView.HISTORY) {
            val rows = runtime.store.inspectHistory(source.scope, cursor?.position?.toLong() ?: 0, query.limit)
            val history = rows.filter { (query.recordId == null || it.id == query.recordId) &&
                (query.definition == null || it.definition == query.definition) && (query.state == null || it.state == query.state) }
            val next = rows.lastOrNull()?.sequence?.toString()?.takeIf { rows.size == query.limit }
            return WorkInspectionPage(source, now(), rows.lastOrNull()?.sequence?.toString() ?: cursor?.revision ?: "0",
                history = history, unreadableIds = rows.filter { it.unreadable }.map { it.id },
                nextCursor = next?.let { json.encodeToString(WorkPageCursor(normalized, it, it)) },
                partial = true, completeness = "Retained journal only; pre-journal coverage and retention start unknown")
        }
        if (query.view != WorkView.RECORDS) {
            val sources = runtime.providerPorts[Type.create(WorkGraphInspectionSource::class)]?.values.orEmpty()
            val snapshots = sources.map { port -> port.suspended { (this as WorkGraphInspectionSource).inspectRuns() } }
            val unreadable = snapshots.flatMap { it.second }.toMutableList()
            val runs = snapshots.flatMap { it.first }.mapNotNull { run ->
                try { WorkRunMetadata(run.id, WorkDagDefinition.decode(run), run.target, run.state, run.revision, run.completed, run.selectedBranches, run.links) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { unreadable += "${run.definition}/${run.id}"; null }
            }.filter { (query.definition == null || it.definition.id == query.definition) && (query.state == null || it.state == query.state) }
                .sortedWith(compareBy({ it.definition.id }, { it.id }))
            val definitions = (runs.map { it.definition } + sources.mapNotNull { port ->
                port.suspended { (this as WorkGraphInspectionSource).definition() }
            }).filter { query.definition == null || it.id == query.definition }.distinct().sortedWith(compareBy({ it.id }, { it.version }))
            val revision = (json.encodeToString(runs) + json.encodeToString(definitions)).hashCode().toString()
            require(cursor == null || cursor.revision == revision) { "Work revision changed; refresh inspection" }
            val start = cursor?.position?.toInt() ?: 0
            require(start in 0..(if (query.view == WorkView.RUNS) runs.size else definitions.size))
            val total = if (query.view == WorkView.RUNS) runs.size else definitions.size
            val end = minOf(total, start + query.limit)
            return WorkInspectionPage(source, now(), revision,
                runs = if (query.view == WorkView.RUNS) runs.subList(start, end) else emptyList(),
                definitions = if (query.view == WorkView.DEFINITIONS) definitions.subList(start, end) else emptyList(),
                unreadableIds = unreadable, nextCursor = if (end < total) json.encodeToString(WorkPageCursor(normalized, revision, end.toString())) else null,
                partial = true, completeness = "Persisted runs of installed graph definitions; removed or uninstalled definitions are not enumerated")
        }
        val snapshot = runtime.store.inspect(source.scope)
        val records = snapshot.records.filter { (query.definition == null || it.intent.definition == query.definition) &&
            (query.state == null || it.state == query.state) && (query.recordId == null || it.intent.id == query.recordId) }
            .sortedBy { it.intent.id }.map { it.metadata() }
        val revision = json.encodeToString(records).hashCode().toString()
        require(cursor == null || cursor.revision == revision) { "Work revision changed; refresh inspection" }
        val start = cursor?.position?.toInt() ?: 0
        require(start in 0..records.size)
        val end = minOf(records.size, start + query.limit)
        val page = records.subList(start, end)
        return WorkInspectionPage(source, now(), revision, records = page,
            providers = providerRead(page).filter { observation -> page.any { it.id == observation.workId && it.handoff?.id == observation.executionId && it.handoff.provider == observation.provider } },
            unreadableIds = snapshot.unreadableIds,
            nextCursor = if (end < records.size) json.encodeToString(WorkPageCursor(normalized, revision, end.toString())) else null,
            partial = snapshot.unreadableIds.isNotEmpty(), completeness = if (snapshot.unreadableIds.isEmpty()) "Scoped WorkStore snapshot; page only" else "Partial: unreadable records",
            controlActions = controlActions)
    }
}
