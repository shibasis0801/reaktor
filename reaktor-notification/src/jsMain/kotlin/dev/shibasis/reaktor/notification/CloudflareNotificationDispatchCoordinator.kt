package dev.shibasis.reaktor.notification

import dev.shibasis.reaktor.cloudflare.CloudflareDurableObject
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlin.js.JsExport

@JsExport
open class CloudflareNotificationDispatchCoordinator(
    state: Any,
    env: Any,
) : CloudflareDurableObject(state, env) {
    @OptIn(DelicateCoroutinesApi::class)
    fun fetch(request: Any): Any =
        GlobalScope.promise {
            handle(request)
        }

    @JsExport.Ignore
    protected open suspend fun dispatchRemote(payload: NotificationDispatchPayload): NotificationDispatchResult =
        payload.dispatchResult(
            status = NotificationDeliveryStatuses.UnsupportedProvider,
            dispatched = false,
            dryRun = false,
        )

    private suspend fun handle(request: Any): Any {
        val incoming = incomingRequest(request)
        val method = incoming.method.uppercase()
        val path = incoming.path

        if (method == "GET" && path == "/state") {
            return json(
                NotificationDispatchStateSnapshot(
                    state = storage.getJson<NotificationDispatchState>(LAST_DISPATCH_KEY),
                ),
                headers = jsonHeaders,
            )
        }

        if (method != "POST" || path != "/deliver") {
            return text("Not Found", status = 404, headers = corsHeaders)
        }

        val payload = incoming.decode<NotificationDispatchPayload>()
        val result = if (payload.dryRun) {
            payload.dispatchResult(
                status = NotificationDeliveryStatuses.DryRunAccepted,
                dispatched = false,
                dryRun = true,
            )
        } else {
            dispatchRemote(payload)
        }

        storage.putJson(
            LAST_DISPATCH_KEY,
            result.toState(
                payload = payload,
                updatedAt = nowIso(),
            ),
        )

        return json(
            result,
            status = if (result.dispatched || result.dryRun) 202 else 502,
            headers = jsonHeaders,
        )
    }

    private companion object {
        const val LAST_DISPATCH_KEY = "last-dispatch"

        val corsHeaders =
            mapOf(
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "Content-Type, Accept, Authorization",
                "Access-Control-Max-Age" to "86400",
            )

        val jsonHeaders = corsHeaders + ("Content-Type" to "application/json; charset=utf-8")
    }
}

private fun nowIso(): String =
    js("new Date().toISOString()") as String
