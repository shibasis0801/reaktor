package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.cloudflare.CloudflareDurableObject
import dev.shibasis.reaktor.cloudflare.DurableObjectStorage
import dev.shibasis.reaktor.cloudflare.Hono
import dev.shibasis.reaktor.cloudflare.mount
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.Reaktor
import dev.shibasis.reaktor.graph.ServiceNode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.IslandShape
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.service.GetHandler
import dev.shibasis.reaktor.service.PostHandler
import kotlin.js.JsExport

@JsExport
open class CloudflareNotificationDispatchCoordinator(
    private val state: Any,
    private val env: Any,
) : CloudflareDurableObject(state, env) {
    private val app by lazy { Hono().mount(NotificationDispatcherService({ storage }, ::dispatchRemote), DispatcherIsland) }

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
    private val storage: () -> DurableObjectStorage,
    private val remote: suspend (NotificationDispatchPayload) -> NotificationDispatchResult,
) : NotificationDispatcherApi() {
    override val deliver by PostHandler<NotificationDispatchPayload, NotificationDispatchResult>("/deliver") { payload ->
        val result = if (payload.dryRun) {
            payload.dispatchResult(status = NotificationDeliveryStatuses.DryRunAccepted, dispatched = false, dryRun = true)
        } else {
            remote(payload)
        }
        storage().putJson(LastDispatchKey, result.toState(payload = payload, updatedAt = nowIso()))
        result.copy(statusCode = if (result.dispatched || result.dryRun) StatusCode.ACCEPTED else StatusCode.BAD_GATEWAY)
    }

    override val state by GetHandler<NotificationDispatchStateRequest, NotificationDispatchStateSnapshot>("/state") {
        NotificationDispatchStateSnapshot(storage().getJson<NotificationDispatchState>(LastDispatchKey))
    }
}

fun notificationDispatcherIsland(): IslandShape {
    Reaktor.web()
    val graph = Graph(label = DispatcherIsland)
    graph.ServiceNode(NotificationDispatcherService({ error("A shape has no storage") }, { error("A shape does not dispatch") }))
    return IslandShape(DispatcherIsland, "cloudflare-durable-object", graph.shape())
}

const val DispatcherIsland = "notification-dispatcher"

private const val LastDispatchKey = "last-dispatch"

private fun nowIso(): String =
    js("new Date().toISOString()") as String
