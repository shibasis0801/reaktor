package dev.shibasis.reaktor.tooling.api

import dev.shibasis.reaktor.tooling.operation.Operation
import dev.shibasis.reaktor.tooling.operation.rawOperation
import dev.shibasis.reaktor.tooling.SafetyClass
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object CloudApiTools {
    private const val MaxResult = 60_000

    fun operations(apis: CloudApis): List<Operation<*, *>> = listOf(
        rawOperation("cloud.api.surfaces", "List the cloud APIs Reaktor can call for this workspace: Cloudflare, Supabase and Google Cloud, " +
            "with the credential each one uses. Find and describe operations with cloud.api.search and cloud.api.describe.",
            SafetyClass.ReadOnly, { schema() }) { surfaces(apis) },
        rawOperation("cloud.api.search", "Search the complete Cloudflare, Supabase or Google Cloud API for operations. " +
            "api is cloudflare, supabase or gcp:<api>.<version> such as gcp:compute.v1; leave it out to search Cloudflare, Supabase and the common Google APIs. " +
            "Pass api=gcp to search Google's directory of APIs instead of operations.", SafetyClass.ReadOnly,
            { schema("query" to "string", "api" to "string", "limit" to "integer", required = listOf("query")) }) { arguments ->
            search(apis, arguments)
        },
        rawOperation("cloud.api.describe", "Describe one operation found with cloud.api.search: its parameters, its JSON body, its safety classification, " +
            "and the token permissions it needs (any one of them is enough). The workspace's Cloudflare account and Google project fill account_id and project when left out.",
            SafetyClass.ReadOnly, { schema("operation" to "string", required = listOf("operation")) }) { arguments ->
            val key = arguments.text("operation")
            apis.surface(CloudApis.surfaceOf(key)).describe(key) ?: error("No operation $key; find it with cloud.api.search")
        },
        call(apis, "cloud.api.read", SafetyClass.LiveRead, "Call an operation that only reads"),
        call(apis, "cloud.api.write", SafetyClass.ProductionReversibleWrite, "Call an operation that creates or changes something. The workspace policy controls its approval"),
        call(apis, "cloud.api.delete", SafetyClass.Destructive, "Call an operation that deletes something. The workspace policy controls its approval"),
    )

    private fun call(apis: CloudApis, name: String, effect: SafetyClass, description: String) = rawOperation(
        name, "$description. operation is the id from cloud.api.search; arguments holds path and query parameters by name and the JSON body under body.", effect,
        { schema("operation" to "string", "arguments" to "object", required = listOf("operation")) },
    ) { arguments ->
        val key = arguments.text("operation")
        val operation = apis.operation(key)
        require(operation.effect == effect) { "$name accepts $effect; $key causes ${operation.effect}" }
        val response = apis.call(key, arguments["arguments"] as? JsonObject ?: JsonObject(emptyMap()))
        val refusal = apis.refusal(key, response)
        buildJsonObject {
            put("status", response.status)
            put("ok", response.ok)
            put("millis", response.millis)
            refusal?.let { put("refused", it) }
            if (response.text.length > MaxResult) {
                put("truncated", true)
                put("text", response.text.take(MaxResult))
            } else put("body", response.body ?: JsonPrimitive(response.text))
        }
    }

    private suspend fun surfaces(apis: CloudApis): JsonElement {
        val loaded = apis.records().associateBy { it.name }
        return buildJsonObject {
            putJsonArray("apis") {
                (CloudApis.Sources.keys + CloudApis.GoogleApis.map { "gcp:$it" }).forEach { name ->
                    add(buildJsonObject {
                        put("api", name)
                        loaded[name]?.let { put("cached", true); put("operations", it.operations) }
                    })
                }
            }
            putJsonObject("credentials") {
                listOf("cloudflare", "supabase", "gcp").forEach { api -> put(api, apis.signIn(api)) }
            }
            put("google", "Any Google API in Google's directory works as gcp:<name>.<version>; search it with cloud.api.search api=gcp.")
        }
    }

    private suspend fun search(apis: CloudApis, arguments: JsonObject): JsonElement {
        val query = arguments.text("query")
        val limit = (arguments["limit"] as? JsonPrimitive)?.intOrNull?.coerceIn(1, 50) ?: 20
        val api = (arguments["api"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        if (api == "gcp") {
            val terms = query.lowercase().split(' ').filter { it.isNotBlank() }
            return buildJsonArray {
                apis.googleApis().filter { it.preferred && terms.all { term -> term in "${it.id} ${it.title} ${it.description}".lowercase() } }.take(limit).forEach { found ->
                    add(buildJsonObject { put("api", "gcp:${found.id}"); put("title", found.title); put("description", found.description.take(200)) })
                }
            }
        }
        val names = api?.let(::listOf) ?: (CloudApis.Sources.keys + CloudApis.GoogleApis.map { "gcp:$it" })
        val found = names.flatMap { name -> runCatching { apis.surface(name).search(query, limit) }.getOrDefault(emptyList()) }
        return JsonArray(found.take(limit).map { operation ->
            buildJsonObject {
                put("operation", operation.key)
                put("method", operation.method)
                put("path", operation.path)
                put("effect", operation.effect.name.lowercase())
                put("summary", operation.summary.take(160))
            }
        })
    }

    private fun schema(vararg properties: Pair<String, String>, required: List<String> = emptyList()) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { properties.forEach { (name, type) -> putJsonObject(name) { put("type", type) } } }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    }

    private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("$key is required")
}
