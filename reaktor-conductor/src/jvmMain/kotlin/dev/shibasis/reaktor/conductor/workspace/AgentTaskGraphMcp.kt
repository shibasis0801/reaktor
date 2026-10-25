package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.mcp.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

fun AgentWorkspace.taskGraphCaller(graph: String?, by: BoardActor): TaskGraphCaller {
    val session = by.session ?: return TaskGraphCaller(by)
    val refs = setOfNotNull(session, threadOf(session)?.let { BoardRefs.Hangar + it })
    val record = graph?.let(board::graph)
    val own = record?.let { board.members(it.id).filter { task ->
        task.graph?.let { it.state != TaskState.Pending && it.attemptOf(refs) == it.attempt } == true
    }.map { it.id }.toSet() }.orEmpty()
    return TaskGraphCaller(by, lead = record?.lead != null && record.lead in refs, own = own, refs = refs)
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
            "connect {from, to, condition?, required?}, disconnect {from, to}, seat {task, seat}, note {text, layout?}, retry {task}, envelope {envelope}. " +
            "A key names a task added in the same change so later operations can refer to it.")
    }
    return listOf(
        McpTool("agent_taskgraph_list", "List the task graphs on this workspace's board with their status, progress, agents and token use.",
            emptyObjectSchema(), true, true) {
            buildJsonObject { put("graphs", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(TaskGraphSummary.serializer()), workspace.board.summaries())) }
        },
        McpTool("agent_taskgraph_read", "Read a task graph: its record, its tasks with status, agent, dependencies and outcomes, latest handoffs, decisions, retained messages, the ready tasks and blocked tasks with their reasons. " +
            "format text returns the compact text an agent reads, at most about 60,000 characters.",
            objectSchema(mapOf(graphId, "format" to enumSchema("json or text", listOf("json", "text"))), listOf("graph")), true, true) { args ->
            args.only("graph", "format")
            val id = args.text("graph")
            if ((args["format"] as? JsonPrimitive)?.contentOrNull == "text") buildJsonObject {
                put("revision", workspace.board.graph(id).revision); put("text", TaskGraphText.render(workspace.board.view(id)))
            } else view(id)
        },
        McpTool("agent_taskgraph_seats", "Profiles, agents, models and efforts this workspace can run. Supply seat to resolve its profile and task overrides with the same resolver used by graph checks and dispatch.",
            objectSchema(mapOf("seat" to any)), true, true) { args ->
            args.only("seat")
            val resolved = args["seat"]?.let { workspace.resolveSeat(AgentWorkspaceJson.decodeFromJsonElement(TaskSeat.serializer(), it)) }
            val info = workspace.info()
            buildJsonObject {
                putJsonArray("agents") {
                    info.providers.forEach { provider ->
                        val capability = info.capability(provider)
                        addJsonObject {
                            put("agent", provider.name)
                            capability?.defaultModel?.let { put("defaultModel", it) }
                            putJsonArray("models") { capability?.models.orEmpty().forEach { add(it.id) } }
                            put("modelChoices", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(dev.shibasis.reaktor.conductor.ModelChoice.serializer()), capability?.models.orEmpty()))
                            putJsonArray("efforts") { capability?.effort?.supported.orEmpty().forEach { add(it.value) } }
                        }
                    }
                }
                put("profiles", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(AgentProfile.serializer()), workspace.profiles.list()))
                resolved?.let { put("resolved", AgentWorkspaceJson.encodeToJsonElement(ResolvedTaskSeat.serializer(), it)) }
                put("access", JsonArray(TaskAccess.entries.map { JsonPrimitive(it.name) }))
                put("lead", "A seat of {\"lead\": true} leaves the task to the session that proposed the graph")
            }
        },
        McpTool("agent_profile_list", "List built-in and saved agent profiles with their revisions, perspectives and default seats.", emptyObjectSchema(), true, true) {
            buildJsonObject { put("profiles", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(AgentProfile.serializer()), workspace.profiles.list())) }
        },
        McpTool("agent_profile_read", "Read one agent profile and its revision. Copy a built-in profile with a new id to customize it.",
            objectSchema(mapOf("id" to stringSchema("Profile id")), listOf("id")), true, true) { args ->
            args.only("id")
            AgentWorkspaceJson.encodeToJsonElement(AgentProfile.serializer(), workspace.profiles.read(args.text("id")))
        },
        McpTool("agent_profile_save", "Save a named agent profile. Updates name expectedRevision; built-ins cannot be edited. The owner records the author.",
            objectSchema(mapOf("profile" to any, "expectedRevision" to buildJsonObject {
                put("type", "integer"); put("minimum", 0); put("description", "The profile revision you read; required when updating an existing profile")
            }, by), listOf("profile", "by")), readOnly = false, idempotent = false) { args ->
            args.only("profile", "expectedRevision", "by")
            val profile = args.decode(AgentProfile.serializer(), "profile")
            workspace.resolveSeat(TaskSeat(profile.runtime, profile.model, profile.effort, profile.access))
            AgentWorkspaceJson.encodeToJsonElement(AgentProfile.serializer(), workspace.profiles.save(profile, args.by(), args.long("expectedRevision")))
        },
        McpTool("agent_taskgraph_propose", "Propose a task graph: a title, the goal, its tasks with dependencies and seats, and an envelope. " +
            "It appears in Hangar as Proposed; nothing runs until a person approves it. Tasks name their dependencies by key.",
            objectSchema(mapOf(
                "title" to stringSchema("One line, at most 200 characters"),
                "goal" to stringSchema("What the graph is for and what done looks like"),
                "tasks" to buildJsonObject { put("type", "array"); put("items", any); put("description", "Tasks as add operations: {key, title, intent, acceptance, scope, kind, seat, contract, check, dependsOn: [{task: key, condition, required?: true}]}") },
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
            "Reports belong to the current attempt and its linked session; the lead reports on its lead tasks; only a person passes a gate.",
            objectSchema(mapOf(graphId, "task" to stringSchema("Task id"), "status" to enumSchema("The task's status", TaskReport.entries.map { it.name }),
                "attempt" to buildJsonObject { put("type", "integer"); put("minimum", 0); put("description", "The attempt observed by the caller; stale reports are refused") },
                "summary" to stringSchema("What happened, at most 8,000 characters"), "verdict" to stringSchema("pass, repair or fail for a Decision task; accepted for a gate"),
                "files" to buildJsonObject { put("type", "array"); put("items", stringSchema("A changed file")) },
                "questions" to buildJsonObject { put("type", "array"); put("items", stringSchema("A question for the person")) }, by),
                listOf("graph", "task", "status", "by")), readOnly = false, idempotent = true) { args ->
            args.only("graph", "task", "status", "attempt", "summary", "verdict", "files", "questions", "by")
            val id = args.text("graph")
            val outcome = TaskOutcome(summary = (args["summary"] as? JsonPrimitive)?.contentOrNull.orEmpty(), verdict = (args["verdict"] as? JsonPrimitive)?.contentOrNull,
                files = (args["files"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(), questions = (args["questions"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty(),
                attempt = (args["attempt"] as? JsonPrimitive)?.intOrNull)
            val task = workspace.board.report(id, args.text("task"), TaskReport.valueOf(args.text("status")), outcome, workspace.taskGraphCaller(id, args.by()))
            AgentWorkspaceJson.encodeToJsonElement(BoardTask.serializer(), task)
        },
        McpTool("agent_taskgraph_claim", "The lead takes one of its ready tasks: the task starts and links the lead's session.",
            objectSchema(mapOf(graphId, "task" to stringSchema("Task id"), by), listOf("graph", "task", "by")), readOnly = false, idempotent = true) { args ->
            args.only("graph", "task", "by")
            val id = args.text("graph")
            AgentWorkspaceJson.encodeToJsonElement(BoardTask.serializer(), workspace.board.claim(id, args.text("task"), workspace.taskGraphCaller(id, args.by())))
        },
        McpTool("agent_taskgraph_recover", "A person reconciles an interrupted attempt: Retry reuses its checkout; KeepWork captures a candidate for independent review; Abandon retains a Git snapshot before removing its writing checkout.",
            objectSchema(mapOf(graphId, "task" to stringSchema("Interrupted task id"), revision,
                "attempt" to buildJsonObject { put("type", "integer"); put("minimum", 1) },
                "action" to enumSchema("Recovery action", TaskRecoveryAction.entries.map { it.name }), by),
                listOf("graph", "task", "expectedRevision", "attempt", "action", "by")), readOnly = false, idempotent = false) { args ->
            args.only("graph", "task", "expectedRevision", "attempt", "action", "by")
            AgentWorkspaceJson.encodeToJsonElement(TaskGraphView.serializer(), workspace.recoverTask(args.text("graph"),
                args.text("task"), requireNotNull(args.long("expectedRevision")), requireNotNull(args.long("attempt")).toInt(),
                TaskRecoveryAction.valueOf(args.text("action")), args.by()))
        },
        McpTool("agent_taskgraph_accept", "A person accepts a writing task's exact candidate after its review and required checks pass.",
            objectSchema(mapOf(graphId, "task" to stringSchema("Writing task id"), "candidate" to stringSchema("Exact candidate id"), revision, by),
                listOf("graph", "task", "candidate", "expectedRevision", "by")), readOnly = false, idempotent = false) { args ->
            args.only("graph", "task", "candidate", "expectedRevision", "by")
            AgentWorkspaceJson.encodeToJsonElement(TaskGraphView.serializer(), workspace.acceptTaskCandidate(args.text("graph"),
                args.text("task"), args.text("candidate"), requireNotNull(args.long("expectedRevision")), args.by()))
        },
        McpTool("agent_taskgraph_land", "A person lands an accepted candidate's hunks in local commits, preserving unrelated work and staging, then removes its owned worktree.",
            objectSchema(mapOf(graphId, "task" to stringSchema("Writing task id"), "candidate" to stringSchema("Exact accepted candidate id"), revision, by),
                listOf("graph", "task", "candidate", "expectedRevision", "by")), readOnly = false, idempotent = true) { args ->
            args.only("graph", "task", "candidate", "expectedRevision", "by")
            runBlocking { AgentWorkspaceJson.encodeToJsonElement(TaskGraphView.serializer(), workspace.landTaskCandidate(args.text("graph"),
                args.text("task"), args.text("candidate"), requireNotNull(args.long("expectedRevision")), args.by())) }
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
