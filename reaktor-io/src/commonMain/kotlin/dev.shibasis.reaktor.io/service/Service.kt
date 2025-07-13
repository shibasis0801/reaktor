package dev.shibasis.reaktor.io.service

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.RoutePattern
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.serializer
import kotlin.js.JsExport

@JsExport
enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD
}

@JsExport
enum class Environment {
    STAGE, PROD;
    companion object {
        final val Header = "X-Environment"
        operator fun invoke(value: String) =
            if (value.lowercase() == "prod") PROD else STAGE
    }
}

@JsExport
interface BaseRequest {
    val headers: MutableMap<String, String>
    val queryParams: MutableMap<String, String>
    val pathParams: MutableMap<String, String>
    var environment: Environment

    companion object {
        operator fun invoke(
            headers: MutableMap<String, String> = mutableMapOf(),
            queryParams: MutableMap<String, String> = mutableMapOf(),
            pathParams: MutableMap<String, String> = mutableMapOf()
        ) = object: BaseRequest {
            override val headers = headers
            override val queryParams = queryParams
            override val pathParams = pathParams
            override var environment = Environment.STAGE
        }
    }
}

@JsExport
interface BaseResponse {
    val headers: MutableMap<String, String>
    var statusCode: StatusCode

    companion object {
        operator fun invoke(
            headers: MutableMap<String, String> = mutableMapOf(),
            statusCode: StatusCode = StatusCode.OK
        ) = object: BaseResponse {
            override val headers = headers
            override var statusCode = statusCode
        }
    }
}

typealias RequestHandlerBlock<Request, Response> =
        suspend RequestHandler<Request, Response>.(Request) -> Response

@JsExport
abstract class RequestHandler<Request: BaseRequest, Response: BaseResponse>(
    val method: HttpMethod,
    val route: String,
    val requestSerializer: KSerializer<Request>,
    val responseSerializer: KSerializer<Response>
) {
    val routePattern = RoutePattern.from(route)

    inline fun url(request: Request, vararg extraPathParams: Pair<String, String>): String =
        routePattern.fill(request.pathParams + extraPathParams)

    @JsExport.Ignore
    abstract suspend operator fun invoke(request: Request): Response

    companion object {
        @JsExport.Ignore
         inline operator fun <reified Request: BaseRequest, reified Response: BaseResponse> invoke(
            method: HttpMethod,
            rawRoute: String,
            crossinline block: RequestHandlerBlock<Request, Response>
        ): RequestHandler<Request, Response> = object : RequestHandler<Request, Response>(
            method,
            rawRoute,
            serializersModule.serializer<Request>(),
            serializersModule.serializer<Response>()
        ) {
            override suspend fun invoke(request: Request): Response = block(request)
        }

        val serializersModule = json.serializersModule
    }
}

// todo add base url
@JsExport
abstract class Service(val baseUrl: String = "") : Adapter<Unit>(Unit) {
    val handlers = arrayListOf<RequestHandler<*, *>>()

    @JsExport.Ignore
    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified Request : BaseRequest, reified Response: BaseResponse> GetHandler(
        route: String,
        crossinline block: RequestHandlerBlock<Request, Response>
    ): RequestHandler<Request, Response> =
        RequestHandler(HttpMethod.GET, route, block).also(handlers::add)

    @JsExport.Ignore
    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified Request: BaseRequest, reified Response : BaseResponse> PostHandler(
        route: String,
        crossinline block: RequestHandlerBlock<Request, Response>
    ): RequestHandler<Request, Response> =
        RequestHandler(HttpMethod.POST, route, block).also(handlers::add)
}


@Serializable
data class EmptyRequest(
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.STAGE
): BaseRequest

@Serializable
data class EmptyResponse(
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf(),
): BaseResponse

/*
todo

1. support arbitrary clients
2. support cloudflare workers
3. support split servers (worker + server)
4. support load balancing
5. endless.
*/

