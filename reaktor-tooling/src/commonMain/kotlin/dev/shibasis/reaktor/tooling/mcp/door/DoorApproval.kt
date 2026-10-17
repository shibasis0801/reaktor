package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.tooling.SafetyClass
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class DoorApproval(
    val id: String,
    val fingerprint: String,
    val seat: String? = null,
    val provider: String,
    val call: String,
    val effect: SafetyClass,
    val arguments: JsonObject,
    val requestedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val state: State = State.Pending,
    val decidedBy: String? = null,
) {
    @Serializable enum class State { Pending, Approved, Denied, Used }
}
