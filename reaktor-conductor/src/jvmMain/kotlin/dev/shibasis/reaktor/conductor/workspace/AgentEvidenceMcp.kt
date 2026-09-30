package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.mcp.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer

internal fun agentEvidenceTools(workspace: AgentWorkspace): List<McpTool> {
    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    val taskSchema = objectSchema(mapOf("taskId" to stringSchema("Reaktor task/thread id")), listOf("taskId"))
    fun JsonObject.task(): String = text("taskId").also { workspace.taskEvidence(it) }
    fun result(task: AgentTaskEvidence) = AgentWorkspaceJson.encodeToJsonElement(AgentTaskEvidence.serializer(), task)
    return listOf(
        McpTool("agent_queue", "Queue an exact next submission behind this task's running turn. Interrupted or failed predecessors block the queue; owner restart never silently executes it.",
            objectSchema(mapOf("afterRunId" to stringSchema("Active run id"), "submission" to buildJsonObject { put("type", "object"); put("additionalProperties", true) }), listOf("afterRunId", "submission")), false, true) {
            AgentWorkspaceJson.encodeToJsonElement(AgentQueuedTurn.serializer(), workspace.queue(it.text("afterRunId"),
                AgentWorkspaceJson.decodeFromJsonElement(AgentSubmission.serializer(), it.getValue("submission"))))
        },
        McpTool("agent_queue_items", "Read the queued follow-ups for a task.", taskSchema, true, true) {
            val items = workspace.queueItems(it.task())
            buildJsonObject { put("items", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(AgentQueuedTurn.serializer()), items)) }
        },
        McpTool("agent_queue_cancel", "Cancel a queued turn before dispatch.", objectSchema(mapOf("id" to stringSchema("Queue entry id")), listOf("id")), false, true) {
            val items = workspace.cancelQueued(it.text("id"))
            buildJsonObject { put("items", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(AgentQueuedTurn.serializer()), items)) }
        },
        McpTool("agent_collect_check", "Import a finished check and its retained log from this workspace's local kernel. Validates workspace and candidate identity; does not run a command.",
            objectSchema(mapOf("taskId" to stringSchema("Task id"), "runId" to stringSchema("Kernel run id")), listOf("taskId", "runId")), false, true) {
            runBlocking { result(workspace.collectKernelCheck(it.task(), it.text("runId"))) }
        },
        McpTool("agent_task_graph", "Read the task's graph subjects, candidates, findings, checks and acceptance edges.", taskSchema, true, true) {
            AgentWorkspaceJson.encodeToJsonElement(AgentEvidenceGraph.serializer(), workspace.evidence.graph(it.task()))
        },
        McpTool("agent_candidate_capture", "Capture the current working source, including dirty and untracked files. Records omissions and a local diff artifact; does not accept or commit changes.", taskSchema, false, false) {
            AgentWorkspaceJson.encodeToJsonElement(AgentCandidate.serializer(), workspace.captureCandidate(it.task()))
        },
        McpTool("agent_artifact", "Fetch a bounded byte range of an artifact attached to this task. Follow nextOffset for more.",
            objectSchema(mapOf("taskId" to stringSchema("Task id"), "artifactId" to stringSchema("Exact attached artifact id"),
                "offset" to buildJsonObject { put("type", "integer"); put("minimum", 0) },
                "limit" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 100000) }), listOf("taskId", "artifactId")), true, true) {
            AgentWorkspaceJson.encodeToJsonElement(AgentArtifactPage.serializer(), workspace.evidence.artifact(it.task(), it.text("artifactId"),
                it["offset"]?.jsonPrimitive?.longOrNull ?: 0, it["limit"]?.jsonPrimitive?.intOrNull ?: 24000))
        },
        McpTool("agent_finding", "Record a typed review finding against an exact candidate and producer. References are validated; the diagnosis remains the reviewer's claim.",
            objectSchema(mapOf("taskId" to stringSchema("Task id"), "finding" to buildJsonObject {
                put("type", "object"); put("description", "AgentFinding: id, candidateId, producerRunId, participant, title, detail, severity (blocker/high/medium/low); optional subject, sourcePath, line")
                put("additionalProperties", true)
            }), listOf("taskId", "finding")), false, true) {
            result(workspace.addFinding(it.task(), AgentWorkspaceJson.decodeFromJsonElement(AgentFinding.serializer(), it.getValue("finding"))))
        },
        McpTool("agent_finding_resolve", "Record how a finding was addressed by a candidate. This is a resolution claim; acceptance still requires current checks.",
            objectSchema(mapOf("taskId" to stringSchema("Task id"), "findingId" to stringSchema("Exact finding id"), "candidateId" to stringSchema("Repair candidate id"),
                "reason" to stringSchema("Concrete resolution and evidence")), listOf("taskId", "findingId", "candidateId", "reason")), false, true) {
            result(workspace.evidence.resolve(it.task(), it.text("findingId"), it.text("candidateId"), it.text("reason")))
        },
        McpTool("agent_acceptance_checks", "Set the kernel check task ids required for acceptance. Changing the checklist invalidates previous acceptance.",
            objectSchema(mapOf("taskId" to stringSchema("Task id"), "checks" to buildJsonObject { put("type", "array"); put("items", stringSchema("Kernel task id")) }), listOf("taskId", "checks")), false, true) {
            result(workspace.evidence.requireChecks(it.task(), it.getValue("checks").jsonArray.map { v -> v.jsonPrimitive.content }))
        },
        McpTool("agent_candidate_accept", "Accept a current candidate only after all required kernel checks pass and blocking findings are resolved. Does not commit, merge or deploy.",
            objectSchema(mapOf("taskId" to stringSchema("Task id"), "candidateId" to stringSchema("Exact current candidate id")), listOf("taskId", "candidateId")), false, true) {
            result(workspace.evidence.accept(it.task(), it.text("candidateId")))
        },
    )
}
