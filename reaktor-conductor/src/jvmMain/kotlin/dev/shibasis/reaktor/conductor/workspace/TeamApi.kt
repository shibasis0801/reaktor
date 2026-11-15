package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.operation.Describe
import dev.shibasis.reaktor.tooling.operation.Schema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

open class TeamApi : Service("reaktor.team") {
    open val read by call<TeamReadRequest, JsonElement>(effect = Effect.ReadOnly, summary = "The agent team: its topologies (graphs of nodes and edges, built in or the team's own), its variants (a topology with any saved agent attached to each node, a prompt appended to every node's prompt, and optional prompts per node) and its older member chart. Returns the revision to send as base; since <revision> adds what others changed after it, briefs=true adds the exact prompt each variant node's agent receives, and shape=true adds the team's reaktor-graph shape.")

    open val write by call<TeamWriteRequest, JsonElement>(effect = Effect.LocalArtifactWrite, summary = "Changes the agent team: topology and untopology for the team's own topologies; variant, unvariant, attach and node-prompt for variants; add-member, retire-member (a person only), relate, unrelate, group and ungroup for the member chart. Pass base, the revision you read: an op on a record someone else changed after it is refused for that op only.")
}

@Serializable
data class TeamReadRequest(
    @Describe("The revision you last read") @Schema(minimum = 0, nullable = false) val since: Long? = null,
    @Describe("Include the last 200 changes") val history: Boolean = false,
    @Describe("Add the team's reaktor-graph shape: a child graph per variant with a node per topology node, and the member chart") val shape: Boolean = false,
    @Describe("Add, for every variant node, the prompt its agent receives: the agent's own instructions, the node's prompt, its place in the topology and the variant prompt") val briefs: Boolean = false,
    @Schema(nullable = false) val by: PlanReader? = null,
)

@Serializable
@Schema(emptyRequired = true)
data class TeamWriteRequest(
    @Describe("topology {topology: {id, title, summary, nodes: [{key, name}], edges: [{from, to, label?}]}} adds or replaces one of the team's own topologies; built-in ids (" +
        "pair, chain, loop, fan-out, fan-in, split-join, hub, triangle) are fixed, so copy one under another id. A changed topology clears agents and prompts on nodes it no longer has. " +
        "untopology {id} removes one that no variant uses. variant {key, topology, title?, prompt?} adds a variant or renames it; prompt, when given, replaces the text appended to every node's prompt. " +
        "unvariant {key}. attach {variant, node, profile? | model?} puts any saved agent, or a bare model level such as claude-opus-5-5:max, on a node; with neither it clears the node. A bare model has no role, so the variant prompt is its system prompt. node-prompt {variant, node, prompt} sets the extra prompt for one node; an empty prompt clears it. " +
        "Member chart: add-member {profile, memory}, retire-member {profile, reason} (person only), relate and unrelate {from, to, kind}, group {key, title, members, parent?, summary?} and ungroup {key}. " +
        "Each op is an object with its type, such as {type: \"attach\", variant: \"council\", node: \"join\", profile: \"judge-sonnet\"}")
    @Schema(openItems = true) val ops: List<JsonObject> = emptyList(),
    @Describe("The revision you read") @Schema(minimum = 0, nullable = false) val base: Long? = null,
    @Describe("A note kept in the team's history") @Schema(nullable = false) val message: String? = null,
)
