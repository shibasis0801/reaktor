package dev.shibasis.reaktor.io.service

import dev.shibasis.reaktor.core.framework.KSerializer
import dev.shibasis.reaktor.core.framework.json
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

@JsExport
abstract class Service(
    baseUrl: String = "",
    val httpClient: HttpClient = http
) {
    val handlers = arrayListOf<RequestHandler<*, *>>()
    val baseUrl: String = baseUrl.trimEnd('/')

    fun <In : Request, Out: Response> server(
        factory: RequestHandler.Factory,
        endpoint: String,
        requestSerializer: KSerializer<In>,
        responseSerializer: KSerializer<Out>,
        block: RequestHandlerBlock<In, Out>
    ) = factory(baseUrl + endpoint, requestSerializer, responseSerializer, block).also(handlers::add)

    fun <In : Request, Out : Response> client(
        factory: RequestHandler.Factory,
        route: String,
        requestSerializer: KSerializer<In>,
        responseSerializer: KSerializer<Out>
    ): RequestHandler<In, Out> {
        return factory(route, requestSerializer, responseSerializer) { request ->
            val fullUrl = baseUrl + url(request)
            val ktorMethod = method.toKtorMethod()

            val response = httpClient.request(fullUrl) {
                method = ktorMethod
                headers.append(Environment.Header, request.environment.name)
                request.headers.forEach { (k, v) -> headers.append(k, v) }
                request.queryParams.forEach { (k, v) -> url.parameters.append(k, v) }

                when (method) {
                    KtorMethod.Post, KtorMethod.Put, KtorMethod.Patch -> {
                        contentType(ContentType.Application.Json)
                        setBody(json.encodeToString(requestSerializer, request))
                    }
                }
            }

            json.decodeFromString(responseSerializer, response.bodyAsText())
        }
    }
}

inline fun <reified In : Request, reified Out: Response> Service.server(
    factory: RequestHandler.Factory,
    endpoint: String,
    noinline block: RequestHandlerBlock<In, Out>
) = server(factory, endpoint, KSerializer<In>(), KSerializer<Out>(), block)

inline fun <reified In : Request, reified Out: Response> Service.client(
    factory: RequestHandler.Factory,
    endpoint: String,
) = client(factory, endpoint, KSerializer<In>(), KSerializer<Out>())

inline fun <reified In: Request, reified Out: Response> Service.GetHandler(
    endpoint: String,
    noinline block: RequestHandlerBlock<In, Out>
) = server(GetHandler.Companion, endpoint, block)

inline fun <reified In: Request, reified Out: Response> Service.GetHandler(
    endpoint: String
) = client<In, Out>(GetHandler.Companion, endpoint)

inline fun <reified In: Request, reified Out: Response> Service.PostHandler(
    endpoint: String,
    noinline block: RequestHandlerBlock<In, Out>
) = server(PostHandler.Companion, endpoint, block)

inline fun <reified In: Request, reified Out: Response> Service.PostHandler(
    endpoint: String
) = client<In, Out>(PostHandler.Companion, endpoint)


inline fun <reified In: Request, reified Out: Response> Service.PutHandler(
    endpoint: String,
    noinline block: RequestHandlerBlock<In, Out>
) = server(PutHandler.Companion, endpoint, block)

inline fun <reified In: Request, reified Out: Response> Service.PutHandler(
    endpoint: String
) = client<In, Out>(PutHandler.Companion, endpoint)


inline fun <reified In: Request, reified Out: Response> Service.DeleteHandler(
    endpoint: String,
    noinline block: RequestHandlerBlock<In, Out>
) = server(DeleteHandler.Companion, endpoint, block)

inline fun <reified In: Request, reified Out: Response> Service.DeleteHandler(
    endpoint: String
) = client<In, Out>(DeleteHandler.Companion, endpoint)

