package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.mcp.*
import dev.shibasis.reaktor.tooling.operation.jsonSchema
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*

internal fun agentTeamTools(workspace: AgentWorkspace): List<McpTool> {
    fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull
    fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.longOrNull
    fun JsonObject.flag(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true
    fun JsonObject.only(vararg keys: String) = also { args ->
        (args.keys - keys.toSet()).takeIf { it.isNotEmpty() }?.let { error("Unknown arguments: ${it.joinToString()}") }
    }
    fun JsonObject.actor(): BoardActor? = get("by")?.let { AgentWorkspaceJson.decodeFromJsonElement(BoardActor.serializer(), it) }
    fun changes(values: List<PlanChange>) = AgentWorkspaceJson.encodeToJsonElement(ListSerializer(PlanChange.serializer()), values)
    fun profile(id: String): AgentProfile? = runCatching { workspace.profiles.read(id) }.getOrNull()
    fun busy(profile: String): String? {
        workspace.plans.list().mapNotNull { workspace.plans.plan(it.thread) }.forEach { plan ->
            plan.seats.firstOrNull { it.retired == null && it.profile == profile && (it.run != null || it.session != null || it.agent != null) }?.let { seat ->
                return "$profile holds seat ${seat.id} on ${plan.thread}, bound to ${seat.run ?: seat.session ?: seat.agent}; retire that seat first"
            }
            plan.items.firstOrNull { it.holder?.profile == profile }?.let { item ->
                return "$profile is doing ${plan.thread}#${item.id}; it finishes or returns that item first"
            }
        }
        return null
    }
    val api = TeamApi()
    val by = "by" to PlanReader.serializer().descriptor.jsonSchema()
    fun withActor(schema: JsonObject): JsonObject = JsonObject(schema + mapOf(
        "properties" to JsonObject(schema.getValue("properties").jsonObject + by),
        "required" to JsonArray(((schema["required"] as? JsonArray).orEmpty() + JsonPrimitive("by")).distinct()),
    ))
    return listOf(
        McpTool("agent_team_read", "Read the agent team as a graph: members (saved profiles with their four-part tuple and memory role, elephant or goldfish), groups such as a council, " +
            "and typed links: supervises, critic of and pairs with. Pass since, the revision you last read, to get news; shape=true adds its reaktor-graph shape.",
            api.read.request.descriptor.jsonSchema(), true, true) { args ->
            args.only("since", "history", "shape", "by")
            val read = workspace.team.read(args.long("since"), args.actor())
            buildJsonObject {
                put("team", AgentWorkspaceJson.encodeToJsonElement(AgentTeam.serializer(), if (args.flag("history")) read.team else read.team.copy(changes = emptyList())))
                put("tuples", buildJsonObject { read.team.active.forEach { member -> profile(member.profile)?.let { put(member.profile, AgentLabel.of(it)) } } })
                if (args.long("since") != null) put("news", changes(read.news))
                if (args.flag("shape")) put("shape", AgentWorkspaceJson.encodeToJsonElement(GraphShape.serializer(), read.team.shape(::profile)))
            }
        },
        McpTool("agent_team_write", "Change the agent team: add-member, retire-member (a person only), relate, unrelate, group and ungroup. " +
            "structure adds a structure from a template (${AgentStructures.templates.joinToString { it.id }}) under a key; seat puts a saved profile in one of its seats, " +
            "or empties the seat when profile is left out; unstructure removes it. " +
            "Pass base, the revision you read: an op on a member, link or group someone else changed after it is refused for that op only, and news lists their changes. " +
            "The answer lists what applied, what was refused and why, and the new revision.",
            withActor(api.write.request.descriptor.jsonSchema()),
            readOnly = false, idempotent = false) { args ->
            args.only("ops", "base", "message", "by")
            val writer = args.actor() ?: error("by is required: who makes this change")
            val patch = TeamPatch(
                ops = AgentWorkspaceJson.decodeFromJsonElement(ListSerializer(TeamOp.serializer()), TeamJson.ops(args["ops"] ?: JsonArray(emptyList()))),
                base = args.long("base"), message = args.text("message"),
            )
            val applied = workspace.team.write(patch, writer, saved = { profile(it) != null }, busy = ::busy)
            buildJsonObject {
                put("revision", applied.team.revision)
                put("applied", JsonArray(applied.applied.map(::JsonPrimitive)))
                put("refused", AgentWorkspaceJson.encodeToJsonElement(ListSerializer(PlanRefusal.serializer()), applied.refused))
                put("news", changes(applied.news))
                put("members", applied.team.active.size)
            }
        },
    )
}

internal object TeamJson {
    private val kinds = TeamLinkKind.entries.flatMap { kind ->
        listOf(kind.name, kind.label, kind.label.replace(' ', '-'), kind.label.replace(' ', '_')).map { it.lowercase() to kind.name }
    }.toMap()
    private val memories = TeamMemory.entries.associate { it.name.lowercase() to it.name }

    fun ops(ops: JsonElement): JsonElement = (ops as? JsonArray)?.let { array ->
        JsonArray(array.map { op ->
            val fields = op as? JsonObject ?: return@map op
            JsonObject(fields + listOfNotNull(fields.named("kind", kinds)?.let { "kind" to it }, fields.named("memory", memories)?.let { "memory" to it }))
        })
    } ?: ops

    private fun JsonObject.named(key: String, names: Map<String, String>): JsonPrimitive? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()?.let(names::get)?.let(::JsonPrimitive)
}
