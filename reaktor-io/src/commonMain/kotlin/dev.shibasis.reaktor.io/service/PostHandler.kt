package dev.shibasis.reaktor.io.service

import dev.shibasis.reaktor.core.framework.KSerializer
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.coroutines.SuspendFunction2
import kotlin.js.JsExport

@JsExport
class PostHandler<In: Request, Out: Response>(
    route: String,
    requestSerializer: KSerializer<In>,
    responseSerializer: KSerializer<Out>,
    handler: RequestHandlerBlock<In, Out>
): RequestHandler<In, Out>(
    HttpMethod.POST, route, requestSerializer, responseSerializer, handler
) {
    companion object: Factory {
        override fun <In : Request, Out : Response> invoke(
            route: String,
            requestSerializer: KSerializer<In>,
            responseSerializer: KSerializer<Out>,
            block: RequestHandlerBlock<In, Out>
        ) = PostHandler(
            route,
            requestSerializer,
            responseSerializer,
            block
        )
    }
}