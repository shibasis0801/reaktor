package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.operation.Describe
import dev.shibasis.reaktor.tooling.operation.Schema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

open class TeamApi : Service("reaktor.team") {
    open val read by call<TeamReadRequest, JsonElement>(effect = Effect.ReadOnly, summary = "The agent team as a graph: its members (saved agent profiles with their four-part tuple and memory role), groups such as a council, and typed links: supervises, critic of and pairs with. Returns the revision to send as base; since <revision> adds what others changed after it, and shape=true adds the team's reaktor-graph shape.")

    open val write by call<TeamWriteRequest, JsonElement>(effect = Effect.LocalArtifactWrite, summary = "Changes the agent team: add-member, retire-member (a person only), relate, unrelate, group and ungroup. Pass base, the revision you read: an op on a member, link or group someone else changed after it is refused for that op only.")
}

@Serializable
data class TeamReadRequest(
    @Describe("The revision you last read") @Schema(minimum = 0, nullable = false) val since: Long? = null,
    @Describe("Include the last 200 changes") val history: Boolean = false,
    @Describe("Add the team's reaktor-graph shape: a node per member, a child graph per group and a wire per link named by its relation") val shape: Boolean = false,
    @Schema(nullable = false) val by: PlanReader? = null,
)

@Serializable
@Schema(emptyRequired = true)
data class TeamWriteRequest(
    @Describe("add-member {profile, memory}, where profile is a saved profile id and memory is elephant (long memory across tasks) or goldfish (fresh context every task); " +
        "retire-member {profile, reason} (person only, current base required; refused while the member holds a bound seat or Doing work); " +
        "relate {from, to, kind} and unrelate {from, to, kind}, where kind is supervises, critic of or pairs with, from the lead, critic or elephant to the other member; " +
        "group {key, title, members}, which moves the members out of any other group; ungroup {key}. Each op is an object with its type, such as {type: \"relate\", from: \"bpo-lead\", to: \"bpo-engine\", kind: \"supervises\"}")
    @Schema(openItems = true) val ops: List<JsonObject> = emptyList(),
    @Describe("The revision you read") @Schema(minimum = 0, nullable = false) val base: Long? = null,
    @Describe("A note kept in the team's history") @Schema(nullable = false) val message: String? = null,
)
