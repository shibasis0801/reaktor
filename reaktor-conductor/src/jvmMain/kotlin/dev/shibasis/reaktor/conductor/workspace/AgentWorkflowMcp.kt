package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import dev.shibasis.reaktor.mcp.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

internal fun agentWorkflowTools(workspace: AgentWorkspace): List<McpTool> {
    fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    fun schema(vararg fields: String) = objectSchema(fields.associateWith { stringSchema(it) }, fields.toList())
    return listOf(
        McpTool("agent_runtime_prune", "Delete expired runtime jars not referenced by this JVM, the installed supervisor, or recent image manifests. Keeps at least seven days; does not delete task data or worktrees.",
            objectSchema(mapOf("olderThanDays" to buildJsonObject { put("type", "integer"); put("minimum", 7); put("maximum", 3650) })), false, true, true, false) {
            buildJsonObject { put("bytesRemoved", workspace.pruneRuntimeImages(it["olderThanDays"]?.jsonPrimitive?.intOrNull ?: 30)) }
        },
        McpTool("agent_service_drain", "Pause admission of new tasks, queued turns and recovery while a service upgrade waits for current work. Existing tasks keep running. Set enabled=false to reopen admission.",
            objectSchema(mapOf("enabled" to buildJsonObject { put("type", "boolean") }), listOf("enabled")), false, true) {
            buildJsonObject { put("activeRuns", workspace.drain(it.getValue("enabled").jsonPrimitive.boolean)) }
        },
        McpTool("agent_activity_compact", "Compress old terminal-run activity into verified local archives. Original cursors and detail remain readable; active and recoverable work is excluded.",
            objectSchema(mapOf("olderThanDays" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 3650) })), false, true) {
            buildJsonObject { put("bytesSaved", workspace.compactActivity(it["olderThanDays"]?.jsonPrimitive?.intOrNull ?: 30)) }
        },
        McpTool("agent_native_control", "Steer or interrupt an observed native child with its exact active turn precondition. Omit text to interrupt. Unsupported transports expose no live native controls.",
            objectSchema(mapOf("runId" to stringSchema("Run"), "agent" to stringSchema("Owning participant"), "childId" to stringSchema("Observed native child"),
                "expectedTurn" to stringSchema("Exact active child turn"), "text" to stringSchema("Steering input; omit to interrupt")), listOf("runId", "agent", "childId", "expectedTurn")), false, false, true, true) {
            runBlocking { AgentWorkspaceJson.encodeToJsonElement(CommandOutcome.serializer(), workspace.controlNative(it.text("runId"), it.text("agent"), it.text("childId"), it.text("expectedTurn"), it["text"]?.jsonPrimitive?.contentOrNull)) }
        },
        McpTool("agent_checkpoints", "Inspect immutable checkpoint history, completed stages, source revision and actions with unknown outcomes.", schema("runId"), true, true) {
            workspace.get(it.text("runId"))
            buildJsonObject { put("checkpoints", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(AgentCheckpoint.serializer()), workspace.checkpoints.list(it.text("runId")))) }
        },
        McpTool("agent_checkpoint_fork", "Fork a stopped workflow. Omit fromStage to recompute everything on current source; otherwise retain unaffected stages only when source still matches. New task, fresh native sessions; incurs model usage.",
            objectSchema(mapOf("runId" to stringSchema("Source run"), "checkpointId" to stringSchema("Immutable checkpoint"),
                "requestId" to stringSchema("Idempotency key"), "fromStage" to stringSchema("Invalidate this stage and descendants")), listOf("runId", "checkpointId", "requestId")), false, true, true, true) {
            AgentWorkspaceJson.encodeToJsonElement(AgentRunRecord.serializer(), workspace.fork(it.text("runId"), it.text("checkpointId"), it.text("requestId"), it["fromStage"]?.jsonPrimitive?.contentOrNull))
        },
        McpTool("agent_interrupt", "Interrupt one live participant. Other participants retain their own native controls and results.", schema("runId", "agent"), false, true) {
            runBlocking { AgentWorkspaceJson.encodeToJsonElement(CommandOutcome.serializer(), workspace.interrupt(it.text("runId"), it.text("agent"))) }
        },
        McpTool("agent_runbooks", "Read saved, revisioned workflow definitions. Graph subjects, stage contracts and conditional edges are authored data.", emptyObjectSchema(), true, true) {
            buildJsonObject { put("runbooks", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(AgentRunbook.serializer()), workspace.runbooks.list())) }
        },
        McpTool("agent_runbook_save", "Validate and save a reusable graph workflow. Updating requires its current revision. Saving does not execute it.",
            objectSchema(mapOf("definition" to buildJsonObject { put("type", "object"); put("additionalProperties", true) },
                "expectedRevision" to stringSchema("Required to replace an existing definition")), listOf("definition")), false, true) {
            AgentWorkspaceJson.encodeToJsonElement(AgentRunbook.serializer(), workspace.runbooks.save(
                ConductorJson.decodeFromJsonElement(WorkflowDefinition.serializer(), it.getValue("definition")), it["expectedRevision"]?.jsonPrimitive?.contentOrNull))
        },
        McpTool("agent_worktrees", "Read isolated source-root ownership for this run. Worktrees include the dirty baseline; their changes require explicit review and apply.", schema("runId"), true, true) {
            workspace.get(it.text("runId"))
            buildJsonObject { put("worktrees", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(AgentWorktree.serializer()), workspace.worktrees.list(it.text("runId")))) }
        },
        McpTool("agent_worktree_review", "Inspect task-only changes against the isolated baseline and check conflicts with current source. Diff text is bounded to 60000 characters.", schema("worktreeId"), true, true) {
            AgentWorkspaceJson.encodeToJsonElement(AgentWorktreeReview.serializer(), workspace.worktrees.review(it.text("worktreeId")))
        },
        McpTool("agent_worktree_apply", "Apply the exact reviewed patch to source. Requires no active workspace runs, matching patch/source digests and no conflicts. Preserves the Git index; multi-root partial outcomes require reconciliation.",
            schema("worktreeId", "patchDigest", "sourceRevision"), false, true, true, false) {
            AgentWorkspaceJson.encodeToJsonElement(AgentWorktreeReview.serializer(), workspace.applyWorktree(it.text("worktreeId"), it.text("patchDigest"), it.text("sourceRevision")))
        },
        McpTool("agent_evaluations", "Read recent workflow outcomes with exact definition fingerprint, source, duration, attempts and reported usage. Different source/model configurations are not controlled comparisons.", emptyObjectSchema(), true, true) {
            buildJsonObject { putJsonArray("runs") { workspace.list(50).filter { it.workflow != null }.forEach { run -> add(buildJsonObject {
                put("runId", run.id); put("runbook", run.workflow!!.id)
                put("definitionRevision", digest(ConductorJson.encodeToString(WorkflowDefinition.serializer(), run.workflow)))
                put("status", run.status.name); put("durationMillis", run.updatedAt - run.startedAt); put("attempts", run.attempt)
                put("candidate", run.candidateId); run.turnUsage?.let { put("usage", AgentWorkspaceJson.encodeToJsonElement(UsageSummary.serializer(), it)) }
            }) } }; put("coverage", "Most recent 50 runs; native usage may be unavailable or partial") }
        },
    )
}
