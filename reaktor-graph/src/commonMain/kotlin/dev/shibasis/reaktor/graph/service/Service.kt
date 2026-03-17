package dev.shibasis.reaktor.graph.service

import dev.shibasis.reaktor.core.framework.kSerializer
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.http
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.KSerializer
import kotlin.js.JsExport
import io.ktor.http.HttpMethod as KtorMethod


/*
A service is a typesafe way of declaring clients and servers.
todo:
1. complete documentation
2. Add support for interceptors / middleware before / after
3. Use those to implement auth, jwt, caching, etc.

*/
@JsExport
abstract class Service(
    baseUrl: String = "",
    val httpClient: HttpClient = http
) {
    val handlers = arrayListOf<RequestHandler<*, *>>()
    val baseUrl: String = baseUrl.trimEnd('/')
    private val interceptors = arrayListOf<ServiceInterceptor>()

    fun use(vararg interceptor: ServiceInterceptor): Service = apply {
        interceptors += interceptor
    }

    protected open fun serviceInterceptors(): List<ServiceInterceptor> = interceptors

    private suspend fun <In : Request, Out : Response> invokeWithInterceptors(
        phase: ServiceExecutionPhase,
        handler: RequestHandler<In, Out>,
        request: In,
        terminal: suspend (In) -> Out,
    ): Out = DefaultServiceChain(
        phase = phase,
        handler = handler,
        request = request,
        interceptors = serviceInterceptors(),
        index = 0,
        terminal = terminal,
    ).proceed()

    fun <In : Request, Out: Response> server(
        factory: RequestHandler.Factory,
        endpoint: String,
        operation: String = endpoint,
        requestSerializer: KSerializer<In>,
        responseSerializer: KSerializer<Out>,
        block: RequestHandlerBlock<In, Out>
    ): RequestHandler<In, Out> {
        lateinit var created: RequestHandler<In, Out>
        created = factory.create(baseUrl + endpoint, operation, requestSerializer, responseSerializer) { request ->
            invokeWithInterceptors(ServiceExecutionPhase.SERVER, created, request) { intercepted ->
                block(created, intercepted)
            }
        }
        handlers += created
        return created
    }

    fun <In : Request, Out : Response> client(
        factory: RequestHandler.Factory,
        route: String,
        operation: String = route,
        requestSerializer: KSerializer<In>,
        responseSerializer: KSerializer<Out>
    ): RequestHandler<In, Out> {
        lateinit var created: RequestHandler<In, Out>
        created = factory.create(route, operation, requestSerializer, responseSerializer) { request ->
            invokeWithInterceptors(ServiceExecutionPhase.CLIENT, created, request) { intercepted ->
                val fullUrl = baseUrl + created.url(intercepted)
                val ktorMethod = created.method.toKtorMethod()

                val response = httpClient.request(fullUrl) {
                    method = ktorMethod
                    headers.append(Environment.Header, intercepted.environment.name)
                    intercepted.headers.forEach { (k, v) -> headers.append(k, v) }
                    intercepted.queryParams.forEach { (k, v) -> url.parameters.append(k, v) }

                    when (method) {
                        KtorMethod.Post, KtorMethod.Put, KtorMethod.Patch -> {
                            contentType(ContentType.Application.Json)
                            setBody(json.encodeToString(requestSerializer, intercepted))
                        }
                    }
                }

                val decoded = json.decodeFromString(responseSerializer, response.bodyAsText())
                decoded.applyTransportMetadata(
                    headers = response.headers.entries().associate { (key, values) -> key to values.joinToString(", ") },
                    statusCode = StatusCode.invoke(response.status.value),
                )
                decoded
            }
        }
        return created
    }
}

inline fun <reified In : Request, reified Out: Response> Service.server(
    factory: RequestHandler.Factory,
    endpoint: String,
    operation: String = endpoint,
    noinline block: RequestHandlerBlock<In, Out>
) = server(factory, endpoint, operation, kSerializer<In>(), kSerializer<Out>(), block)

inline fun <reified In : Request, reified Out: Response> Service.client(
    factory: RequestHandler.Factory,
    endpoint: String,
    operation: String = endpoint,
) = client(factory, endpoint, operation, kSerializer<In>(), kSerializer<Out>())

inline fun <reified In: Request, reified Out: Response> Service.GetHandler(
    endpoint: String,
    operation: String = endpoint,
    noinline block: RequestHandlerBlock<In, Out>
) = server(GetHandler.Companion, endpoint, operation, block) as GetHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.GetHandler(
    endpoint: String,
    operation: String = endpoint,
) = client<In, Out>(GetHandler.Companion, endpoint, operation) as GetHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.PostHandler(
    endpoint: String,
    operation: String = endpoint,
    noinline block: RequestHandlerBlock<In, Out>
) = server(PostHandler.Companion, endpoint, operation, block) as PostHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.PostHandler(
    endpoint: String,
    operation: String = endpoint,
) = client<In, Out>(PostHandler.Companion, endpoint, operation) as PostHandler<In, Out>


inline fun <reified In: Request, reified Out: Response> Service.PutHandler(
    endpoint: String,
    operation: String = endpoint,
    noinline block: RequestHandlerBlock<In, Out>
) = server(PutHandler.Companion, endpoint, operation, block) as PutHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.PutHandler(
    endpoint: String,
    operation: String = endpoint,
) = client<In, Out>(PutHandler.Companion, endpoint, operation) as PutHandler<In, Out>


inline fun <reified In: Request, reified Out: Response> Service.DeleteHandler(
    endpoint: String,
    operation: String = endpoint,
    noinline block: RequestHandlerBlock<In, Out>
) = server(DeleteHandler.Companion, endpoint, operation, block) as DeleteHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.DeleteHandler(
    endpoint: String,
    operation: String = endpoint,
) = client<In, Out>(DeleteHandler.Companion, endpoint, operation) as DeleteHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.PatchHandler(
    endpoint: String,
    operation: String = endpoint,
    noinline block: RequestHandlerBlock<In, Out>
) = server(PatchHandler.Companion, endpoint, operation, block) as PatchHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.PatchHandler(
    endpoint: String,
    operation: String = endpoint,
) = client<In, Out>(PatchHandler.Companion, endpoint, operation) as PatchHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.OptionsHandler(
    endpoint: String,
    operation: String = endpoint,
    noinline block: RequestHandlerBlock<In, Out>
) = server(OptionsHandler.Companion, endpoint, operation, block) as OptionsHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.OptionsHandler(
    endpoint: String,
    operation: String = endpoint,
) = client<In, Out>(OptionsHandler.Companion, endpoint, operation) as OptionsHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.HeadHandler(
    endpoint: String,
    operation: String = endpoint,
    noinline block: RequestHandlerBlock<In, Out>
) = server(HeadHandler.Companion, endpoint, operation, block) as HeadHandler<In, Out>

inline fun <reified In: Request, reified Out: Response> Service.HeadHandler(
    endpoint: String,
    operation: String = endpoint,
) = client<In, Out>(HeadHandler.Companion, endpoint, operation) as HeadHandler<In, Out>
