package dev.shibasis.reaktor.tooling.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
enum class ApiEffect { Read, Write, Delete }

@Serializable
data class ApiParameter(
    val name: String,
    val location: String,
    val required: Boolean,
    val type: String,
    val description: String = "",
    val reserved: Boolean = false,
)

@Serializable
data class ApiOperation(
    val id: String,
    val api: String,
    val method: String,
    val path: String,
    val summary: String,
    val baseUrl: String,
    val parameters: List<ApiParameter> = emptyList(),
    val body: JsonElement? = null,
    val tags: List<String> = emptyList(),
    val effect: ApiEffect = ApiEffect.Read,
    val deprecated: Boolean = false,
    val permissions: List<String> = emptyList(),
) {
    val key: String get() = "$api:$id"
}

@Serializable
data class ApiSurface(
    val provider: String,
    val title: String,
    val version: String,
    val operations: List<ApiOperation>,
    val digest: String,
    val schemas: Map<String, JsonElement> = emptyMap(),
) {
    private val byId by lazy { operations.associateBy { it.id } }

    operator fun get(id: String): ApiOperation? = byId[id] ?: byId[id.substringAfterLast(':')]

    fun search(query: String, limit: Int = 25): List<ApiOperation> {
        val terms = query.lowercase().split(' ', '-', '_', '.', '/').filter { it.length >= 2 }
        if (terms.isEmpty()) return operations.take(limit)
        return operations.asSequence()
            .map { operation -> operation to score(operation, query.lowercase(), terms) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<ApiOperation, Int>> { it.second }.thenBy { it.first.deprecated }.thenBy { it.first.id.length })
            .take(limit).map { it.first }.toList()
    }

    fun describe(id: String, depth: Int = 3): JsonObject? {
        val operation = get(id) ?: return null
        return JsonObject(buildMap {
            put("id", JsonPrimitive(operation.key))
            put("method", JsonPrimitive(operation.method))
            put("path", JsonPrimitive(operation.path))
            put("effect", JsonPrimitive(operation.effect.name.lowercase()))
            put("summary", JsonPrimitive(operation.summary))
            put("parameters", JsonArray(operation.parameters.map { parameter ->
                JsonObject(mapOf("name" to JsonPrimitive(parameter.name), "in" to JsonPrimitive(parameter.location),
                    "required" to JsonPrimitive(parameter.required), "type" to JsonPrimitive(parameter.type),
                    "description" to JsonPrimitive(parameter.description.take(300))))
            }))
            operation.body?.let { put("body", resolve(it, depth)) }
            if (operation.permissions.isNotEmpty()) put("permissions", JsonArray(operation.permissions.map(::JsonPrimitive)))
            if (operation.deprecated) put("deprecated", JsonPrimitive(true))
        })
    }

    fun resolve(schema: JsonElement, depth: Int): JsonElement {
        val node = schema as? JsonObject ?: return schema
        val reference = (node["\$ref"] as? JsonPrimitive)?.contentOrNull
        if (reference != null) {
            val name = reference.substringAfterLast('/')
            val target = schemas[name] ?: return JsonPrimitive(name)
            return if (depth <= 0) JsonPrimitive(name) else resolve(target, depth - 1)
        }
        return JsonObject(node.mapNotNull { (key, value) ->
            when (key) {
                "example", "examples", "x-examples", "externalDocs" -> null
                "description" -> key to ((value as? JsonPrimitive)?.contentOrNull?.take(200)?.let(::JsonPrimitive) ?: JsonNull)
                "properties" -> key to JsonObject((value as? JsonObject).orEmpty().entries.take(60).associate { (name, property) -> name to resolve(property, depth) })
                "items", "additionalProperties" -> key to resolve(value, depth)
                "allOf", "anyOf", "oneOf" -> key to JsonArray((value as? JsonArray).orEmpty().take(12).map { resolve(it, depth) })
                else -> key to value
            }
        }.toMap())
    }

    private fun score(operation: ApiOperation, phrase: String, terms: List<String>): Int {
        val id = operation.id.lowercase()
        val text = listOf(id, operation.path.lowercase(), operation.summary.lowercase(), operation.tags.joinToString(" ").lowercase())
        var score = if (id == phrase || operation.key.lowercase() == phrase) 1000 else 0
        terms.forEach { term ->
            when {
                term in id -> score += 6
                term in text[1] -> score += 4
                term in text[2] -> score += 3
                term in text[3] -> score += 2
                else -> return 0
            }
        }
        return score
    }
}

data class ApiRequest(val method: String, val url: String, val query: List<Pair<String, String>>, val headers: Map<String, String>, val body: String?)

class ApiArgumentException(message: String) : IllegalArgumentException(message)

fun ApiOperation.request(arguments: JsonObject): ApiRequest {
    val known = parameters.map { it.name }.toSet() + "body"
    val unknown = arguments.keys - known
    if (unknown.isNotEmpty()) throw ApiArgumentException("${key} takes ${known.sorted().joinToString()}; unknown: ${unknown.sorted().joinToString()}")
    parameters.filter { it.required && it.location != "header" && arguments[it.name].isMissing() }.takeIf { it.isNotEmpty() }?.let { missing ->
        throw ApiArgumentException("$key needs ${missing.joinToString { it.name }}")
    }
    var resolved = path
    parameters.filter { it.location == "path" }.forEach { parameter ->
        val value = arguments[parameter.name].text() ?: return@forEach
        resolved = resolved.replace("{+${parameter.name}}", percentEncode(value, keepSlash = true))
            .replace("{${parameter.name}}", percentEncode(value, keepSlash = parameter.reserved))
    }
    val query = parameters.filter { it.location == "query" }.flatMap { parameter ->
        when (val value = arguments[parameter.name]) {
            null, is JsonNull -> emptyList()
            is JsonArray -> value.mapNotNull { it.text() }.map { parameter.name to it }
            else -> listOfNotNull(value.text()?.let { parameter.name to it })
        }
    }
    val headers = parameters.filter { it.location == "header" }.mapNotNull { parameter -> arguments[parameter.name].text()?.let { parameter.name to it } }.toMap()
    val url = baseUrl.trimEnd('/') + "/" + resolved.trimStart('/')
    return ApiRequest(method, url, query, headers, arguments["body"]?.takeUnless { it is JsonNull }?.toString())
}

fun ApiRequest.fullUrl(): String = if (query.isEmpty()) url else url + "?" + query.joinToString("&") { (name, value) -> percentEncode(name) + "=" + percentEncode(value) }

private fun JsonElement?.isMissing(): Boolean = this == null || this is JsonNull || (this is JsonPrimitive && contentOrNull.isNullOrEmpty())

private fun JsonElement?.text(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> contentOrNull
    else -> toString()
}

private fun JsonObject?.orEmpty(): Map<String, JsonElement> = this ?: emptyMap()

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()

private const val Unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

fun percentEncode(value: String, keepSlash: Boolean = false): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val char = (byte.toInt() and 0xFF).toChar()
        if (char in Unreserved || (keepSlash && char == '/')) append(char)
        else append('%').append("0123456789ABCDEF"[(byte.toInt() shr 4) and 0xF]).append("0123456789ABCDEF"[byte.toInt() and 0xF])
    }
}
