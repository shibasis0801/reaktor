package dev.shibasis.reaktor.core.actor

import dev.shibasis.reaktor.core.framework.fromJson
import dev.shibasis.reaktor.core.network.Response
import io.ktor.http.ContentType
import io.vertx.core.impl.logging.Logger
import io.vertx.core.impl.logging.LoggerFactory
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.kotlin.coroutines.CoroutineVerticle
import kotlinx.coroutines.*

typealias Handler = suspend CoroutineScope.(routingContext: RoutingContext) -> Response

inline fun <reified T> RoutingContext.requestBody() = fromJson<T>(body().asString())

inline fun Route.handle(
    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    crossinline handler: Handler,
) = produces("application/json").handler {

    coroutineScope.launch(dispatcher) {
        val result = handler(it)
        it.response()
            .putHeader("content-type", "application/json")
            .setStatusCode(result.statusCode.code)
            .end(result.jsonData)
    }
}

open class BaseVerticle: CoroutineVerticle() {
    val scope by lazy { CoroutineScope(coroutineContext) }
    val logger: Logger = LoggerFactory.getLogger(BaseVerticle::class.java)

    inline fun Route.handle(
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
        crossinline handler: Handler,
    ) = handle(scope, dispatcher, handler)

    open fun createRouter(): Router? = null
}












