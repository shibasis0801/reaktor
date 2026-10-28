package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.mcp.*
import dev.shibasis.reaktor.tooling.operation.jsonSchema
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

internal fun agentPlanTools(workspace: AgentWorkspace): List<McpTool> {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    fun JsonObject.only(vararg keys: String) = also { args ->
        (args.keys - keys.toSet()).takeIf { it.isNotEmpty() }?.let { error("Unknown arguments: ${it.joinToString()}") }
    }
    fun JsonObject.actor(): BoardActor? = get("by")?.let { AgentWorkspaceJson.decodeFromJsonElement(BoardActor.serializer(), it) }
    fun JsonObject.thread(by: BoardActor?): String = text("thread") ?: by?.session ?: error("Name the thread: hangar:, claude:, codex: or agy: with its id")
    fun plan(value: WorkPlan, history: Boolean) = AgentWorkspaceJson.encodeToJsonElement(WorkPlan.serializer(), if (history) value else value.copy(changes = emptyList()))
    fun changes(values: List<PlanChange>) = AgentWorkspaceJson.encodeToJsonElement(ListSerializer(PlanChange.serializer()), values)
    val api = PlansApi()
    val by = "by" to PlanReader.serializer().descriptor.jsonSchema()
    fun withActor(schema: JsonObject): JsonObject = JsonObject(schema + mapOf(
        "properties" to JsonObject(schema.getValue("properties").jsonObject + by),
        "required" to JsonArray(((schema["required"] as? JsonArray).orEmpty() + JsonPrimitive("by")).distinct()),
    ))
    return listOf(
        McpTool("agent_plan_list", "List every thread's plan, newest first: its title, revision, items by status (Todo, Doing, Done, Dropped), seats and last writer.",
            api.list.request.descriptor.jsonSchema(), true, true) { args ->
            args.only()
            buildJsonObject { put("plans", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(PlanSummary.serializer()), workspace.plans.list())) }
        },
        McpTool("agent_plan_read", "Read a thread's plan: the high-level design and running checklist its agent keeps, which people edit in Hangar. " +
            "Items carry a status, the items they wait on (after), the item they belong to (parent), links into Reaktor (refs) and the seat of an attached agent. " +
            "Pass since, the revision you last read, to get news: what people and other agents changed after it.",
            api.read.request.descriptor.jsonSchema(), true, true) { args ->
            args.only("thread", "since", "history", "by")
            val reader = args.actor()
            val read = workspace.plans.read(args.thread(reader), args.long("since"), reader)
            buildJsonObject {
                put("plan", plan(read.plan, args["history"]?.jsonPrimitive?.booleanOrNull == true))
                if (args.long("since") != null) put("news", changes(read.news))
            }
        },
        McpTool("agent_plan_write", "Draw or change a thread's plan; the first write starts it and names it with title. " +
            "Pass base, the revision you read: an op on an item someone else changed after it is refused for that item only, and news lists their changes. " +
            "An attached agent passes its seat and stays inside its items. refs link Reaktor with file:, symbol:, module:, graph:, op:, flow:, task: or url:. " +
            "The answer lists what applied, what was refused and why, and the new revision.",
            withActor(api.write.request.descriptor.jsonSchema()),
            readOnly = false, idempotent = false) { args ->
            args.only("thread", "title", "goal", "ops", "base", "seat", "by")
            val writer = args.actor() ?: error("by is required: who makes this change")
            val patch = PlanPatch(
                ops = AgentWorkspaceJson.decodeFromJsonElement(ListSerializer(PlanOp.serializer()), PlanJson.statuses(args["ops"] ?: JsonArray(emptyList()))),
                title = args.text("title"), goal = args.text("goal"), base = args.long("base"), seat = args.text("seat"),
            )
            val applied = workspace.plans.write(args.thread(writer), patch, writer)
            buildJsonObject {
                put("thread", applied.plan.thread)
                put("revision", applied.plan.revision)
                put("applied", JsonArray(applied.plan.changes.lastOrNull()?.takeIf { it.revision == applied.plan.revision && it.by.same(writer) }?.summary.orEmpty().map(::JsonPrimitive)))
                put("refused", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(PlanRefusal.serializer()), applied.refused))
                put("news", changes(applied.news))
                put("statuses", AgentWorkspaceJson.encodeToJsonElement(PlanSummary.serializer(), applied.plan.summary()).jsonObject.getValue("statuses"))
            }
        },
        McpTool("agent_plan_attach", "Attach a gallery agent, an agent profile with its runtime, model, effort and instructions, to items of a plan. " +
            "Hangar starts a run of that agent whose brief names the plan, its items and its seat; the seat covers those items and everything under them. Only a person attaches agents.",
            withActor(api.attach.request.descriptor.jsonSchema()),
            readOnly = false, idempotent = false) { args ->
            args.only("thread", "items", "profile", "note", "by")
            val person = args.actor() ?: error("by is required")
            val items = (args["items"] as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
            AgentWorkspaceJson.encodeToJsonElement(PlanAttached.serializer(),
                workspace.attachPlan(args.thread(person), items, args.text("profile") ?: error("Choose a profile"), args.text("note").orEmpty(), person))
        },
        McpTool("agent_plan_shape", "Read plans as one reaktor-graph: a graph per plan, a node per item wired to what it waits on and what it belongs to, " +
            "and a node per Reaktor link (file, symbol, module, graph, operation, flow) it touches. Without threads it reads every plan.",
            api.shapeGraph.request.descriptor.jsonSchema(), true, true) { args ->
            args.only("threads")
            val wanted = (args["threads"] as? JsonArray)?.map { it.jsonPrimitive.content }
            val plans = wanted?.mapNotNull(workspace.plans::plan) ?: workspace.plans.list().mapNotNull { workspace.plans.plan(it.thread) }
            AgentWorkspaceJson.encodeToJsonElement(dev.shibasis.reaktor.graph.core.GraphShape.serializer(), PlanGraph.shape(plans))
        },
        McpTool("agent_taskgraph_archive", "Move every task graph that is not running, with its tasks, to the board's archive folder (board/archive). " +
            "Plans replace task graphs in Hangar; moving the files back restores a graph. Only a person archives.",
            objectSchema(mapOf(by), listOf("by")), readOnly = false, idempotent = true) { args ->
            args.only("by")
            buildJsonObject { put("archived", JsonArray(workspace.board.archive(args.actor() ?: error("by is required")).map(::JsonPrimitive))) }
        },
    )
}

internal object PlanJson {
    private val names = mapOf(
        "todo" to PlanStatus.Todo, "to do" to PlanStatus.Todo, "pending" to PlanStatus.Todo,
        "doing" to PlanStatus.Doing, "in progress" to PlanStatus.Doing, "in-progress" to PlanStatus.Doing, "in_progress" to PlanStatus.Doing, "started" to PlanStatus.Doing,
        "done" to PlanStatus.Done, "complete" to PlanStatus.Done, "completed" to PlanStatus.Done,
        "dropped" to PlanStatus.Dropped, "won't do" to PlanStatus.Dropped, "wont do" to PlanStatus.Dropped, "will not do" to PlanStatus.Dropped, "skipped" to PlanStatus.Dropped,
    )

    fun statuses(ops: JsonElement): JsonElement = (ops as? JsonArray)?.let { array ->
        JsonArray(array.map { op ->
            val fields = op as? JsonObject ?: return@map op
            val status = (fields["status"] as? JsonPrimitive)?.contentOrNull ?: return@map op
            val named = names[status.trim().lowercase()] ?: return@map op
            JsonObject(fields + ("status" to JsonPrimitive(named.name)))
        })
    } ?: ops
}
