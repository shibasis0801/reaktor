package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.mcp.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

fun AgentWorkspace.taskGraphCaller(graph: String?, by: BoardActor): TaskGraphCaller {
    val session = by.session ?: return TaskGraphCaller(by)
    val refs = setOfNotNull(session, threadOf(session)?.let { BoardRefs.Hangar + it })
    val record = graph?.let(board::graph)
    val own = record?.let { board.members(it.id).filter { task -> task.work.any(refs::contains) || task.graph?.run in refs }.map { it.id }.toSet() }.orEmpty()
    return TaskGraphCaller(by, lead = record?.lead != null && record.lead in refs, own = own)
}

internal fun agentTaskGraphTools(workspace: AgentWorkspace): List<McpTool> {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull ?: error("$key is required")
    fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    fun JsonObject.only(vararg keys: String) = also { args ->
        (args.keys - keys.toSet()).takeIf { it.isNotEmpty() }?.let { error("Unknown arguments: ${it.joinToString()}") }
    }
    fun <T> JsonObject.decode(serializer: KSerializer<T>, key: String): T =
        AgentWorkspaceJson.decodeFromJsonElement(serializer, get(key) ?: error("$key is required"))
    fun JsonObject.by(): BoardActor = decode(BoardActor.serializer(), "by")
    fun view(id: String) = AgentWorkspaceJson.encodeToJsonElement(TaskGraphView.serializer(), workspace.board.view(id))
    fun graph(value: TaskGraph) = AgentWorkspaceJson.encodeToJsonElement(TaskGraph.serializer(), value)
    fun applied(value: TaskGraphApplied) = buildJsonObject {
        put("graph", graph(value.graph)); put("summary", JsonArray(value.summary.map(::JsonPrimitive)))
        put("tasks", JsonArray(value.tasks.map { it.id }.map(::JsonPrimitive))); put("removed", JsonArray(value.removed.map(::JsonPrimitive)))
    }
    val graphId = "graph" to stringSchema("Task graph id, such as G3")
    val revision = "expectedRevision" to buildJsonObject { put("type", "integer"); put("minimum", 0); put("description", "The graph revision you read; a task changed since then refuses the change") }
    val by = "by" to objectSchema(mapOf(
        "kind" to enumSchema("Who makes the change", BoardActorKind.entries.map { it.name }),
        "name" to stringSchema("The person's name, or the agent's seat"),
        "session" to stringSchema("The calling session: claude:<session>, codex:<session>, agy:<conversation> or hangar:<run or conversation>"),
    ), listOf("kind", "name"))
    val any = buildJsonObject { put("type", "object"); put("additionalProperties", true) }
    val ops = "ops" to buildJsonObject {
        put("type", "array"); put("items", any)
        put("description", "Operations, each with type: add {title, key?, intent?, acceptance?, scope?, kind?, seat?, contract?, check?, dependsOn?, layout?, parent?}, " +
            "update {task, title?, intent?, acceptance?, scope?, kind?, contract?, check?, layout?}, split {task, into: [add…]}, remove {task}, " +
            "connect {from, to, condition?}, disconnect {from, to}, seat {task, seat}, note {text, layout?}, retry {task}, envelope {envelope}. " +
            "A key names a task added in the same change so later operations can refer to it.")
    }
    return listOf(
        McpTool("agent_taskgraph_list", "List the task graphs on this workspace's board with their status, progress, agents and token use.",
            emptyObjectSchema(), true, true) {
            buildJsonObject { put("graphs", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(TaskGraphSummary.serializer()), workspace.board.summaries())) }
        },
        McpTool("agent_taskgraph_read", "Read a task graph: its record, its tasks with status, agent, dependencies and outcomes, and the ready tasks. " +
            "format text returns the compact text an agent reads, at most about 60,000 characters.",
            objectSchema(mapOf(graphId, "format" to enumSchema("json or text", listOf("json", "text"))), listOf("graph")), true, true) { args ->
            args.only("graph", "format")
            val id = args.text("graph")
            if ((args["format"] as? JsonPrimitive)?.contentOrNull == "text") buildJsonObject {
                put("revision", workspace.board.graph(id).revision); put("text", TaskGraphText.render(workspace.board.view(id)))
            } else view(id)
        },
        McpTool("agent_taskgraph_seats", "The agents, models and efforts this workspace can run, for a task's seat.", emptyObjectSchema(), true, true) {
            val info = workspace.info()
            buildJsonObject {
                putJsonArray("agents") {
                    info.providers.forEach { provider ->
                        val capability = info.capability(provider)
                        addJsonObject {
                            put("agent", provider.name)
                            capability?.defaultModel?.let { put("defaultModel", it) }
                            putJsonArray("models") { capability?.models.orEmpty().forEach { add(it.id) } }
                            putJsonArray("efforts") { capability?.effort?.supported.orEmpty().forEach { add(it.value) } }
                        }
                    }
                }
                put("access", JsonArray(TaskAccess.entries.map { JsonPrimitive(it.name) }))
                put("lead", "A seat of {\"lead\": true} leaves the task to the session that proposed the graph")
            }
        },
        McpTool("agent_taskgraph_propose", "Propose a task graph: a title, the goal, its tasks with dependencies and seats, and an envelope. " +
            "It appears in Hangar as Proposed; nothing runs until a person approves it. Tasks name their dependencies by key.",
            objectSchema(mapOf(
                "title" to stringSchema("One line, at most 200 characters"),
                "goal" to stringSchema("What the graph is for and what done looks like"),
                "tasks" to buildJsonObject { put("type", "array"); put("items", any); put("description", "Tasks as add operations: {key, title, intent, acceptance, scope, kind, seat, contract, check, dependsOn: [{task: key, condition}]}") },
                "envelope" to any,
                "lead" to stringSchema("The lead session, such as claude:<session>; defaults to the caller's session"),
                by,
            ), listOf("title", "tasks", "by")), readOnly = false, idempotent = false) { args ->
            args.only("title", "goal", "tasks", "envelope", "lead", "by")
            val draft = AgentWorkspaceJson.decodeFromJsonElement(TaskGraphDraft.serializer(), JsonObject(args.filterKeys { it != "by" }))
            AgentWorkspaceJson.encodeToJsonElement(TaskGraphView.serializer(), workspace.board.propose(draft, args.by()))
        },
        McpTool("agent_taskgraph_check", "Check a change without applying it. Returns the reasons it falls outside the envelope, if any; a refusal or a conflict fails.",
            objectSchema(mapOf(graphId, ops, "reason" to stringSchema("Why"), revision, by), listOf("graph", "ops", "by")), true, true) { args ->
            args.only("graph", "ops", "reason", "expectedRevision", "by")
            val id = args.text("graph")
            val patch = TaskGraphPatch(args.decode(ListSerializer(TaskGraphOp.serializer()), "ops"), (args["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty(), args.long("expectedRevision"))
            buildJsonObject { put("outside", JsonArray(workspace.board.outside(id, patch, workspace.taskGraphCaller(id, args.by())).map(::JsonPrimitive))) }
        },
        McpTool("agent_taskgraph_patch", "Change a task graph: add, edit, split or remove tasks, add or remove dependencies, change seats or add notes. " +
            "Name the revision you read and a reason. Inside the envelope the change applies at once; outside it fails unless a person approved it.",
            objectSchema(mapOf(graphId, ops, "reason" to stringSchema("Why"), revision,
                "approvedBy" to stringSchema("The person who approved this exact change through the door"), by), listOf("graph", "ops", "by")),
            readOnly = false, idempotent = false) { args ->
            args.only("graph", "ops", "reason", "expectedRevision", "approvedBy", "by")
            val id = args.text("graph")
            val patch = TaskGraphPatch(args.decode(ListSerializer(TaskGraphOp.serializer()), "ops"), (args["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty(), args.long("expectedRevision"))
            applied(workspace.board.patch(id, patch, workspace.taskGraphCaller(id, args.by()), approvedBy = (args["approvedBy"] as? JsonPrimitive)?.contentOrNull))
        },
        McpTool("agent_taskgraph_report", "Report on a task: Running, NeedsInput, Blocked, Review, Succeeded or Failed, with a summary and evidence. " +
            "An agent reports on its own task; the lead on its tasks; only a person passes a gate.",
            objectSchema(mapOf(graphId, "task" to stringSchema("Task id"), "status" to enumSchema("The task's status", TaskReport.entries.map { it.name }),
                "summary" to stringSchema("What happened, at most 8,000 characters"), "verdict" to stringSchema("pass, repair or fail for a Decision task; accepted for a gate"),
                "files" to buildJsonObject { put("type", "array"); put("items", stringSchema("A changed file")) },
                "questions" to buildJsonObject { put("type", "array"); put("items", stringSchema("A question for the person")) }, by),
                listOf("graph", "task", "status", "by")), readOnly = false, idempotent = true) { args ->
            args.only("graph", "task", "status", "summary", "verdict", "files", "questions", "by")
            val id = args.text("graph")
            val outcome = TaskOutcome(summary = (args["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty(), verdict = (args["verdict"] as? JsonPrimitive)?.contentOrNull,
                files = (args["files"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(), questions = (args["questions"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty())
            val task = workspace.board.report(id, args.text("task"), TaskReport.valueOf(args.text("status")), outcome, workspace.taskGraphCaller(id, args.by()))
            AgentWorkspaceJson.encodeToJsonElement(BoardTask.serializer(), task)
        },
        McpTool("agent_taskgraph_claim", "The lead takes one of its ready tasks: the task starts and links the lead's session.",
            objectSchema(mapOf(graphId, "task" to stringSchema("Task id"), by), listOf("graph", "task", "by")), readOnly = false, idempotent = true) { args ->
            args.only("graph", "task", "by")
            val id = args.text("graph")
            AgentWorkspaceJson.encodeToJsonElement(BoardTask.serializer(), workspace.board.claim(id, args.text("task"), workspace.taskGraphCaller(id, args.by())))
        },
        McpTool("agent_taskgraph_start", "A person approves the graph at the revision on screen and starts it, or resumes a paused graph.",
            objectSchema(mapOf(graphId, revision, by), listOf("graph", "by")), readOnly = false, idempotent = true) { args ->
            args.only("graph", "expectedRevision", "by")
            graph(workspace.board.start(args.text("graph"), args.by(), args.long("expectedRevision"))).also { workspace.graphs?.sync() }
        },
        McpTool("agent_taskgraph_pause", "Pause a running graph: running tasks finish, no new task starts. A person or the lead pauses.",
            objectSchema(mapOf(graphId, "reason" to stringSchema("Why"), by), listOf("graph", "by")), readOnly = false, idempotent = true) { args ->
            args.only("graph", "reason", "by")
            val id = args.text("graph")
            graph(workspace.board.pause(id, workspace.taskGraphCaller(id, args.by()), (args["reason"] as? JsonPrimitive)?.contentOrNull.orEmpty()))
        },
        McpTool("agent_taskgraph_cancel", "A person cancels a graph: its open tasks are cancelled and their runs stopped.",
            objectSchema(mapOf(graphId, by), listOf("graph", "by")), readOnly = false, idempotent = true) { args ->
            args.only("graph", "by")
            graph(workspace.board.cancel(args.text("graph"), args.by())).also { workspace.graphs?.sync() }
        },
        McpTool("agent_taskgraph_revert", "A person reverts one recorded change by its revision.",
            objectSchema(mapOf(graphId, "revision" to buildJsonObject { put("type", "integer"); put("minimum", 1) }, by), listOf("graph", "revision", "by")),
            readOnly = false, idempotent = false) { args ->
            args.only("graph", "revision", "by")
            applied(workspace.board.revert(args.text("graph"), requireNotNull(args.long("revision")) { "revision is required" }, args.by()))
        },
        McpTool("agent_taskgraph_shape", "The reaktor-graph shape of a task graph: the coordinator, a node per task with its status, agent and attempt, and a wire per dependency.",
            objectSchema(mapOf(graphId), listOf("graph")), true, true) { args ->
            args.only("graph")
            AgentWorkspaceJson.encodeToJsonElement(GraphShape.serializer(), requireNotNull(workspace.graphs) { "This workspace runs no task graphs" }.shape(args.text("graph")))
        },
    )
}
