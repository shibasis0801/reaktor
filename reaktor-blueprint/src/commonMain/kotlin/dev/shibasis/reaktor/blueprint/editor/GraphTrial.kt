package dev.shibasis.reaktor.blueprint.editor

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class Trial(
    @EncodeDefault val format: String = Format,
    val id: String,
    val revision: Int,
    val finishedAt: String? = null,
    val summary: String = "",
    val nodes: List<TrialNode> = emptyList(),
) {
    val passed: Int get() = nodes.count { it.ok }
    val failed: Int get() = nodes.count { !it.ok }

    fun encode(): String = TrialJson.encodeToString(serializer(), this)

    companion object {
        const val Format = "reaktor.trial/1"

        fun decode(text: String): Trial = TrialJson.decodeFromString(serializer(), text)
    }
}

@Serializable
data class TrialNode(val node: String, val ok: Boolean, val detail: String = "")

data class Holder(val name: String, val sinceMillis: Long, val editing: Boolean, val ahead: Boolean = true)

private val TrialJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }
