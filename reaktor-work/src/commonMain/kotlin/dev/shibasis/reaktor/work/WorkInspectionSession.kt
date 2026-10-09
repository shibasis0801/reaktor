package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.registerProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

class WorkHostBinding(val source: WorkSource, val endpoint: WorkInspectionEndpoint?, val unavailableReason: String? = null)

enum class WorkReadStatus { DISCONNECTED, UNSUPPORTED, LOADING, LIVE, PARTIAL, STALE, FAILED }

data class WorkSessionReading(val status: WorkReadStatus = WorkReadStatus.DISCONNECTED,
    val query: WorkQuery? = null, val page: WorkInspectionPage? = null, val error: String? = null)

class WorkInspectionSession(graph: Graph) : BasicNode(graph) {
    private val hostState = MutableStateFlow<List<WorkHostBinding>>(emptyList())
    val hosts: StateFlow<List<WorkHostBinding>> = hostState.asStateFlow()
    private val readState = MutableStateFlow(WorkSessionReading())
    val state: StateFlow<WorkSessionReading> = readState.asStateFlow()
    val session = registerProvider("work.session", this)
    private val mutex = Mutex()
    private var generation = 0L
    private var pending: Job? = null

    suspend fun inspect(query: WorkQuery): WorkInspectionPage {
        val endpoint = mutex.withLock { checkNotNull(hosts.value.firstOrNull { it.source == query.source }?.endpoint) { "Work host is not connected" } }
        val page = endpoint.read(query)
        mutex.withLock {
            check(hosts.value.any { it.source == query.source && it.endpoint === endpoint }) { "Work host changed during inspection" }
            require(page.source == query.source) { "Work response belongs to another host activation" }
        }
        return page
    }

    suspend fun updateHosts(hosts: List<WorkHostBinding>) = mutex.withLock { updateHostsLocked(hosts) }

    suspend fun replaceHosts(owned: Set<WorkSource>, replacements: List<WorkHostBinding>) = mutex.withLock {
        updateHostsLocked(hosts.value.filter { it.source !in owned } + replacements)
    }

    fun detachHost(binding: WorkHostBinding) { coroutineScope.launch { mutex.withLock {
        updateHostsLocked(hosts.value.filter { it !== binding })
    } } }

    private fun updateHostsLocked(hosts: List<WorkHostBinding>) {
        require(hosts.map { it.source }.distinct().size == hosts.size)
        val previous = hostState.value
        hostState.value = hosts.toList()
        val query = readState.value.query ?: return
        val endpoint = hosts.firstOrNull { it.source == query.source }?.endpoint
        if (endpoint == null || endpoint !== previous.firstOrNull { it.source == query.source }?.endpoint) {
            generation++
            pending?.cancel()
            readState.value = WorkSessionReading(WorkReadStatus.DISCONNECTED, error = "Selected host disconnected or changed activation")
        }
    }

    fun read(query: WorkQuery) {
        coroutineScope.launch {
            mutex.withLock {
                generation++
                val requested = generation
                pending?.cancel()
                val host = hosts.value.firstOrNull { it.source == query.source }
                val endpoint = host?.endpoint
                if (endpoint == null) {
                    readState.value = WorkSessionReading(if (host == null) WorkReadStatus.DISCONNECTED else WorkReadStatus.UNSUPPORTED,
                        query, error = host?.unavailableReason ?: "Host is not connected")
                    return@withLock
                }
                val previous = readState.value.page?.takeIf { it.source == query.source && readState.value.query?.view == query.view }
                readState.value = WorkSessionReading(WorkReadStatus.LOADING, query, previous)
                pending = coroutineScope.launch {
                    try {
                        endpoint.subscribe(query).collect { page ->
                            require(page.source == query.source) { "Response belongs to another host activation" }
                            mutex.withLock {
                                if (generation == requested) readState.value = WorkSessionReading(
                                    if (page.partial || page.unreadableIds.isNotEmpty()) WorkReadStatus.PARTIAL else WorkReadStatus.LIVE, query, page)
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) {
                        mutex.withLock {
                            if (generation == requested) {
                                val retained = readState.value.page ?: previous
                                readState.value = WorkSessionReading(
                                    if (failure is UnsupportedOperationException) WorkReadStatus.UNSUPPORTED else if (retained != null) WorkReadStatus.STALE else WorkReadStatus.FAILED,
                                    query, retained, failure.message ?: "Inspection failed")
                            }
                        }
                    }
                }
            }
        }
    }

    fun disconnect() { coroutineScope.launch { mutex.withLock {
        generation++
        pending?.cancel()
        readState.value = WorkSessionReading()
    } } }

    fun staleAfter(maxAgeMillis: Long, nowMillis: Long = Clock.System.now().toEpochMilliseconds()) { coroutineScope.launch { mutex.withLock {
        val reading = readState.value
        if (reading.status in setOf(WorkReadStatus.LIVE, WorkReadStatus.PARTIAL) && reading.page != null &&
            nowMillis - reading.page.observedAtMillis > maxAgeMillis) readState.value = reading.copy(status = WorkReadStatus.STALE)
    } } }

    override fun close() {
        generation++
        pending?.cancel()
        hostState.value = emptyList()
        readState.value = WorkSessionReading()
        super.close()
    }
}
