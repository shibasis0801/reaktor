package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.ContextEntry
import dev.shibasis.reaktor.conductor.ContextPacket
import dev.shibasis.reaktor.conductor.ConductorJson
import kotlinx.serialization.Serializable

@Serializable
data class TaskContext(
    val graph: String,
    val task: String,
    val revision: Long?,
    val prompt: String,
    val context: ContextPacket? = null,
    val excluded: List<ContextEntry> = emptyList(),
    val dispatched: Boolean = false,
    val notices: List<String> = emptyList(),
) {
    val bytes: Int get() = prompt.encodeToByteArray().size +
        (context?.let { ConductorJson.encodeToString(ContextPacket.serializer(), it).encodeToByteArray().size } ?: 0)
}
