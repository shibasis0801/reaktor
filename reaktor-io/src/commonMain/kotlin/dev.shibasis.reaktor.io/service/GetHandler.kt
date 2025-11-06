package dev.shibasis.reaktor.io.service

import kotlinx.serialization.KSerializer
import kotlin.js.JsExport

@JsExport
class GetHandler<In: Request, Out: Response>(
    route: String,
    requestSerializer: KSerializer<In>,
    responseSerializer: KSerializer<Out>,
    handler: RequestHandlerBlock<In, Out>
): RequestHandler<In, Out>(
    HttpMethod.GET, route, requestSerializer, responseSerializer, handler
) {
    companion object: Factory {
        override fun <In : Request, Out : Response> invoke(
            route: String,
            requestSerializer: KSerializer<In>,
            responseSerializer: KSerializer<Out>,
            block: RequestHandlerBlock<In, Out>
        ) = GetHandler(
            route,
            requestSerializer,
            responseSerializer,
            block
        )
    }
}