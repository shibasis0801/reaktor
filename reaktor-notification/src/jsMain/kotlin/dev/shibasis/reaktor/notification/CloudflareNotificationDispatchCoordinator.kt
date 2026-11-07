package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.cloudflare.CloudflareDurableObject
import dev.shibasis.reaktor.cloudflare.DurableObjectStorage
import dev.shibasis.reaktor.cloudflare.Hono
import dev.shibasis.reaktor.cloudflare.mount
import dev.shibasis.reaktor.cloudflare.durableObjectStorage
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.Reaktor
import dev.shibasis.reaktor.graph.serve
import dev.shibasis.reaktor.graph.Workflow
import dev.shibasis.reaktor.graph.step.Inline
import dev.shibasis.reaktor.graph.step.either
import dev.shibasis.reaktor.graph.step.input
import dev.shibasis.reaktor.graph.step.invoke
import dev.shibasis.reaktor.graph.step.step
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.service.ServiceAccess
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
    protected val dispatcherGraph by lazy { notificationDispatcherGraph({ storage }, ::dispatchRemote, access, ::configureGraph) }
    @JsExport.Ignore
    protected open val access: ServiceAccess get() = ServiceAccess.Undeclared
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
    access: ServiceAccess = ServiceAccess.Undeclared,
) : NotificationDispatcher(access) {
    private val delivery = graph.Node { EndpointDispatchFlow(it, storage, remote) }
    private val lastDispatch = graph.Node { DispatchStateFlow(it, storage) }
    override val deliver = super.deliver.handle { payload ->
        val result = delivery(payload)
        result.copy(statusCode = if (result.dispatched || result.dryRun) StatusCode.ACCEPTED else StatusCode.BAD_GATEWAY)
    }

    override val state = super.state.handle { lastDispatch(Unit) }
}

private class EndpointDispatchFlow(
    graph: Graph,
    storage: () -> DurableObjectStorage,
    remote: suspend (NotificationDispatchPayload) -> NotificationDispatchResult,
) : Workflow(graph, "notification-dispatcher/delivery", "Endpoint dispatch"), Inline<NotificationDispatchPayload, NotificationDispatchResult> {
    override val input by input<NotificationDispatchPayload>()
    val choice by step(input, effect = Effect.ReadOnly) {
        decision("provider", it.provider.take(64))
        decision("dry-run", it.dryRun.toString())
        it.dryRun
    }
    val rehearsed by step(input, effect = Effect.ReadOnly, on = choice.matches("a dry run") { it }) {
        it.dispatchResult(status = NotificationDeliveryStatuses.DryRunAccepted, dispatched = false, dryRun = true)
    }
    val provider by step(input, effect = Effect.ProductionReversibleWrite, on = choice.matches("live") { !it }, attributes = mapOf("external" to "push-provider")) {
        remote(it).also { result -> decision("result", result.status) }
    }
    val outcome by either(rehearsed, provider)
    val persisted by step(input, outcome, effect = Effect.ProductionReversibleWrite) { payload, result ->
        storage().putJson(LastDispatchKey, result.toState(payload = payload, updatedAt = nowIso()))
    }
    override val output = outcome
}

private class DispatchStateFlow(graph: Graph, storage: () -> DurableObjectStorage) :
    Workflow(graph, "notification-dispatcher/state", "Last dispatch"), Inline<Unit, NotificationDispatchStateSnapshot> {
    override val input by input<Unit>()
    val state by step(input, effect = Effect.LiveRead) { NotificationDispatchStateSnapshot(storage().getJson<NotificationDispatchState>(LastDispatchKey)) }
    override val output = state
}

private fun notificationDispatcherGraph(storage: () -> DurableObjectStorage,
    remote: suspend (NotificationDispatchPayload) -> NotificationDispatchResult, access: ServiceAccess, configure: (Graph) -> Unit = {}): Graph {
    Reaktor.web()
    return Graph(label = DispatcherIsland).also { graph ->
        val resources = graph.durableObjectStorage()
        graph.serve(NotificationDispatcherService(graph, { storage().traced(resources) }, remote, access))
        configure(graph)
    }
}

fun notificationDispatcherIsland(access: ServiceAccess = ServiceAccess.Undeclared, configure: (Graph) -> Unit = {}): IslandShape {
    val graph = notificationDispatcherGraph({ error("A shape has no storage") }, { error("A shape does not dispatch") }, access, configure)
    return IslandShape(DispatcherIsland, "cloudflare-durable-object", graph.shape())
}

const val DispatcherIsland = "notification-dispatcher"

private const val LastDispatchKey = "last-dispatch"

private fun nowIso(): String =
    js("new Date().toISOString()") as String
