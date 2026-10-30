package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.operation.Describe
import kotlinx.serialization.Serializable

open class AgentPresenceApi : Service("reaktor.presence") {
    open val read by call<AgentPresenceRequest, AgentPresencePage>(effect = Effect.ReadOnly,
        summary = "Observed agent presence: connected doors, fresh heartbeats, last action, held plan items and profile configuration. Quota remains unknown unless the provider reports it.")
    open val heartbeat by call<AgentHeartbeatRequest, AgentPresencePage>(effect = Effect.LocalArtifactWrite,
        summary = "Renew the authenticated agent's presence. A door supplies its transport-generated id and sends connected=false when it closes. A person cannot impersonate another agent.")
}

@Serializable
class AgentPresenceRequest

@Serializable
data class AgentHeartbeatRequest(@Describe("The transport-generated connection id") val door: String? = null, val connected: Boolean = true)
