package dev.shibasis.reaktor.io.network

import dev.shibasis.reaktor.io.service.*
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.serialization.KSerializer
import kotlin.js.Promise


fun <In: Request, Out: Response> promised(
    handler: RequestHandler<In, Out>,
    request: In
) = GlobalScope.promise { handler.invoke(request) }

fun <In: Request, Out: Response> RequestHandler<In, Out>.promised(
    request: In
) = promised(this, request)

fun <In: Request, Out: Response> handler(
    method: HttpMethod,
    route: String,
    requestSerializer: KSerializer<In>,
    responseSerializer: KSerializer<Out>,
    handler: (In) -> Promise<Out>
): RequestHandler<In, Out> {
    val factory = when(method) {
        HttpMethod.GET -> GetHandler.Companion
        HttpMethod.POST -> PostHandler.Companion
        HttpMethod.PUT -> PutHandler.Companion
        HttpMethod.DELETE -> DeleteHandler.Companion
        HttpMethod.PATCH -> TODO()
        HttpMethod.OPTIONS -> TODO()
        HttpMethod.HEAD -> TODO()
    }

    return factory(route, requestSerializer, responseSerializer) { request ->
        handler(request).await()
    }
}