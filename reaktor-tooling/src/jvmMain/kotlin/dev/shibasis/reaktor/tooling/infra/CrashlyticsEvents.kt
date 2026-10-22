package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.time.Instant

@Serializable
data class CrashlyticsBreadcrumb(val timeMillis: Long?, val context: Map<String, String>)

@Serializable
data class CrashlyticsEvent(val name: String, val timeMillis: Long, val context: Map<String, String>,
    val breadcrumbs: List<CrashlyticsBreadcrumb> = emptyList())

@Serializable
data class CrashlyticsEvents(val events: List<CrashlyticsEvent>, val bounded: Boolean)

fun crashlyticsApiBody(result: JsonObject): JsonObject {
    require(result["isError"]?.jsonPrimitive?.booleanOrNull != true)
    val blocks = result["content"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull }
    require(blocks.size == 1 && blocks.single().length in 1..262_144) { "Invalid Crashlytics API response" }
    val response = Json.parseToJsonElement(blocks.single()).jsonObject
    require(response["ok"]?.jsonPrimitive?.booleanOrNull == true && response["status"]?.jsonPrimitive?.intOrNull == 200)
    require(response["truncated"]?.jsonPrimitive?.booleanOrNull != true) { "Truncated Crashlytics response" }
    return response["body"] as? JsonObject ?: error("Missing Crashlytics API body")
}

fun crashlyticsEvents(body: JsonObject, appResource: String, limit: Int = 10): CrashlyticsEvents {
    require(limit in 1..10 && appResource.matches(Regex("projects/[0-9]+/apps/[0-9A-Za-z:]+")))
    val rows = (body["events"] as? JsonArray).orEmpty()
    require(rows.size <= limit) { "Crashlytics event page exceeded its limit" }
    fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    fun time(value: String?) = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
    fun project(values: Map<String, String>): Map<String, String> = values.filter { (key, value) ->
        value.length in 1..128 && when (key) {
            "reaktor.trace_id" -> value.matches(Regex("[0-9a-f]{32}")) && value.any { it != '0' }
            "reaktor.span_id" -> value.matches(Regex("[0-9a-f]{16}")) && value.any { it != '0' }
            "reaktor.graph_digest" -> value.matches(Regex("[0-9a-f]{64}")) && value.any { it != '0' }
            "reaktor.application", "reaktor.installation", "reaktor.platform", "reaktor.environment",
            "reaktor.application_session", "reaktor.node" -> value.matches(Regex("[A-Za-z0-9._:/-]+"))
            else -> false
        }
    }
    val events = rows.map { row ->
        val event = row.jsonObject
        val name = event.text("name").orEmpty()
        require(name.startsWith("$appResource/events/") && name.removePrefix("$appResource/events/").matches(Regex("[A-Za-z0-9_-]{1,256}")))
        val at = time(event.text("eventTime")) ?: error("Missing Crashlytics event time")
        val keys = (event["customKeys"] as? JsonObject).orEmpty().mapNotNull { (key, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { key to it }
        }.toMap()
        val logs = (event["logs"] as? JsonArray).orEmpty().takeLast(64).mapNotNull { log ->
            val item = log as? JsonObject ?: return@mapNotNull null
            val message = item.text("message") ?: return@mapNotNull null
            if (message.length > 1_024 || !message.startsWith("reaktor.application=")) return@mapNotNull null
            val pairs = message.split(' ').map { it.split('=', limit = 2) }
            if (pairs.any { it.size != 2 } || pairs.map { it[0] }.distinct().size != pairs.size) return@mapNotNull null
            val context = project(pairs.associate { it[0] to it[1] })
            if (context["reaktor.trace_id"] == null || context["reaktor.span_id"] == null) return@mapNotNull null
            CrashlyticsBreadcrumb(time(item.text("logTime")), context)
        }
        CrashlyticsEvent(name, at, project(keys), logs)
    }
    require(events.map { it.name }.distinct().size == events.size) { "Duplicate Crashlytics events" }
    return CrashlyticsEvents(events, rows.size == limit || !body["nextPageToken"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
}
