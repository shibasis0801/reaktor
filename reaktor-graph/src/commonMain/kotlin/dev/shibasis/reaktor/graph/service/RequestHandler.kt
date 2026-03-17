package dev.shibasis.reaktor.graph.service

import dev.shibasis.reaktor.core.framework.kSerializer
import dev.shibasis.reaktor.io.network.RoutePattern
import kotlinx.serialization.KSerializer
import kotlin.js.JsExport

typealias RequestHandlerBlock<In, Out> =
        suspend RequestHandler<In, Out>.(In) -> Out

@JsExport
sealed class RequestHandler<In: Request, Out: Response>(
    val endpoint: ServiceEndpoint,
    val requestSerializer: KSerializer<In>,
    val responseSerializer: KSerializer<Out>,
    val handler: RequestHandlerBlock<In, Out>
) {
    val transport: ServiceTransport
        get() = endpoint.transport

    val method: HttpMethod
        get() = endpoint.method ?: error("RequestHandler '${endpoint.operation}' is not bound to HTTP")

    val route: String
        get() = endpoint.address

    val routePattern by lazy { RoutePattern.from(route) }

    inline fun url(request: In, vararg extraPathParams: Pair<String, String>): String =
        routePattern.fill(request.pathParams + extraPathParams)

    suspend operator fun invoke(request: In): Out = handler(request)

    interface Factory {
        fun <In: Request, Out: Response> create(
            route: String,
            operation: String,
            requestSerializer: KSerializer<In>,
            responseSerializer: KSerializer<Out>,
            block: RequestHandlerBlock<In, Out>
        ): RequestHandler<In, Out>

        operator fun <In: Request, Out: Response> invoke(
            route: String,
            requestSerializer: KSerializer<In>,
            responseSerializer: KSerializer<Out>,
            block: RequestHandlerBlock<In, Out>
        ): RequestHandler<In, Out> = create(route, route, requestSerializer, responseSerializer, block)
    }
}


inline fun <reified In: Request, reified Out: Response> RequestHandler.Factory.create(
    route: String,
    operation: String = route,
    noinline fn: RequestHandlerBlock<In, Out>
) = create(
    route,
    operation,
    kSerializer<In>(),
    kSerializer<Out>(),
    fn
)
