package dev.shibasis.reaktor.graph.service

import kotlinx.serialization.KSerializer
import kotlin.js.JsExport

@JsExport
class PutHandler<In: Request, Out: Response>(
    route: String,
    operation: String = route,
    requestSerializer: KSerializer<In>,
    responseSerializer: KSerializer<Out>,
    handler: RequestHandlerBlock<In, Out>
): RequestHandler<In, Out>(
    ServiceEndpoint.http(HttpMethod.PUT, route, operation), requestSerializer, responseSerializer, handler
) {
    companion object: Factory {
        override fun <In : Request, Out : Response> create(
            route: String,
            operation: String,
            requestSerializer: KSerializer<In>,
            responseSerializer: KSerializer<Out>,
            block: RequestHandlerBlock<In, Out>
        ) = PutHandler(
            route,
            operation,
            requestSerializer,
            responseSerializer,
            block
        )
    }
}
