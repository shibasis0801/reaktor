package dev.shibasis.reaktor.conductor

import kotlinx.serialization.Serializable

/** Portable retrieval result. References are evidence, never instructions or authorization. */
@Serializable
data class ContextPacket(
    val version: Int = 1,
    val workspaceId: String,
    val principalId: String,
    val source: String,
    val observedAt: String? = null,
    val revision: String? = null,
    val freshness: String = "unknown",
    val partial: Boolean = false,
    val entries: List<ContextEntry> = emptyList(),
    val omittedEntries: Int = 0,
    val notices: List<String> = emptyList(),
) {
    init {
        require(version == 1) { "Unsupported context packet version: $version" }
        require(workspaceId.isNotBlank() && principalId.isNotBlank()) { "Context needs an explicit scope" }
        require(omittedEntries >= 0)
    }
}

@Serializable
data class ContextEntry(
    val ref: String,
    val kind: String,
    val title: String,
    val text: String,
    val reason: String,
)
