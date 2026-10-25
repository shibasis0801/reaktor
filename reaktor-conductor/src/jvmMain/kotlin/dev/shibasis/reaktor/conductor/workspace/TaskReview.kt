package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.mcp.enumSchema
import dev.shibasis.reaktor.mcp.objectSchema
import dev.shibasis.reaktor.mcp.stringSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class TaskReview(
    val verdict: String,
    val summary: String,
    val candidate: String,
    val findings: List<Finding>,
) {
    @Serializable
    data class Finding(val title: String, val detail: String, val severity: String, val sourcePath: String, val line: Int,
        val confirms: List<String> = emptyList(), val checkReceipt: String? = null)

    companion object {
        fun schema(candidate: String): JsonObject = objectSchema(mapOf(
            "verdict" to enumSchema("Review decision", listOf("pass", "repair", "fail")),
            "summary" to stringSchema("Concise evidence and required changes"),
            "candidate" to enumSchema("Exact reviewed candidate", listOf(candidate)),
            "findings" to buildJsonObject {
                put("type", "array")
                put("maxItems", 20)
                put("items", objectSchema(mapOf(
                    "title" to stringSchema("Specific defect"),
                    "detail" to stringSchema("Evidence, impact and required change"),
                    "severity" to enumSchema("Finding severity", listOf("blocker", "high", "medium", "low")),
                    "sourcePath" to stringSchema("Changed file relative to the candidate workspace"),
                    "line" to buildJsonObject { put("type", "integer"); put("minimum", 1) },
                    "confirms" to buildJsonObject { put("type", "array"); put("maxItems", 120); put("items", stringSchema("Panel finding id, lens-index:finding-index")) },
                    "checkReceipt" to buildJsonObject { put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null")))); put("description", "An existing failed check receipt for this exact candidate, or null for independent source verification") },
                ), listOf("title", "detail", "severity", "sourcePath", "line", "confirms", "checkReceipt")))
            },
        ), listOf("verdict", "summary", "candidate", "findings"))

        fun parse(text: String): TaskReview = Json.decodeFromString(serializer(), text.trim()).also {
            require(it.verdict in listOf("pass", "repair", "fail")) { "Invalid review verdict" }
            require(it.summary.isNotBlank() && it.summary.length <= TaskGraphRules.OutcomeChars) { "Invalid review summary" }
            require(it.findings.size <= 20) { "Review has too many findings" }
        }
    }
}
