package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.RuntimeKind
import dev.shibasis.reaktor.conductor.worklog.GraphWorkScore
import kotlinx.serialization.Serializable

@Serializable
data class AgentBenchDefect(val id: String, val kind: String, val path: String, val from: Int, val through: Int = from)

@Serializable
data class AgentBenchScenario(
    val id: String,
    val title: String,
    val revisions: Map<String, String>,
    val draft: TaskGraphDraft,
    val defects: List<AgentBenchDefect> = emptyList(),
)

@Serializable
data class AgentBenchConfiguration(val id: String, val slots: Map<String, TaskSeat>, val estimatedTokens: Long? = null)

@Serializable
data class AgentBenchBinding(
    val scenario: AgentBenchScenario,
    val scenarioRevision: String,
    val configuration: String,
    val seats: Map<String, ResolvedTaskSeat>,
    val repeat: Int,
    val repeats: Int,
    val ownerBuild: String,
    val estimatedTokens: Long?,
    val definition: String = "",
)

@Serializable
data class AgentBenchScenarioEntry(val scenario: AgentBenchScenario, val revision: String)

@Serializable
data class AgentBenchRun(val graph: String, val status: TaskGraphStatus, val binding: AgentBenchBinding, val result: AgentBenchResult? = null)

@Serializable
data class AgentBenchCatalog(val scenarios: List<AgentBenchScenarioEntry>, val runs: List<AgentBenchRun>)

@Serializable
data class AgentBenchLensScore(
    val profile: String,
    val revision: Long?,
    val provider: RuntimeKind?,
    val kind: String,
    val expected: Int,
    val confirmed: Int,
    val missed: Int,
    val unmatchedConfirmed: Int,
    val unconfirmed: Int,
)

@Serializable
data class AgentBenchResult(
    val graph: String,
    val revision: Long,
    val measuredAt: Long,
    val binding: AgentBenchBinding,
    val score: GraphWorkScore,
    val checksPassed: Boolean?,
    val acceptedCandidates: Int,
    val candidates: Int,
    val repairAttempts: Int,
    val lenses: List<AgentBenchLensScore>,
    val comparable: Boolean,
    val notes: List<String>,
)
