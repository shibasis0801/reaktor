package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.mcp.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*

internal fun agentBoardTools(workspace: AgentWorkspace): List<McpTool> {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull ?: error("$key is required")
    fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    fun JsonObject.only(vararg keys: String) = also { args ->
        (args.keys - keys.toSet()).takeIf { it.isNotEmpty() }?.let { error("Unknown arguments: ${it.joinToString()}") }
    }
    fun <T> JsonObject.decode(serializer: KSerializer<T>, vararg skip: String): T =
        AgentWorkspaceJson.decodeFromJsonElement(serializer, JsonObject(filterKeys { it !in skip }))
    fun JsonObject.by(): BoardActor = AgentWorkspaceJson.decodeFromJsonElement(BoardActor.serializer(),
        get("by") ?: error("by is required: who makes this change"))
    fun entry(value: BoardEntry) = AgentWorkspaceJson.encodeToJsonElement(BoardEntry.serializer(), value)
    fun page(value: BoardPage) = AgentWorkspaceJson.encodeToJsonElement(BoardPage.serializer(), value)
    fun list(description: String) = buildJsonObject { put("type", "array"); put("items", stringSchema(description)) }
    fun integer(description: String) = buildJsonObject { put("type", "integer"); put("minimum", 0); put("description", description) }
    val task = "taskId" to stringSchema("Board task id, such as T12")
    val revision = "revision" to integer("The task revision you read; the change is refused when the task moved since")
    val by = "by" to objectSchema(mapOf(
        "kind" to enumSchema("Who makes the change", BoardActorKind.entries.map { it.name }),
        "name" to stringSchema("The person's name, or the agent's seat"),
    ), listOf("kind", "name"))
    val links = "links" to objectSchema(mapOf(
        "mission" to stringSchema("Mission id or link"), "issue" to stringSchema("Issue link"), "pullRequest" to stringSchema("Pull request link"),
    ))
    val work = list("A run or session: hangar:<conversation id>, claude:<session id> or codex:<session id>")
    val stages = BoardStage.entries.map { it.name }
    return listOf(
        McpTool("agent_board", "Read the task board. Each entry is a task and its column: Backlog, Planning, Running, Needs you, Review or Done. " +
            "Running means a linked run is executing now; Needs you means a linked run asks a question or waits for approval, a named person must act, " +
            "or the task's work stopped without a named blocker. Columns here count Hangar runs; the kernel's board.list and Hangar also count linked Claude Code and Codex sessions. " +
            "The page revision is the task revision: agent_tasks_wait wakes when the board changes.",
            objectSchema(mapOf("query" to stringSchema("Search text, or id:, title:, column: and stage: filters"))), true, true) { args ->
            args.only("query")
            page(workspace.boardPage(args["query"]?.jsonPrimitive?.content.orEmpty()))
        },
        McpTool("agent_board_task", "Read one board task with its column, linked work, blocker and history.",
            objectSchema(mapOf(task), listOf("taskId")), true, true) { args ->
            args.only("taskId")
            entry(workspace.boardEntry(args.text("taskId")))
        },
        McpTool("agent_board_create", "Put a new task on the board. It starts in Backlog unless stage says Planning or Review. " +
            "Link the runs and sessions doing it with work, or later with agent_board_link.",
            objectSchema(mapOf(
                "title" to stringSchema("One line, at most 200 characters"),
                "intent" to stringSchema("What the task is for and what done looks like"),
                "acceptance" to list("One acceptance condition"),
                "scope" to list("A path, module or graph subject the task touches"),
                "stage" to enumSchema("Where it starts", listOf("Backlog", "Planning", "Review")),
                "work" to work,
                "parent" to stringSchema("The task that started this one"),
                links, by,
            ), listOf("title", "by")), readOnly = false, idempotent = false) { args ->
            args.only("title", "intent", "acceptance", "scope", "stage", "work", "parent", "links", "by")
            entry(workspace.createBoardTask(args.decode(BoardDraft.serializer(), "by"), args.by()))
        },
        McpTool("agent_board_update", "Edit a task's title, intent, acceptance, scope or links, or set or clear its blocker. " +
            "A blocker is either another task ({type: task, task: T3}) or a named owner and the action that unblocks it " +
            "({type: owner, owner: Kedarnath, action: redeploy the dev workers}). A task blocked by an owner shows in Needs you.",
            objectSchema(mapOf(
                task,
                "title" to stringSchema("One line, at most 200 characters"),
                "intent" to stringSchema("What the task is for and what done looks like"),
                "acceptance" to list("One acceptance condition"),
                "scope" to list("A path, module or graph subject the task touches"),
                links,
                "blocker" to buildJsonObject { put("type", "object"); put("description", "{type: task, task} or {type: owner, owner, action}"); put("additionalProperties", true) },
                "unblock" to buildJsonObject { put("type", "boolean"); put("description", "Clear the blocker") },
                revision, by,
            ), listOf("taskId", "by")), readOnly = false, idempotent = true) { args ->
            args.only("taskId", "title", "intent", "acceptance", "scope", "links", "blocker", "unblock", "revision", "by")
            val id = args.text("taskId")
            entry(workspace.board.update(id, args.decode(BoardPatch.serializer(), "taskId", "revision", "by"), args.by(), args.long("revision"))
                .let { workspace.boardEntry(it.id) })
        },
        McpTool("agent_board_move", "Move a task to Backlog, Planning, Review or Done. An agent moves its finished task to Review; " +
            "only a person moves a task to Done or reopens a done task. Moving a task to the stage it is in clears a Needs-you that came from stopped work.",
            objectSchema(mapOf(task, "stage" to enumSchema("The new stage", stages), revision, by), listOf("taskId", "stage", "by")),
            readOnly = false, idempotent = true) { args ->
            args.only("taskId", "stage", "revision", "by")
            entry(workspace.board.move(args.text("taskId"), BoardStage.valueOf(args.text("stage")), args.by(), args.long("revision"))
                .let { workspace.boardEntry(it.id) })
        },
        McpTool("agent_board_link", "Link a run or session to a task, or unlink it with linked false. A task's column follows the work linked to it.",
            objectSchema(mapOf(task, "work" to stringSchema("hangar:<conversation id>, claude:<session id> or codex:<session id>"),
                "linked" to buildJsonObject { put("type", "boolean"); put("description", "False unlinks; true by default") }, by),
                listOf("taskId", "work", "by")), readOnly = false, idempotent = true) { args ->
            args.only("taskId", "work", "linked", "by")
            entry(workspace.linkBoardWork(args.text("taskId"), args.text("work"), args["linked"]?.jsonPrimitive?.booleanOrNull ?: true, args.by()))
        },
    )
}
