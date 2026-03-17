package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.service.Environment
import dev.shibasis.reaktor.graph.service.Request
import dev.shibasis.reaktor.graph.service.RequestHandler
import dev.shibasis.reaktor.graph.service.Response
import dev.shibasis.reaktor.graph.service.Service
import dev.shibasis.reaktor.io.serialization.TextSerializer
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val textSerializer = TextSerializer()

fun Service.toHono(): Hono = Hono().mount(this)

fun Hono.mount(service: Service): Hono {
    service.handlers.forEach {
        @Suppress("UNCHECKED_CAST")
        val handler = it as RequestHandler<Request, Response>

        on(handler.method.name, handler.route.toHonoRoute()) { context ->
            handler.asHonoHandler(context)
        }
    }
    return this
}

fun Hono.nest(path: String, service: Service): Hono = route(path, service.toHono())

@OptIn(DelicateCoroutinesApi::class)
private fun RequestHandler<Request, Response>.asHonoHandler(context: HonoContext) = GlobalScope.promise {
    val rawBody = runCatching { context.req.text().await() }.getOrNull().orEmpty().ifBlank { "{}" }
    val pathParams = toStringMap(context.req.param())
    val queryParams = toStringMap(context.req.query())
    val request = textSerializer.deserialize(
        requestSerializer,
        mergeRequestPayload(rawBody, pathParams, queryParams),
    )

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

    val response = invoke(request)
    response.toWorkerResponse(textSerializer.serialize(responseSerializer, response))
}

private fun mergeRequestPayload(
    rawBody: String,
    pathParams: Map<String, String>,
    queryParams: Map<String, String>,
): String {
    val baseObject = runCatching { json.parseToJsonElement(rawBody) }
        .getOrNull()
        ?.unsafeCast<JsonObject?>()
        ?: JsonObject(emptyMap())

    val merged = baseObject.toMutableMap()
    queryParams.forEach { (key, value) -> merged[key] = value.toJsonScalar() }
    pathParams.forEach { (key, value) -> merged[key] = value.toJsonScalar() }
    return json.encodeToString(JsonObject(merged))
}

private fun String.toJsonScalar(): JsonElement =
    toIntOrNull()?.let(::JsonPrimitive)
        ?: toLongOrNull()?.let(::JsonPrimitive)
        ?: toDoubleOrNull()?.let(::JsonPrimitive)
        ?: when (lowercase()) {
            "true" -> JsonPrimitive(true)
            "false" -> JsonPrimitive(false)
            else -> JsonPrimitive(this)
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
    val status = statusCode.code
    headers.forEach { (key, value) ->
        initHeaders[key] = value
    }
    val hasContentType = js("initHeaders['Content-Type'] !== undefined") as Boolean
    if (!hasContentType) {
        initHeaders["Content-Type"] = "application/json"
    }

    return js("new Response(body, { status: status, headers: initHeaders })")
}
