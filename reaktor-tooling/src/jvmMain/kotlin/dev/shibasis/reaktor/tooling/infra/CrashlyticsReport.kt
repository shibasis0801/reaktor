package dev.shibasis.reaktor.tooling.infra

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

@Serializable
data class CrashlyticsIssue(val id: String, val title: String, val subtitle: String, val errorType: String,
    val events: Long? = null, val users: Long? = null)

@Serializable
data class CrashlyticsReport(val name: String, val issues: List<CrashlyticsIssue>, val bounded: Boolean)

fun crashlyticsReport(result: JsonObject, limit: Int = 50): CrashlyticsReport {
    require(limit in 1..50)
    require(result["isError"]?.jsonPrimitive?.booleanOrNull != true)
    val text = result["content"]?.jsonArray.orEmpty().mapNotNull { block ->
        block.jsonObject.takeIf { it["type"]?.jsonPrimitive?.contentOrNull == "text" }?.get("text")?.jsonPrimitive?.contentOrNull
    }.joinToString("\n")
    require(text.length in 1..262_144) { "Invalid Crashlytics report size" }
    val options = LoaderOptions().apply { maxAliasesForCollections = 0; nestingDepthLimit = 32; codePointLimit = 262_144 }
    val yaml = Yaml(SafeConstructor(options))
    val document = yaml.load<Any?>(text) as? Map<*, *> ?: error("Invalid Crashlytics report")
    val name = (document["name"] as? String)?.takeIf { it.isNotBlank() } ?: error("Missing Crashlytics report identity")
    val groups = when (val value = document["groups"]) {
        is String -> yaml.load<Any?>(value) as? List<*> ?: error("Invalid Crashlytics groups")
        is List<*> -> value
        null -> emptyList<Any?>()
        else -> error("Invalid Crashlytics groups")
    }
    require(groups.size <= limit) { "Crashlytics report exceeded its page limit" }
    fun Map<*, *>.text(key: String) = this[key]?.toString().orEmpty().take(1_024)
    fun number(value: Any?): Long? = when (value) {
        is Number -> value.toLong().takeIf { it >= 0 && value.toDouble() == it.toDouble() }
        is String -> value.toLongOrNull()?.takeIf { it >= 0 }
        else -> null
    }
    val issues = groups.map { row ->
        val group = row as? Map<*, *> ?: error("Invalid Crashlytics group")
        val issue = group["issue"] as? Map<*, *> ?: error("Missing Crashlytics issue")
        val id = issue.text("id").ifBlank { issue.text("name") }
        require(id.isNotBlank()) { "Missing Crashlytics issue identity" }
        val metrics = group["metrics"] as? Map<*, *> ?: group
        CrashlyticsIssue(id, issue.text("title"), issue.text("subtitle"), issue.text("errorType"),
            number(metrics["eventsCount"]), number(metrics["impactedUsers"]))
    }
    return CrashlyticsReport(name.take(1_024), issues, groups.size >= limit || !document["nextPageToken"].toString().let { it == "null" || it.isBlank() })
}
