package dev.shibasis.reaktor.graph.service

import dev.shibasis.reaktor.core.framework.KSerializer
import dev.shibasis.reaktor.io.network.RoutePattern
import kotlinx.serialization.KSerializer
import kotlin.js.JsExport

typealias RequestHandlerBlock<In, Out> =
        suspend RequestHandler<In, Out>.(In) -> Out

@JsExport
sealed class RequestHandler<In: Request, Out: Response>(
    val method: HttpMethod,
    val route: String,
    val requestSerializer: KSerializer<In>,
    val responseSerializer: KSerializer<Out>,
    val handler: RequestHandlerBlock<In, Out>
) {
    val routePattern = RoutePattern.from(route)

    inline fun url(request: In, vararg extraPathParams: Pair<String, String>): String =
        routePattern.fill(request.pathParams + extraPathParams)

    @JsExport.Ignore
    suspend operator fun invoke(request: In): Out = handler(request)

    interface Factory {
        operator fun<In: Request, Out: Response> invoke(
            route: String,
            requestSerializer: KSerializer<In>,
            responseSerializer: KSerializer<Out>,
            block: RequestHandlerBlock<In, Out>
        ): RequestHandler<In, Out>
    }
}


inline operator
fun <reified In: Request, reified Out: Response> RequestHandler.Factory.invoke(
    rawRoute: String,
    noinline fn: RequestHandlerBlock<In, Out>
) = invoke(
    rawRoute,
    KSerializer<In>(),
    KSerializer<Out>(),
    fn
)
