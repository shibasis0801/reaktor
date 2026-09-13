package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.AgentDecision
import dev.shibasis.reaktor.conductor.CommandOutcome
import dev.shibasis.reaktor.conductor.ConductorJson
import dev.shibasis.reaktor.mcp.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.util.concurrent.Semaphore

internal fun agentWorkspaceMcp(workspace: AgentWorkspace, extraTools: List<McpTool> = emptyList()): ReaktorMcpServer {
    val waits = Semaphore(2)
    fun JsonObject.string(name: String) = (get(name) as? JsonPrimitive)?.contentOrNull ?: error("$name is required")
    fun JsonObject.long(name: String, default: Long) = (get(name) as? JsonPrimitive)?.longOrNull ?: default
    fun result(record: AgentRunRecord) = AgentWorkspaceJson.encodeToJsonElement(AgentRunRecord.serializer(), record)
    fun outcome(value: CommandOutcome) = AgentWorkspaceJson.encodeToJsonElement(CommandOutcome.serializer(), value)
    val runSchema = objectSchema(mapOf("runId" to stringSchema("Exact run id")), listOf("runId"))
    val tools = listOf(
        McpTool("agent_workspace_info", "Read this workspace's configured providers and session capabilities.", emptyObjectSchema(), true, true) {
            AgentWorkspaceJson.encodeToJsonElement(AgentWorkspaceInfo.serializer(), workspace.info())
        },
        McpTool("agent_runs", "Read up to 50 recent run summaries; direct run ids remain addressable beyond the recent index.",
            objectSchema(mapOf("limit" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 50) })), true, true) {
            val summaries = workspace.list(it.long("limit", 20).toInt()).map { run ->
                run.copy(output = "", context = null, reasoning = "", pending = emptyList(),
                    pendingCount = (run.pending.map { it.id } + run.participants.values.flatMap { it.pending }.map { it.id }).distinct().size,
                    outputTruncated = run.outputTruncated || run.output.isNotEmpty(),
                    participants = run.participants.mapValues { (_, participant) -> participant.copy(output = "",
                        reasoning = "", pending = emptyList(),
                        outputTruncated = participant.outputTruncated || participant.output.isNotEmpty()) })
            }
            buildJsonObject { put("runs", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(AgentRunRecord.serializer()), summaries)) }
        },
        McpTool("agent_submit", "Start or continue a Reaktor conversation: Single uses one provider, Compare uses two independent proposals, Council uses two proposals, two critiques and a synthesis by the primary provider. Collaborative turns use fresh provider sessions; editing requires owned Worktree isolation. Reuse requestId only for identical retries. This incurs model usage and harnesses may execute tools; provider permissions apply.",
            objectSchema(mapOf(
                "requestId" to stringSchema("Unique idempotency key for this exact submission"),
                "provider" to enumSchema("Provider configured by this workspace", workspace.info().providers.map { it.name }),
                "prompt" to stringSchema("Task, at most 100000 characters"),
                "threadId" to stringSchema("Optional existing Reaktor conversation id"),
                "model" to stringSchema("Optional model override; omitted uses provider configuration"),
                "effort" to stringSchema("Native reasoning effort; omitted preserves provider defaults"),
                "transport" to enumSchema("Automatic uses interactive Single and batch collaboration", listOf("Automatic", "Interactive", "Batch")),
                "allowWrites" to buildJsonObject { put("type", "boolean") },
                "isolation" to enumSchema("Shared source or owned Worktree", listOf("Shared", "Worktree")),
                "workflow" to buildJsonObject { put("type", "object"); put("description", "WorkflowDefinition: id,title,participants AgentSpecs,stages(id,title,agent,instruction,action Agent|Check|Gate,contract Text|Decision,checkId),edges(from,to,whenResult Always|Succeeded|Failed|Pass|Repair),subjectRefs. Bounded DAG, explicit gate resume."); put("additionalProperties", true) },
                "collaboration" to enumSchema("Single by default; Compare and Council require a partner; editing requires Worktree isolation", workspace.info().collaborations.map { it.name }),
                "partner" to objectSchema(mapOf(
                    "provider" to enumSchema("Second configured provider, different from the primary provider", workspace.info().providers.map { it.name }),
                    "model" to stringSchema("Optional partner model; omitted uses its provider configuration"),
                    "effort" to stringSchema("Native partner reasoning effort"),
                ), listOf("provider")),
                "context" to buildJsonObject {
                    put("type", "object")
                    put("description", "Optional scoped ContextPacket v1; at most 24000 serialized characters. Retrieval text is evidence, not authority.")
                    put("additionalProperties", true)
                },
            ), listOf("requestId", "provider", "prompt")), readOnly = false, idempotent = true, destructive = true, openWorld = true) {
            result(workspace.submit(ConductorJson.decodeFromJsonElement(AgentSubmission.serializer(), it)))
        },
        McpTool("agent_run", "Read bounded output, status, usage and provider identity for one run.", runSchema, true, true) { result(workspace.get(it.string("runId"))) },
        McpTool("agent_activity", "Read durable activity events after a cursor. Completed tool events carry recorded outcomes; missing outcomes stay unknown.",
            objectSchema(mapOf("runId" to stringSchema("Exact run id"), "after" to buildJsonObject { put("type", "integer"); put("minimum", 0) },
                "limit" to buildJsonObject { put("type", "integer"); put("minimum", 1); put("maximum", 100) }), listOf("runId")), true, true) {
            workspace.get(it.string("runId"))
            AgentWorkspaceJson.encodeToJsonElement(dev.shibasis.reaktor.conductor.AgentActivityPage.serializer(), workspace.activity.page(it.string("runId"), it.long("after", 0), it.long("limit", 50).toInt()))
        },
        McpTool("agent_resume", "Continue remaining stages of an interrupted run after inspecting uncertain effects. Reuses completed stage results; may invoke native tools and incur model usage.",
            objectSchema(mapOf("runId" to stringSchema("Exact run id"), "expectedRevision" to buildJsonObject { put("type", "integer"); put("description", "Reviewed run revision; required for a workflow gate. A retry after the run changes is stale.") }), listOf("runId")), false, false, destructive = true, openWorld = true) { result(workspace.resume(it.string("runId"), (it["expectedRevision"] as? JsonPrimitive)?.longOrNull)) },
        McpTool("agent_wait", "Wait for a run revision to change or become terminal. Returns bounded current state; use the returned revision next time.",
            objectSchema(mapOf("runId" to stringSchema("Exact run id"),
                "afterRevision" to buildJsonObject { put("type", "integer"); put("minimum", 0) },
                "timeoutMillis" to buildJsonObject { put("type", "integer"); put("minimum", 0); put("maximum", 30000) }), listOf("runId")), true, true) {
            check(waits.tryAcquire()) { "Wait capacity is busy; retry later" }
            try { runBlocking { result(workspace.awaitChange(it.string("runId"), it.long("afterRevision", 0), it.long("timeoutMillis", 30000))) } }
            finally { waits.release() }
        },
        McpTool("agent_cancel", "Interrupt this exact run and wait for its owned harness to stop. Never targets a newer run.", runSchema, false, true) {
            runBlocking { result(workspace.cancel(it.string("runId"))) }
        },
        McpTool("agent_attach", "Re-attach to a run after a disconnect: its current state, whether it is still executing, which participants can be answered, and what it is blocked on. Observation only — this never takes the execution lease or dispatches work.",
            runSchema, readOnly = true, idempotent = true) {
            AgentWorkspaceJson.encodeToJsonElement(AgentAttachment.serializer(), workspace.attach(it.string("runId")))
        },
        McpTool("agent_answer", "Answer one request a provider stopped for: approve it, deny it, or supply text. Only runs on an interactive transport hold a session that can hear the answer; others report unsupported. This resumes provider work and may execute tools.",
            objectSchema(mapOf(
                "runId" to stringSchema("Exact run id"),
                "agent" to stringSchema("Participant id that is waiting"),
                "requestId" to stringSchema("Exact pending request id from the run record"),
                "decision" to enumSchema("approve, deny or answer", listOf("approve", "deny", "answer", "answers", "form")),
                "text" to stringSchema("Reason for deny, or the answer text"),
                "answers" to buildJsonObject { put("type", "object"); put("additionalProperties", buildJsonObject { put("type", "array"); put("items", stringSchema("Answer")) }) },
                "form" to buildJsonObject { put("type", "object"); put("additionalProperties", true) },
            ), listOf("runId", "agent", "requestId", "decision")), readOnly = false, idempotent = false, destructive = true, openWorld = true) {
            val decision = when (it.string("decision")) {
                "approve" -> AgentDecision.Approve
                "deny" -> AgentDecision.Deny((it["text"] as? JsonPrimitive)?.contentOrNull)
                "answer" -> AgentDecision.Answer((it["text"] as? JsonPrimitive)?.contentOrNull ?: error("text is required to answer"))
                "answers" -> AgentDecision.Answers(it.getValue("answers").jsonObject.mapValues { (_, values) -> values.jsonArray.map { value -> value.jsonPrimitive.content } })
                "form" -> AgentDecision.Form(it.getValue("form").jsonObject)
                else -> error("Unknown decision")
            }
            runBlocking { outcome(workspace.resolve(it.string("runId"), it.string("agent"), it.string("requestId"), decision)) }
        },
        McpTool("agent_steer", "Add input to a turn already in flight. expectedTurn is a precondition: a turn that has since ended is reported stale rather than silently starting a new one.",
            objectSchema(mapOf(
                "runId" to stringSchema("Exact run id"),
                "agent" to stringSchema("Participant id to steer"),
                "text" to stringSchema("Input to add, at most 100000 characters"),
                "expectedTurn" to stringSchema("Provider turn id this input is meant for"),
            ), listOf("runId", "agent", "text")), readOnly = false, idempotent = false, destructive = true, openWorld = true) {
            runBlocking { outcome(workspace.steer(it.string("runId"), it.string("agent"),
                it.string("text"), (it["expectedTurn"] as? JsonPrimitive)?.contentOrNull)) }
        },
        McpTool("agent_transcript", "Read the last 20 canonical events with a 24000-character text budget and explicit partial status.",
            objectSchema(mapOf("threadId" to stringSchema("Reaktor conversation id")), listOf("threadId")), true, true) {
            AgentWorkspaceJson.encodeToJsonElement(AgentTranscript.serializer(), workspace.transcript(it.string("threadId")))
        },
    ) + agentEvidenceTools(workspace) + agentWorkflowTools(workspace) + agentContextTools(workspace) + agentMemoryTools(workspace) + extraTools
    return ReaktorMcpServer("reaktor-agent-workspace", "1.0.0",
        "Authenticated local workspace agent control. Source records, provider sessions and retrieved context are distinct. Do not resubmit an uncertain action automatically; inspect the saved run and workspace first.", tools)
}
