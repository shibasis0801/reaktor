package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.HttpFailure
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.RequestHandler
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.Service
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.graph.ServiceNode
import dev.shibasis.reaktor.io.serialization.TextSerializer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise

private val textSerializer = TextSerializer()

fun Service.toHono(): Hono = Hono().mount(this)

fun Hono.mount(service: Service, island: String? = null): Hono {
    service.handlers.forEach {
        @Suppress("UNCHECKED_CAST")
        val handler = it as RequestHandler<Request, Response>

        on(handler.method.name, handler.route.toHonoRoute()) { context ->
            handler.asHonoHandler(context, island)
        }
    }
    return this
}

fun Hono.nest(path: String, service: Service): Hono = route(path, service.toHono())

fun Hono.mount(nodes: List<ServiceNode>): Hono = apply { nodes.forEach { mount(it.service, it.graph.label.ifBlank { null }) } }

@Suppress("UNCHECKED_CAST")
fun Hono.fallback(handler: RequestHandler<out Request, out Response>): Hono =
    on(handler.method.name, "/*") { context -> (handler as RequestHandler<Request, Response>).asHonoHandler(context, null) }

fun Hono.otherwise(status: StatusCode): Hono =
    all("/*") { failureResponse(status, status.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)) }

data class CorsPolicy(
    val methods: List<String>,
    val headers: List<String>,
    val maxAgeSeconds: Int,
    val origin: String = "*",
)

fun Hono.cors(policy: CorsPolicy): Hono {
    val options = js("({})")
    options.origin = policy.origin
    options.allowMethods = policy.methods.toTypedArray()
    options.allowHeaders = policy.headers.toTypedArray()
    options.maxAge = policy.maxAgeSeconds
    return use("*", HonoCorsModule.cors(options))
}

@OptIn(DelicateCoroutinesApi::class)
private fun RequestHandler<Request, Response>.asHonoHandler(context: HonoContext, island: String?) = GlobalScope.promise {
    val rawBody = runCatching { context.req.text().await() }.getOrNull().orEmpty().ifBlank { "{}" }
    val pathParams = toStringMap(context.req.param())
    val queryParams = toStringMap(context.req.query())
    val request = try {
        textSerializer.deserialize(requestSerializer, rawBody)
    } catch (error: Throwable) {
        // A body that will not parse is the caller's mistake, not a crash. Before this it reached
        // the transport as an unhandled rejection and came back as a 500, which tells a client to
        // retry something that will never succeed.
        //
        // The reason is included because it names a field, not a value: enough for whoever is
        // holding a stale client to see what changed, and nothing about the request's contents.
        return@promise failureResponse(
            StatusCode.BAD_REQUEST,
            error.message ?: "The request body did not parse",
        )
    }

    request.pathParams.putAll(pathParams)
    request.queryParams.putAll(queryParams)
    toStringMap(context.req.header()).forEach { entry ->
        val key = entry.key
        val value = entry.value
        if (key == Environment.Header) {
            request.environment = Environment(value)
        }
        request.headers[key] = value
    }

    val cloudflareContext = CloudflareContext(context.env, context.executionCtx, context)
    (request as? CloudflareAwareRequest)?.cloudflareContext = cloudflareContext
    request.asDynamic().cloudflareContext = cloudflareContext
    request.attributes[CloudflareContextAttribute] = cloudflareContext
    island?.let { request.attributes[ServiceCall.IslandAttribute] = it }

    val response = try {
        invoke(request)
    } catch (failure: HttpFailure) {
        // Something the handler decided. The status and the message are both meant for the caller.
        return@promise failureResponse(failure.statusCode, failure.message ?: failure.statusCode.name)
    } catch (error: Throwable) {
        // Anything else is a bug, not an answer. The caller gets a 500 and no detail — an
        // unplanned exception's message is written for whoever reads the log, and that is where
        // it stays.
        console.error("Unhandled error serving " + route + ": " + error.toString())
        return@promise failureResponse(StatusCode.INTERNAL_SERVER_ERROR, "Internal server error")
    }

    response.toWorkerResponse(textSerializer.serialize(responseSerializer, response))
}

/** A bare `{"error": …}` body, so a failure is still JSON to a client that only parses JSON. */
private fun failureResponse(status: StatusCode, message: String): dynamic {
    val initHeaders = js("({})")
    initHeaders["content-type"] = "application/json"

    val init = js("({})")
    init.status = status.code
    init.headers = initHeaders

    val body = json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        kotlinx.serialization.json.JsonObject(
            mapOf("error" to kotlinx.serialization.json.JsonPrimitive(message)),
        ),
    )
    return js("new Response(body, init)")
}

private fun toStringMap(source: dynamic): MutableMap<String, String> {
    val result = mutableMapOf<String, String>()
    if (source == null) return result

    val entries = js("Object.entries(source)") as Array<Array<dynamic>>
    entries.forEach { entry ->
        val key = entry.getOrNull(0)?.toString() ?: return@forEach
        val value = entry.getOrNull(1)?.toString() ?: return@forEach
        result[key] = value
    }
    return result
}

private fun String.toHonoRoute(): String =
    replace(routeParameterPattern) { matchResult ->
        ":${matchResult.groupValues[1]}"
    }

private val routeParameterPattern = """\{([^}]+)\}""".toRegex()

private fun Response.toWorkerResponse(body: String): dynamic {
    val initHeaders = js("({})")
    val status = transportStatusCode.code
    transportHeaders.forEach { (key, value) ->
        initHeaders[key] = value
    }
    val hasContentType = js("initHeaders['Content-Type'] !== undefined") as Boolean
    if (!hasContentType) {
        initHeaders["Content-Type"] = "application/json"
    }

    return js("new Response(body, { status: status, headers: initHeaders })")
}
