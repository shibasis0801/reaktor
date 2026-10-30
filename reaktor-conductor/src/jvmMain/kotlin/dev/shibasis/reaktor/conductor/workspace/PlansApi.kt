package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.operation.Describe
import dev.shibasis.reaktor.tooling.operation.Schema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

open class PlansApi : Service("reaktor.plan") {
    open val list by call<PlanListRequest, JsonElement>(effect = Effect.ReadOnly, summary = "Every thread's plan with its title, revision, progress and when it last changed.")

    open val read by call<PlanReadRequest, JsonElement>(effect = Effect.ReadOnly, summary = "One thread's plan, your own when thread is left out: each item with its status, order, parent, links, seat and who changed it, and the revision to send as base. since <revision> adds what others changed after it.")

    open val write by call<PlanWriteRequest, JsonElement>(effect = Effect.LocalArtifactWrite, summary = "Draws or changes a thread's plan, the high-level design and running checklist people watch and edit in Hangar: components and their tasks, checks and decisions: add, update, status (Todo, Doing, Done, Dropped), connect, disconnect, relate, unrelate and remove items, with links into Reaktor. The thread defaults to your own session. Pass base, the revision you read: an op on an item someone else changed after it is refused for that item only. An attached agent passes its seat.")

    open val attach by call<PlanAttachRequest, JsonElement>(effect = Effect.LocalArtifactWrite, summary = "A person attaches a gallery agent (an agent profile) to items of a plan; Hangar starts that agent with a brief naming its seat.")

    open val shapeGraph by call<PlanShapeRequest, JsonElement>(name = "shape", effect = Effect.ReadOnly, summary = "The reaktor-graph shape of the plans named in threads, or of every plan: a graph per plan with a child graph per component, a node per item wired by order and relations, and an In Reaktor graph with a node per file, symbol, module or graph an item links, each naming the atlas call that explores it.")
}

@Serializable
class PlanListRequest

@Serializable
data class PlanReader(
    @Describe("Who makes the change") val kind: BoardActorKind,
    @Describe("The person's name, or the agent's seat") val name: String,
    @Describe("The writer's own session ref") @Schema(nullable = false) val session: String? = null,
)

@Serializable
data class PlanReadRequest(
    @Describe("hangar:<conversation id>, claude:<session id>, codex:<session id> or agy:<conversation id>; your own session when left out") @Schema(nullable = false) val thread: String? = null,
    @Describe("The revision you last read") @Schema(minimum = 0, nullable = false) val since: Long? = null,
    @Describe("Include the last 200 changes") val history: Boolean = false,
    @Schema(nullable = false) val by: PlanReader? = null,
)

@Serializable
@Schema(emptyRequired = true)
data class PlanWriteRequest(
    @Describe("hangar:<conversation id>, claude:<session id>, codex:<session id> or agy:<conversation id>; your own session when left out") @Schema(nullable = false) val thread: String? = null,
    @Describe("The plan's name, the request in a few words") @Schema(nullable = false) val title: String? = null,
    @Describe("What done looks like") @Schema(nullable = false) val goal: String? = null,
    @Describe("add {key, title, detail, kind, status, parent, after, refs, at, priority, reason, replacement}; update {item, title, detail, kind, parent, root, refs, at, priority, reason, replacement}; status {item, status: Todo | Doing | Done | Dropped, reason, replacement}. Dropped requires a nonblank reason; replacement names another item on the plan. Priority is Low, Normal (default), High or Urgent; connect {from, to}, where to waits on from; disconnect {from, to}; relate {from, to, label}, where label says what passes between them, such as reads or calls; unrelate {from, to}; remove {item}. kind is Component, Task, Check or Decision. Each op is an object with its type, such as {type: \"status\", item: \"api\", status: \"Done\"}")
    @Schema(openItems = true) val ops: List<JsonObject> = emptyList(),
    @Describe("The revision you read") @Schema(minimum = 0, nullable = false) val base: Long? = null,
    @Describe("Your seat when a person attached you to part of this plan") @Schema(nullable = false) val seat: String? = null,
    @Describe("A note to the others on this plan: kept in its log and delivered to them at their next step") @Schema(nullable = false) val message: String? = null,
)

@Serializable
data class PlanAttachRequest(
    @Describe("hangar:<conversation id>, claude:<session id>, codex:<session id> or agy:<conversation id>; your own session when left out") val thread: String,
    @Schema(itemsDescription = "An item id; the seat covers it and everything under it") val items: List<String>,
    @Describe("The agent profile id") val profile: String,
    @Describe("What the person wants from this agent, added to its brief") @Schema(nullable = false) val note: String? = null,
)

@Serializable
data class PlanShapeRequest(
    @Schema(nullable = false, itemsDescription = "A thread whose plan to include") val threads: List<String>? = null,
)
