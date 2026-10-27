package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.cloudflare.CloudflareDurableObject
import dev.shibasis.reaktor.cloudflare.DurableObjectStorage
import dev.shibasis.reaktor.cloudflare.Hono
import dev.shibasis.reaktor.cloudflare.mount
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.Reaktor
import dev.shibasis.reaktor.graph.serve
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.stage
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.decision
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.IslandShape
import dev.shibasis.reaktor.graph.core.shape
import kotlin.js.JsExport

@JsExport
open class CloudflareNotificationDispatchCoordinator(
    private val state: Any,
    private val env: Any,
) : CloudflareDurableObject(state, env) {
    @JsExport.Ignore
    protected val dispatcherGraph by lazy { notificationDispatcherGraph({ storage }, ::dispatchRemote, ::configureGraph) }
    @JsExport.Ignore
    protected open fun configureGraph(graph: Graph) {}
    private val app by lazy { Hono().mount(dispatcherGraph) }

    open fun fetch(request: Any): Any = app.fetch(request, env.asDynamic(), state.asDynamic())

    @JsExport.Ignore
    protected open suspend fun dispatchRemote(payload: NotificationDispatchPayload): NotificationDispatchResult =
        payload.dispatchResult(
            status = NotificationDeliveryStatuses.UnsupportedProvider,
            dispatched = false,
            dryRun = false,
        )
}

class NotificationDispatcherService(
    graph: Graph,
    private val storage: () -> DurableObjectStorage,
    private val remote: suspend (NotificationDispatchPayload) -> NotificationDispatchResult,
) : NotificationDispatcher() {
    private val flow = graph.Node { Workflow(it, "notification-dispatcher/delivery", "Endpoint dispatch") }
    private val choose = flow.activity<NotificationDispatchPayload, Boolean>("provider-choice", Effect.ReadOnly) {
        decision("provider", it.provider.take(64))
        decision("dry-run", it.dryRun.toString())
        it.dryRun
    }
    private val provider = flow.activity<NotificationDispatchPayload, NotificationDispatchResult>("provider", Effect.ProductionReversibleWrite,
        mapOf("external" to "push-provider")) { remote(it).also { result -> decision("result", result.status) } }
    private val persist = flow.stage<NotificationDispatchResult, Unit>("persist-outcome", Effect.ProductionReversibleWrite)
    private val snapshot = flow.activity<Unit, NotificationDispatchStateSnapshot>("state", Effect.LiveRead) {
        NotificationDispatchStateSnapshot(storage().getJson<NotificationDispatchState>(LastDispatchKey))
    }
    override val deliver = super.deliver.handle { payload ->
        val result = if (choose(payload)) {
            payload.dispatchResult(status = NotificationDeliveryStatuses.DryRunAccepted, dispatched = false, dryRun = true)
        } else {
            provider(payload)
        }
        persist(result) { storage().putJson(LastDispatchKey, it.toState(payload = payload, updatedAt = nowIso())) }
        result.copy(statusCode = if (result.dispatched || result.dryRun) StatusCode.ACCEPTED else StatusCode.BAD_GATEWAY)
    }

    override val state = super.state.handle { snapshot(Unit) }
}

private fun notificationDispatcherGraph(storage: () -> DurableObjectStorage,
    remote: suspend (NotificationDispatchPayload) -> NotificationDispatchResult, configure: (Graph) -> Unit = {}): Graph {
    Reaktor.web()
    return Graph(label = DispatcherIsland).also { graph ->
        graph.serve(NotificationDispatcherService(graph, storage, remote))
        configure(graph)
    }
}

fun notificationDispatcherIsland(configure: (Graph) -> Unit = {}): IslandShape {
    val graph = notificationDispatcherGraph({ error("A shape has no storage") }, { error("A shape does not dispatch") }, configure)
    return IslandShape(DispatcherIsland, "cloudflare-durable-object", graph.shape())
}

const val DispatcherIsland = "notification-dispatcher"

private const val LastDispatchKey = "last-dispatch"

private fun nowIso(): String =
    js("new Date().toISOString()") as String
