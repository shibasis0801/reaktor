package dev.shibasis.reaktor.conductor.workspace

import kotlinx.coroutines.runBlocking
import dev.shibasis.reaktor.mcp.*
import dev.shibasis.reaktor.tooling.operation.jsonSchema
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.io.IOException

private fun AgentWorkspace.documentPlan(plan: WorkPlan): WorkPlan {
    val path = plan.documentPath ?: return plan
    if (!PlanRules.validDocumentPath(path)) return plan.copy(documentText = null)
    val text = try {
        val root = Path.of(info().workspaceRoot).toRealPath()
        val plans = root.resolve("plans").toRealPath()
        val file = root.resolve(path).toRealPath()
        if (plans != root.resolve("plans") || !file.startsWith(plans) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(file) > 524_288) null
        else Files.newByteChannel(file, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            val buffer = ByteBuffer.allocate(524_289)
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) Unit
            if (buffer.position() > 524_288) null else Charsets.UTF_8.newDecoder().decode(buffer.flip()).toString()
        }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
    return plan.copy(documentText = text)
}

internal fun agentPlanTools(workspace: AgentWorkspace): List<McpTool> {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    fun JsonObject.only(vararg keys: String) = also { args ->
        (args.keys - keys.toSet()).takeIf { it.isNotEmpty() }?.let { error("Unknown arguments: ${it.joinToString()}") }
    }
    fun JsonObject.actor(): BoardActor? = get("by")?.let { AgentWorkspaceJson.decodeFromJsonElement(BoardActor.serializer(), it) }
    fun JsonObject.thread(by: BoardActor?): String = text("thread") ?: text("continuation")?.let(PlanReadPage::thread) ?: by?.session ?: error("Name the thread: hangar:, claude:, codex: or agy: with its id")
    fun plan(value: WorkPlan, history: Boolean): JsonElement {
        val document = workspace.documentPlan(value)
        return AgentWorkspaceJson.encodeToJsonElement(WorkPlan.serializer(), if (history) document else document.copy(changes = emptyList()))
    }
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
            "Pass since, the revision you last read, to get news and your scope. When newsTruncated is true, read again with since=newsSince before advancing to the plan revision. Follow continuation and reassemble metadataFragment first. Follow continuation until absent. " +
            "A metadataFragment carries JSON text at its offset; concatenate it before decoding claims, ready and news. An item fragment uses the same protocol for one item.",
            api.read.request.descriptor.jsonSchema(), true, true) { args ->
            args.only("thread", "since", "history", "by", "budgetBytes", "frame", "continuation")
            val reader = args.actor()
            val identity = reader?.let(workspace::planIdentity)
            val read = workspace.plans.read(args.thread(reader), args.long("since"), reader)
            val history = args["history"]?.jsonPrimitive?.booleanOrNull == true
            val presence = workspace.presence.read(listOf(read.plan))
            val claims = PlanClaims.resolve(read.plan, presence, presence.observedAt)
            val ready = when {
                reader?.kind != BoardActorKind.Agent -> PlanAnalysis.ready(read.plan).take(8)
                identity?.agent == null -> emptyList()
                else -> listOfNotNull(workspace.nextPlan(read.plan.thread, reader).candidate)
            }
            val answer = buildJsonObject {
                put("plan", plan(read.plan, history))
                put("claims", AgentWorkspaceJson.encodeToJsonElement(PlanClaims.serializer(), if (history) claims else claims.active()))
                put("ready", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(PlanCandidate.serializer()), ready))
                if (args.long("since") != null) put("news", changes(read.news))
            }
            if (reader?.kind == BoardActorKind.Agent || args["budgetBytes"] != null || args["frame"] != null || args["continuation"] != null)
                PlanReadPage.answer(read.plan, identity ?: BoardActor(BoardActorKind.Person, "Reader"), answer,
                    args.text("frame"), args.text("continuation"), args.long("budgetBytes")?.toInt(), scoped = args.long("since") != null)
            else answer
        },
        McpTool("agent_plan_schedule", "Remaining item steps, critical path and slack, schedulable dependency width and unresolved blockers. No duration estimates.",
            withActor(api.schedule.request.descriptor.jsonSchema()), true, true) { args ->
            args.only("thread", "by", "budgetBytes", "frame", "continuation")
            val reader = args.actor() ?: error("by is required")
            val read = workspace.plans.read(args.thread(reader), reader = reader)
            val report = PlanAnalysis.report(read.plan)
            if (reader.kind == BoardActorKind.Agent || args["budgetBytes"] != null || args["frame"] != null || args["continuation"] != null)
                PlanReadPage.answer(read.plan, workspace.planIdentity(reader), frame = args.text("frame"), continuation = args.text("continuation"),
                    budgetBytes = args.long("budgetBytes")?.toInt(), scoped = true) { page ->
                    val ids = page.items.mapTo(HashSet()) { it.id }
                    val selected = report.copy(remaining = report.timings.count { it.item in ids },
                        ready = report.ready.filter { it.item.id in ids }, criticalPath = report.criticalPath.filter { it in ids },
                        timings = report.timings.filter { it.item in ids }, review = report.review.filter { it.item in ids })
                    val encoded = AgentWorkspaceJson.encodeToJsonElement(PlanScheduleReport.serializer(), selected).jsonObject
                    buildJsonObject { put("schedule", JsonObject(encoded + ("ready" to JsonArray(encoded.getValue("ready").jsonArray.map(PlanReadPage::compactCandidate))))) }
                }
            else AgentWorkspaceJson.encodeToJsonElement(PlanScheduleReport.serializer(), report)
        },
        McpTool("agent_plan_next", "The next ready item ordered by inherited priority, work unblocked and age. Name thread and item to consider a specific handoff. take=true atomically claims an activity-renewed lease.",
            withActor(api.next.request.descriptor.jsonSchema()), false, false) { args ->
            args.only("thread", "agent", "seat", "by", "take", "item", "budgetBytes", "frame", "continuation")
            val actor = args.actor() ?: error("by is required")
            val requestedThread = args.text("thread") ?: args.text("continuation")?.let(PlanReadPage::thread)
            val next = workspace.nextPlan(requestedThread, actor, args.text("seat"), args.text("agent"),
                take = args["take"]?.jsonPrimitive?.booleanOrNull == true && args.text("continuation") == null, item = args.text("item"))
            val answer = AgentWorkspaceJson.encodeToJsonElement(PlanNext.serializer(), next).jsonObject
            val thread = requestedThread ?: next.candidate?.thread
            val current = thread?.let(workspace.plans::plan)
            if (current != null && (actor.kind == BoardActorKind.Agent || args["budgetBytes"] != null || args["frame"] != null || args["continuation"] != null))
                PlanReadPage.answer(current, workspace.planIdentity(actor), JsonObject(answer.mapValues { (key, value) -> if (key == "candidate") PlanReadPage.compactCandidate(value) else value }),
                    args.text("frame"), args.text("continuation"), args.long("budgetBytes")?.toInt())
            else answer
        },
        McpTool("agent_plan_write", "Draw or change a thread's plan; the first write starts it and names it with title. " +
            "Pass base, the revision you read: an op on an item someone else changed after it is refused for that item only, and news lists their changes. " +
            "An attached agent passes its seat and stays inside its items. refs link Reaktor with file:, symbol:, module:, graph:, op:, flow:, task: or url:. " +
            "Set documentPath to the plan Markdown file under plans/; headings with {#item-key} link to DAG items. " +
            "The answer lists what applied, what was refused and why, and the new revision.",
            withActor(api.write.request.descriptor.jsonSchema()),
            readOnly = false, idempotent = false) { args ->
            args.only("thread", "title", "goal", "ops", "base", "seat", "message", "documentPath", "documentText", "approval", "requestId", "limits", "by")
            val writer = args.actor() ?: error("by is required: who makes this change")
            val patch = PlanPatch(
                ops = AgentWorkspaceJson.decodeFromJsonElement(ListSerializer(PlanOp.serializer()), PlanJson.statuses(args["ops"] ?: JsonArray(emptyList()))),
                title = args.text("title"), goal = args.text("goal"), base = args.long("base"), seat = args.text("seat"), message = args.text("message"),
                documentPath = args.text("documentPath"), requestId = args.text("requestId"),
                documentText = args.text("documentText"), approval = args.text("approval")?.let(PlanApproval::valueOf),
                limits = args["limits"]?.let { value ->
                    val current = workspace.plans.plan(args.thread(writer))?.limits ?: PlanLimits()
                    val fields = AgentWorkspaceJson.encodeToJsonElement(PlanLimits.serializer(), current).jsonObject
                    AgentWorkspaceJson.decodeFromJsonElement(PlanLimits.serializer(), JsonObject(fields + value.jsonObject))
                },
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
                runBlocking { workspace.attachPlan(args.thread(person), items, args.text("profile") ?: error("Choose a profile"), args.text("note").orEmpty(), person) })
        },
        McpTool("agent_plan_shape", "Read plans as one reaktor-graph: a graph per plan with a child graph per component, a node per item wired to what it waits on and what it relates to, " +
            "and a node per Reaktor link (file, symbol, module, graph, operation, flow) it touches, naming the atlas call that explores it. Without threads it reads every plan.",
            api.shapeGraph.request.descriptor.jsonSchema(), true, true) { args ->
            args.only("threads", "by", "budgetBytes", "frame", "continuation")
            val reader = args.actor()
            val wanted = (args["threads"] as? JsonArray)?.map { it.jsonPrimitive.content }
            if (reader?.kind == BoardActorKind.Agent || args["budgetBytes"] != null || args["frame"] != null || args["continuation"] != null) {
                require(wanted == null || wanted.size == 1) { "Read one named plan at a time with bounded shape answers" }
                val thread = args.text("continuation")?.let(PlanReadPage::thread) ?: wanted?.singleOrNull() ?: reader?.session ?: error("Name one plan thread")
                val current = workspace.plans.read(thread, reader = reader).plan
                PlanReadPage.answer(current, reader?.let(workspace::planIdentity) ?: BoardActor(BoardActorKind.Person, "Reader"),
                    frame = args.text("frame"), continuation = args.text("continuation"), budgetBytes = args.long("budgetBytes")?.toInt(), scoped = true) { page ->
                    buildJsonObject { put("shape", AgentWorkspaceJson.encodeToJsonElement(dev.shibasis.reaktor.graph.core.GraphShape.serializer(), PlanGraph.shape(listOf(page)))) }
                }
            } else {
            val plans = wanted?.mapNotNull(workspace.plans::plan) ?: workspace.plans.list().mapNotNull { workspace.plans.plan(it.thread) }
            AgentWorkspaceJson.encodeToJsonElement(dev.shibasis.reaktor.graph.core.GraphShape.serializer(), PlanGraph.shape(plans))
            }
        },
        McpTool("agent_plan_archive", "Archive completed (Done) and closed (Dropped) leaf items from a thread's plan into plans/archive/ so large multi-wave plans stay compact.",
            withActor(api.archive.request.descriptor.jsonSchema()), readOnly = false, idempotent = true) { args ->
            args.only("thread", "by")
            val writer = args.actor() ?: error("by is required")
            val thread = args.thread(writer)
            val archived = workspace.plans.archive(thread, writer)
            buildJsonObject {
                put("thread", thread)
                put("archived", JsonArray(archived.map(::JsonPrimitive)))
                put("revision", workspace.plans.read(thread, reader = writer).plan.revision)
            }
        },
        McpTool("agent_taskgraph_archive", "Move every task graph that is not running, with its tasks, to the board's archive folder (board/archive). " +
            "Plans replace task graphs in Hangar; moving the files back restores a graph. Only a person archives.",
            objectSchema(mapOf(by), listOf("by")), readOnly = false, idempotent = true) { args ->
            args.only("by")
            buildJsonObject { put("archived", JsonArray(workspace.board.archive(args.actor() ?: error("by is required")).map(::JsonPrimitive))) }
        },
    ) + agentIdentityTools(workspace)
}

internal object PlanJson {
    private val names = mapOf(
        "todo" to PlanStatus.Todo, "to do" to PlanStatus.Todo, "pending" to PlanStatus.Todo,
        "doing" to PlanStatus.Doing, "in progress" to PlanStatus.Doing, "in-progress" to PlanStatus.Doing, "in_progress" to PlanStatus.Doing, "started" to PlanStatus.Doing,
        "done" to PlanStatus.Done, "complete" to PlanStatus.Done, "completed" to PlanStatus.Done,
        "dropped" to PlanStatus.Dropped, "won't do" to PlanStatus.Dropped, "wont do" to PlanStatus.Dropped, "will not do" to PlanStatus.Dropped, "skipped" to PlanStatus.Dropped,
        "parked" to PlanStatus.Dropped, "deferred" to PlanStatus.Dropped,
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
