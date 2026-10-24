package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.contract.Metadata

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
    options.allowHeaders = (policy.headers + Metadata.Sent).distinctBy { it.lowercase() }.toTypedArray()
    options.maxAge = policy.maxAgeSeconds
    return use("*", HonoCorsModule.cors(options))
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

internal fun toStringMap(source: dynamic): MutableMap<String, String> {
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

internal fun String.toHonoRoute(): String =
    replace(routeParameterPattern) { matchResult ->
        ":${matchResult.groupValues[1]}"
    }

private val routeParameterPattern = """\{([^}]+)\}""".toRegex()
