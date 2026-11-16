package dev.shibasis.reaktor.conductor.workspace

import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal data class AgentSourceScope(
    val paths: List<String>,
    val owners: List<String>,
    val modules: List<String>,
) {
    fun overlaps(other: AgentSourceScope): Boolean =
        TaskGraphRules.overlaps(paths, other.paths) ||
            TaskGraphRules.overlaps(modules, other.owners) ||
            TaskGraphRules.overlaps(owners, other.modules)

    companion object {
        fun refs(plan: WorkPlan, item: PlanItem): List<String> = generateSequence(item) { it.parent?.let(plan::item) }
            .map { it.refs.filter(::sourceRef) }.firstOrNull { it.isNotEmpty() }.orEmpty().distinct()

        fun local(ref: String): List<String> = if (!ref.startsWith("file:")) emptyList() else
            ref.removePrefix("file:").replace('\\', '/').takeIf { it.isNotBlank() && it !in setOf("*", ".", "./") }
                ?.let { runCatching { listOf(Path.of(it).normalize().toString().replace('\\', '/')) }.getOrDefault(emptyList()) }.orEmpty()

        fun files(plan: WorkPlan, item: PlanItem, resolved: Map<String, List<String>>): List<String> =
            refs(plan, item).flatMap { resolved[it] ?: local(it) }.distinct()

        fun complete(plan: WorkPlan, item: PlanItem, resolved: Map<String, List<String>>): Boolean =
            refs(plan, item).let { refs -> refs.isNotEmpty() && refs.all { (resolved[it] ?: local(it)).isNotEmpty() } }

        fun footprint(plan: WorkPlan, item: PlanItem, resolved: Map<String, List<String>>): List<String> =
            refs(plan, item).flatMap { ref -> (resolved[ref] ?: local(ref)).let { files ->
                if (files.isEmpty()) listOf(ref) else files.map { "file:$it" }
            } }.distinct()

        private fun sourceRef(ref: String): Boolean = ref.substringBefore(':') in setOf("file", "symbol", "module")

        fun conflicts(left: List<String>, right: List<String>): Boolean {
            val sources = left.filter(::sourceRef)
            val others = right.filter(::sourceRef)
            return PlanSchedule.conflicts(sources, others) || sources.any { !it.startsWith("file:") && it in others }
        }

        private val BuildFiles = setOf("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle", "gradle.properties", "libs.versions.toml", "package.json")

        fun of(root: File, values: List<String>, resolve: (List<String>) -> List<String>): AgentSourceScope {
            if (values.isEmpty() || values.any { it.trim() in setOf("", "*", ".", "./") }) return AgentSourceScope(listOf("*"), emptyList(), emptyList())
            val paths = linkedSetOf<String>()
            val owners = linkedSetOf<String>()
            val modules = linkedSetOf<String>()
            values.forEach { value ->
                val path = value.trim().replace('\\', '/').substringBefore('*').trimEnd('/')
                val found = resolve(listOf(path))
                owners.addAll(found)
                if (':' in path && !File(path).isAbsolute) {
                    if (found.isEmpty()) paths.add("*") else modules.addAll(found)
                } else {
                    val file = File(path).let { if (it.isAbsolute) it else File(root, path) }.canonicalFile
                    paths.add(file.invariantSeparatorsPath)
                    if (file.name in BuildFiles) modules.addAll(found)
                }
            }
            return AgentSourceScope(paths.toList(), owners.toList(), modules.toList())
        }
    }
}

internal class PlanRefResolver(
    private val root: File,
    private val read: (suspend (String, JsonObject) -> JsonElement)?,
    private val modules: (List<String>) -> List<String> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val symbolLifetimeMillis: Long = 60_000,
) : AutoCloseable {
    private data class Symbol(val files: List<String>, val until: Long)
    private data class Graph(val revision: String, val paths: List<Pair<String?, String>>,
        val answers: ConcurrentHashMap<String, List<String>> = ConcurrentHashMap())
    private data class Pages(val revision: String, var count: Int = 0, var next: Int = 0,
        val paths: LinkedHashSet<Pair<String?, String>> = LinkedHashSet())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val symbols = ConcurrentHashMap<String, Symbol>()
    @Volatile private var graph: Graph? = null
    private val graphLock = Any()
    private var graphRevision: String? = null
    private var pages: Pages? = null
    private var loading: Deferred<Graph?>? = null
    private val symbolLoads = HashMap<String, Deferred<List<String>>>()

    fun cached(refs: List<String>): Map<String, List<String>> {
        val current = graph
        return refs.distinct().associateWith { ref ->
            when (ref.substringBefore(':')) {
                "file" -> local(ref)
                "symbol" -> symbols[ref]?.takeIf { it.until > clock() }?.files.orEmpty()
                "module" -> moduleFolders(ref).ifEmpty { current?.answers?.get(ref).orEmpty() }
                else -> emptyList()
            }
        }
    }

    suspend fun resolve(refs: List<String>, deadlineNanos: Long = System.nanoTime() + WaitNanos): Map<String, List<String>> {
        val needed = refs.distinct()
        val result = needed.associateWith { ref ->
            when (ref.substringBefore(':')) {
                "file" -> local(ref)
                "module" -> moduleFolders(ref)
                "symbol" -> symbols[ref]?.takeIf { it.until > clock() }?.files.orEmpty()
                else -> emptyList()
            }
        }.toMutableMap()
        val moduleRefs = needed.filter { it.startsWith("module:") && result.getValue(it).isEmpty() }
        val graphRequest = if (moduleRefs.isEmpty()) null else graphRequest()
        val symbolRequests = needed.filter { it.startsWith("symbol:") && symbols[it]?.takeIf { value -> value.until > clock() } == null }
            .associateWith(::symbolRequest)
        val remainingMillis = ((deadlineNanos - System.nanoTime()) / 1_000_000).coerceAtLeast(0)
        if (remainingMillis > 0) withTimeoutOrNull(remainingMillis) {
            graphRequest?.await()
            symbolRequests.values.forEach { it.await() }
        }
        if (graphRequest?.isCompleted == true && !graphRequest.isCancelled) graphRequest.await()?.let { current ->
            moduleRefs.forEach { ref ->
                result[ref] = current.answers[ref] ?: moduleFiles(ref, current.paths).also { files ->
                    if (files.isNotEmpty()) current.answers[ref] = files
                }
            }
        }
        symbolRequests.forEach { (ref, request) ->
            if (request.isCompleted && !request.isCancelled) result[ref] = request.await()
        }
        return result
    }

    private fun symbolRequest(ref: String): Deferred<List<String>> = synchronized(graphLock) {
        symbolLoads[ref]?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) {
            try {
                val answer = answer("atlas_read", buildJsonObject {
                    put("name", ref.substringAfter(':')); put("part", "declaration"); put("budget", 1000)
                })
                val sources = listOfNotNull(answer) + (answer?.get("also") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                sources.mapNotNull { it["path"]?.jsonPrimitive?.contentOrNull?.let(::normalized) }.distinct().also { files ->
                    if (answer != null) symbols[ref] = Symbol(files, clock() + symbolLifetimeMillis)
                }
            } finally { synchronized(graphLock) { symbolLoads.remove(ref) } }
        }.also { symbolLoads[ref] = it }
    }.also { it.start() }

    private fun graphRequest(): Deferred<Graph?> = synchronized(graphLock) {
        loading?.takeUnless { it.isCompleted } ?: scope.async(start = CoroutineStart.LAZY) { graph() }.also { loading = it }
    }.also { it.start() }

    private fun local(ref: String): List<String> = AgentSourceScope.local(ref).mapNotNull(::normalized)

    private fun normalized(path: String): String? = path.takeIf { it.isNotBlank() && it !in setOf("*", ".", "./") }?.let {
        runCatching {
            File(it.replace('\\', '/')).let { file -> if (file.isAbsolute) file else File(root, file.path) }
                .canonicalFile.relativeToOrSelf(root.canonicalFile).invariantSeparatorsPath
        }.getOrNull()
    }

    private suspend fun answer(name: String, args: JsonObject): JsonObject? = try {
        read?.invoke(name, args) as? JsonObject
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun graph(): Graph? {
        val probe = answer("atlas_graph", buildJsonObject { put("page", 0); put("chars", 20_000) }) ?: return null
        val revision = probe["revision"]?.jsonPrimitive?.contentOrNull ?: return null
        if (graphRevision != revision) {
            graphRevision = revision
            graph = null
            pages = null
        }
        if (probe["ready"]?.jsonPrimitive?.booleanOrNull != true || (probe["pages"]?.jsonPrimitive?.intOrNull ?: 0) < 1) return null
        graph?.takeIf { it.revision == revision }?.let { return it }
        val progress = pages?.takeIf { it.revision == revision } ?: Pages(revision).also { pages = it }
        val loaded = loadGraph(progress) ?: return null
        graph = loaded
        pages = null
        return loaded
    }

    private suspend fun loadGraph(progress: Pages): Graph? {
        while (progress.count == 0 || progress.next < progress.count) {
            currentCoroutineContext().ensureActive()
            val page = answer("atlas_graph", buildJsonObject {
                put("page", progress.next); put("revision", progress.revision); put("chars", 250_000)
            })?.takeIf { it["ready"]?.jsonPrimitive?.booleanOrNull == true && it["revision"]?.jsonPrimitive?.contentOrNull == progress.revision } ?: return null
            val count = page["pages"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 } ?: return null
            val nodes = (page["shape"] as? JsonObject)?.get("nodes") as? JsonArray ?: return null
            val paths = nodes.mapNotNull { node ->
                val attributes = (node as? JsonObject)?.get("attributes") as? JsonObject ?: return@mapNotNull null
                val path = attributes["path"]?.jsonPrimitive?.contentOrNull?.let(::normalized) ?: return@mapNotNull null
                attributes["module"]?.jsonPrimitive?.contentOrNull to path
            }
            progress.count = count
            progress.paths.addAll(paths)
            progress.next++
        }
        return Graph(progress.revision, progress.paths.toList())
    }

    private fun moduleFolders(ref: String): List<String> = runCatching { modules(listOf(ref.substringAfter(':'))) }
        .getOrDefault(emptyList()).mapNotNull { normalized(File(root.parentFile, it).path) }.distinct()

    private fun moduleFiles(ref: String, paths: List<Pair<String?, String>>): List<String> {
        val target = ref.substringAfter(':')
        val folders = listOfNotNull(normalized(target), normalized(File(root.parentFile, target).path))
        return paths.mapNotNull { (label, path) ->
            path.takeIf { label == target || label == target.removePrefix(":") || folders.any { path.startsWith("$it/") } }
        }.distinct()
    }

    override fun close() { scope.cancel() }

    companion object { const val WaitNanos = 3_000_000_000L }
}
