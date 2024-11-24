package dev.shibasis.reaktor.io.network

import dev.shibasis.reaktor.core.actor.handle
import dev.shibasis.reaktor.core.network.JsonResponse
import io.vertx.ext.web.Router

fun Router.healthCheck() {
    get(HealthCheck.PATH).handle { _ -> JsonResponse(HealthCheck.Response(true)) }
}
