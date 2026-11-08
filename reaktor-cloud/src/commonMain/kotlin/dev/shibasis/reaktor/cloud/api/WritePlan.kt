package dev.shibasis.reaktor.cloud.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class PlannedRequest(val method: String, val url: String, val body: JsonElement? = null)

@Serializable
data class FieldChange(val path: String, val before: JsonElement? = null, val after: JsonElement? = null)

@Serializable
data class WritePlan(
    val target: String,
    val request: PlannedRequest,
    val exists: Boolean,
    val changes: List<FieldChange>,
    val digest: String,
    val liveVersion: String? = null,
)

@Serializable
data class WriteReceipt(val target: String, val digest: String, val status: Int, val remoteVersion: String? = null)

object JsonDiff {
    fun changes(before: JsonElement?, after: JsonElement?, ignore: Set<String> = emptySet(), path: String = ""): List<FieldChange> = when {
        path in ignore || before == after -> emptyList()
        before is JsonObject && after is JsonObject -> (before.keys + after.keys).sorted().flatMap { key ->
            changes(before[key], after[key], ignore, if (path.isEmpty()) key else "$path.$key")
        }
        before is JsonArray && after is JsonArray && before.size == after.size -> before.indices.flatMap { index ->
            changes(before[index], after[index], ignore, "$path[$index]")
        }
        else -> listOf(FieldChange(path.ifEmpty { "." }, before, after))
    }
}
