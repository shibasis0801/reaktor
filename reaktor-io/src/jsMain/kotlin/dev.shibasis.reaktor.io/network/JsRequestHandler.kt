package dev.shibasis.reaktor.io.network

import dev.shibasis.reaktor.io.service.BaseRequest
import dev.shibasis.reaktor.io.service.BaseResponse
import dev.shibasis.reaktor.io.service.HttpMethod
import dev.shibasis.reaktor.io.service.RequestHandler
import dev.shibasis.reaktor.io.service.Service
import kotlinx.coroutines.await
import kotlin.js.Promise



@JsExport
class JsRequestHandler<Request: BaseRequest, Response: BaseResponse>(
    method: HttpMethod,
    route: String,
    val handler: (Request) -> Promise<Response>
): RequestHandler<Request, Response>(method, route) {
    @JsExport.Ignore
    override suspend fun invoke(request: Request): Response {
        return handler(request).await()
    }
}

@JsExport
fun <Request: BaseRequest, Response: BaseResponse> Service.GetHandler(
    route: String,
    handler: (Request) -> Promise<Response>
): RequestHandler<Request, Response> {
    return GetHandler(route) {
        handler(it).await()
    }
}

@JsExport
fun <Request: BaseRequest, Response: BaseResponse> Service.PostHandler(
    route: String,
    handler: (Request) -> Promise<Response>
): RequestHandler<Request, Response> {
    return PostHandler(route) {
        handler(it).await()
    }
}

