package dev.shibasis.reaktor.conductor.workspace

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

        fun parse(text: String): TaskReview = Json.decodeFromString(serializer(), text.trim()).also {
            require(it.verdict in listOf("pass", "repair", "fail")) { "Invalid review verdict" }
            require(it.summary.isNotBlank() && it.summary.length <= TaskGraphRules.OutcomeChars) { "Invalid review summary" }
            require(it.findings.size <= 20) { "Review has too many findings" }
        }
    }
}
