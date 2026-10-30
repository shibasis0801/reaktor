package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.operation.Describe
import dev.shibasis.reaktor.tooling.operation.Schema
import kotlinx.serialization.Serializable

open class AgentIdentityApi : Service("reaktor.agent") {
    open val identity by call<AgentIdentityRequest, AgentIdentity>(effect = Effect.ReadOnly,
        summary = "The authenticated agent's named profile tuple, its configured model and reasoning, profile revision and native binding revision. An unbound native harness leaves unreported model and reasoning empty.")
    open val bind by call<AgentBindRequest, AgentIdentity>(effect = Effect.LocalArtifactWrite,
        summary = "Bind the authenticated native agent to a compatible named profile. A person can bind another native session. expectedRevision is the binding revision from agent.identity. Managed runs retain their pinned resolved profile.")
}

@Serializable
class AgentIdentityRequest

@Serializable
data class AgentBindRequest(
    @Describe("A saved agent profile id") val profile: String,
    @Describe("The binding revision from agent.identity") @Schema(minimum = 0) val expectedRevision: Long,
    @Describe("A native session; only a person may choose another caller") @Schema(nullable = false) val session: String? = null,
)
