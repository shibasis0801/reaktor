package dev.shibasis.reaktor.io.service

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.RoutePattern
import dev.shibasis.reaktor.io.service.RequestHandler
import io.ktor.http.cio.Request
import io.ktor.http.cio.Response
import kotlin.js.JsExport

@JsExport
enum class HttpMethod() {
    GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD
}

@JsExport
interface BaseRequest {
    val headers: MutableMap<String, String>
    val queryParams: MutableMap<String, String>
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

@JsExport
abstract class RequestHandler<Request: BaseRequest, Response: BaseResponse>(
    val method: HttpMethod,
    val route: String
) {
    val routePattern = RoutePattern.from(route)
    inline fun url(request: Request, vararg extraPathParams: Pair<String, String>): String {
        return routePattern.fill(request.pathParams + extraPathParams)
    }

    @JsExport.Ignore
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
@JsExport
abstract class Service(val baseUrl: String = ""): Adapter<Unit>(Unit) {
    val handlers = arrayListOf<RequestHandler<*, *>>()

    @JsExport.Ignore
    inline fun <Request: BaseRequest, Response: BaseResponse> GetHandler(
        route: String,
        crossinline block: suspend RequestHandler<Request, Response>.(Request) -> Response
    ): RequestHandler<Request, Response> {
        return RequestHandler(HttpMethod.GET, route, block).also { handlers.add(it) }
    }

    @JsExport.Ignore
    inline fun <Request: BaseRequest, Response: BaseResponse> PostHandler(
        route: String,
        crossinline block: suspend RequestHandler<Request, Response>.(Request) -> Response
    ): RequestHandler<Request, Response> {
        return RequestHandler(HttpMethod.POST, route, block).also { handlers.add(it) }
    }
}








