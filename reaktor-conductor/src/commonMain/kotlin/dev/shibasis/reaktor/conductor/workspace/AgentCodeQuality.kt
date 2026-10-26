package dev.shibasis.reaktor.conductor.workspace

import kotlinx.serialization.Serializable

@Serializable
data class AgentLanding(val candidate: String, val at: Long, val commits: Map<String, String>, val seat: ResolvedTaskSeat?)

@Serializable
data class AgentChurnWindow(val days: Int, val added: Int?, val changed: Int?, val mature: Boolean, val reason: String? = null)

@Serializable
data class AgentChurnFile(
    val task: String,
    val candidate: String,
    val profile: String?,
    val profileRevision: Long?,
    val provider: String?,
    val root: String,
    val commit: String,
    val path: String,
    val modules: List<String>,
    val windows: List<AgentChurnWindow>,
)

@Serializable
data class AgentChurnReport(val measuredAt: Long, val files: List<AgentChurnFile>, val notices: List<String>)
