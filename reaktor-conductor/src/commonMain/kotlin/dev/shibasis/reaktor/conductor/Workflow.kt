package dev.shibasis.reaktor.conductor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** An authored protocol over the canonical conversation graph, not a second application IR. */
@Serializable
data class WorkflowDefinition(
    val id: String,
    val title: String,
    val participants: List<AgentSpec>,
    val stages: List<WorkflowStage>,
    val edges: List<WorkflowEdge> = emptyList(),
    val subjectRefs: List<String> = emptyList(),
) {
    fun validate() {
        require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && title.length in 1..200)
        require(stages.size in 1..64 && participants.size in 1..8 && subjectRefs.size <= 100)
        require(participants.map { it.id }.distinct().size == participants.size)
        require(stages.map { it.id }.distinct().size == stages.size)
        stages.forEach { stage ->
            require(stage.id.matches(Regex("[a-zA-Z0-9_-]{1,80}")) && stage.title.length in 1..200)
            require(stage.instruction.length <= 24000)
            when (stage.action) {
                WorkflowAction.Agent -> require(participants.any { it.id == stage.agent }) { "Unknown participant for ${stage.id}" }
                WorkflowAction.Check -> require(!stage.checkId.isNullOrBlank()) { "A check stage needs a catalog check id" }
                WorkflowAction.Gate -> Unit
            }
        }
        require(edges.size <= 256 && edges.distinct().size == edges.size)
        edges.forEach { edge ->
            require(edge.from != edge.to && stages.any { it.id == edge.from } && stages.any { it.id == edge.to }) { "Invalid workflow edge" }
            if (edge.whenResult in listOf(WorkflowCondition.Pass, WorkflowCondition.Repair))
                require(stages.single { it.id == edge.from }.contract == WorkflowContract.Decision) { "Decision edge needs a Decision output contract" }
        }
        val visited = mutableSetOf<String>()
        repeat(stages.size) { stages.filter { it.id !in visited && edges.filter { edge -> edge.to == it.id }.all { edge -> edge.from in visited } }.forEach { visited += it.id } }
        require(visited.size == stages.size) { "Workflow contains a cycle; use a bounded repair runbook" }
    }
}

@Serializable enum class WorkflowAction { Agent, Check, Gate }
@Serializable enum class WorkflowContract { Text, Decision }
@Serializable enum class WorkflowCondition { Always, Succeeded, Failed, Pass, Repair }
@Serializable enum class WorkflowStageStatus { Running, Completed, Failed, Skipped, Waiting }
@Serializable data class WorkflowStage(val id: String, val title: String, val agent: AgentId? = null,
    val instruction: String = "", val action: WorkflowAction = WorkflowAction.Agent,
    val contract: WorkflowContract = WorkflowContract.Text, val checkId: String? = null)
@Serializable data class WorkflowEdge(val from: String, val to: String, val whenResult: WorkflowCondition = WorkflowCondition.Succeeded)
@Serializable data class WorkflowStageResult(val status: WorkflowStageStatus, val eventId: EventId? = null,
    val verdict: String? = null, val detail: String? = null, val receiptId: String? = null)
@Serializable data class WorkflowProgress(val stages: Map<String, WorkflowStageResult> = emptyMap(), val approvedGates: Set<String> = emptySet())
@Serializable data class WorkflowCheckResult(val ok: Boolean, val text: String, val receiptId: String)
class WorkflowPaused(val stageId: String, message: String) : IllegalStateException(message)

/** Native output is data. Invalid or absent typed decisions fail closed. */
fun workflowDecision(text: String): Pair<String, String> {
    val value = ConductorJson.parseToJsonElement(text.trim()).jsonObject
    val verdict = value.getValue("verdict").jsonPrimitive.content
    require(verdict in listOf("pass", "repair", "fail")) { "Decision verdict must be pass, repair or fail" }
    val summary = value.getValue("summary").jsonPrimitive.content
    require(summary.isNotBlank() && summary.length <= 12000)
    return verdict to summary
}

internal suspend fun executeWorkflow(
    definition: WorkflowDefinition,
    restored: WorkflowProgress,
    save: (WorkflowProgress) -> WorkflowProgress,
    agent: suspend (WorkflowStage, List<EventId>) -> ThreadEvent,
    check: suspend (WorkflowStage) -> WorkflowCheckResult,
) {
    definition.validate()
    var progress = restored
    fun put(id: String, result: WorkflowStageResult) { progress = save(progress.copy(stages = progress.stages + (id to result))) }
    fun matches(edge: WorkflowEdge): Boolean {
        val result = progress.stages.getValue(edge.from)
        if (result.status == WorkflowStageStatus.Skipped) return false
        return when (edge.whenResult) {
            WorkflowCondition.Always -> true
            WorkflowCondition.Succeeded -> result.status == WorkflowStageStatus.Completed
            WorkflowCondition.Failed -> result.status == WorkflowStageStatus.Failed
            WorkflowCondition.Pass -> result.status == WorkflowStageStatus.Completed && result.verdict == "pass"
            WorkflowCondition.Repair -> result.status == WorkflowStageStatus.Completed && result.verdict == "repair"
        }
    }
    val terminal = setOf(WorkflowStageStatus.Completed, WorkflowStageStatus.Failed, WorkflowStageStatus.Skipped)
    while (definition.stages.any { progress.stages[it.id]?.status !in terminal }) {
        val stage = definition.stages.first { candidate -> progress.stages[candidate.id]?.status !in terminal &&
            definition.edges.filter { it.to == candidate.id }.all { progress.stages[it.from]?.status in terminal } }
        val incoming = definition.edges.filter { it.to == stage.id }
        // A join waits for every predecessor to settle and runs if any incoming branch activates it.
        val selected = incoming.filter(::matches)
        if (incoming.isNotEmpty() && selected.isEmpty()) { put(stage.id, WorkflowStageResult(WorkflowStageStatus.Skipped)); continue }
        if (stage.action == WorkflowAction.Gate && stage.id !in progress.approvedGates) {
            put(stage.id, WorkflowStageResult(WorkflowStageStatus.Waiting, detail = stage.instruction))
            throw WorkflowPaused(stage.id, stage.title)
        }
        put(stage.id, WorkflowStageResult(WorkflowStageStatus.Running))
        val result = when (stage.action) {
            WorkflowAction.Gate -> WorkflowStageResult(WorkflowStageStatus.Completed, detail = "Explicitly continued by the operator")
            WorkflowAction.Check -> check(stage).let { WorkflowStageResult(if (it.ok) WorkflowStageStatus.Completed else WorkflowStageStatus.Failed,
                detail = it.text.take(12000), receiptId = it.receiptId) }
            WorkflowAction.Agent -> {
                val event = agent(stage, selected.mapNotNull { progress.stages[it.from]?.eventId })
                if (event.kind == EventKind.Failure) WorkflowStageResult(WorkflowStageStatus.Failed, event.id, detail = event.text.take(12000))
                else if (stage.contract == WorkflowContract.Decision) runCatching { workflowDecision(event.text) }.fold(
                    { (verdict, summary) -> WorkflowStageResult(if (verdict == "fail") WorkflowStageStatus.Failed else WorkflowStageStatus.Completed, event.id, verdict, summary) },
                    { WorkflowStageResult(WorkflowStageStatus.Failed, event.id, detail = "Invalid Decision output: ${it.message}") })
                else WorkflowStageResult(WorkflowStageStatus.Completed, event.id)
            }
        }
        put(stage.id, result)
    }
}
