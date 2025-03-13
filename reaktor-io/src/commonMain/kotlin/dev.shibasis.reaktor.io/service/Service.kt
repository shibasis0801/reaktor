package dev.shibasis.reaktor.io.service

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.RoutePattern
import io.ktor.http.HttpMethod
import kotlin.js.JsExport

/* You design your own sealed hierarchy for Request/Response */
// todo both should have body<T>

@JsExport
interface BaseRequest {
    val headers: MutableMap<String, String>
    val queryParams: MutableMap<String, String> // todo needs encode/decode
    val pathParams: MutableMap<String, String>

    companion object {
        inline fun new(
            headers: MutableMap<String, String> = mutableMapOf(),
            queryParams: MutableMap<String, String> = mutableMapOf(),
            pathParams: MutableMap<String, String> = mutableMapOf()
        ) = object: BaseRequest {
            override val headers = headers
            override val queryParams = queryParams
            override val pathParams = pathParams
        }
    }
}

@JsExport
interface BaseResponse {
    val headers: MutableMap<String, String>
    var statusCode: StatusCode

    companion object {
        inline fun new(
            headers: MutableMap<String, String> = mutableMapOf(),
            statusCode: StatusCode = StatusCode.OK
        ) = object: BaseResponse {
            override val headers = headers
            override var statusCode = statusCode
        }
    }
}

abstract class RequestHandler<Request: BaseRequest, Response: BaseResponse>(
    val method: HttpMethod,
    val route: String
) {
    val routePattern = RoutePattern.from(route)
    inline fun url(request: Request, vararg extraPathParams: Pair<String, String>): String {
        return routePattern.fill(request.pathParams + extraPathParams)
    }

    abstract suspend operator fun invoke(request: Request): Response
    companion object {
        inline operator fun <Request: BaseRequest, Response: BaseResponse> invoke(
            method: HttpMethod,
            rawRoute: String,
            crossinline block: suspend RequestHandler<Request, Response>.(Request) -> Response
        ): RequestHandler<Request, Response> = object: RequestHandler<Request, Response>(method, rawRoute) {
            override suspend fun invoke(request: Request): Response = block(request)
        }
    }
}

// todo add base url
abstract class Service(val baseUrl: String = ""): Adapter<Unit>(Unit) {
    val handlers = arrayListOf<RequestHandler<*, *>>()

    inline fun <Request: BaseRequest, Response: BaseResponse> GetHandler(
        route: String,
        crossinline block: suspend RequestHandler<Request, Response>.(Request) -> Response
    ): RequestHandler<Request, Response> {
        return RequestHandler(HttpMethod.Get, route, block).also { handlers.add(it) }
    }

    inline fun <Request: BaseRequest, Response: BaseResponse> PostHandler(
        route: String,
        crossinline block: suspend RequestHandler<Request, Response>.(Request) -> Response
    ): RequestHandler<Request, Response> {
        return RequestHandler(HttpMethod.Post, route, block).also { handlers.add(it) }
    }
}








