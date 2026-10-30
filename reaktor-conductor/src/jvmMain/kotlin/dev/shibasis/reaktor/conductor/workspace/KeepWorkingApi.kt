package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.operation.Describe
import dev.shibasis.reaktor.tooling.operation.Schema
import kotlinx.serialization.Serializable

open class KeepWorkingApi : Service("reaktor.seat") {
    open val keepWorking by call<KeepWorkingUpdate, KeepWorkingStatus>(name = "keep-working", effect = Effect.LocalArtifactWrite,
        summary = "Bind the authenticated thread to a plan, pause or resume continuation. Only a person can configure another thread. Does not install or trust a native hook.")
    open val keepWorkingStatus by call<KeepWorkingRead, KeepWorkingStatus>(name = "keep-working-status", effect = Effect.ReadOnly,
        summary = "The authenticated thread's continuation binding, pause and last nudge. Only a person can read another thread's binding.")
    open val stop by call<KeepWorkingStop, KeepWorkingResult>(effect = Effect.LocalArtifactWrite,
        summary = "Evaluate a native Stop hook against authenticated plan readiness, honoring pause, owner stop and two nudges without progress. Never claims work.")
}

@Serializable
data class KeepWorkingUpdate(
    @Describe("Plan reference to continue") val plan: String,
    val paused: Boolean = false,
    @Describe("Defaults to the authenticated thread; another thread requires a person") @Schema(nullable = false) val thread: String? = null,
)

@Serializable
data class KeepWorkingRead(@Schema(nullable = false) val thread: String? = null)

@Serializable
data class KeepWorkingStop(@Describe("Latest owner input from the current native transcript, up to 16000 characters") val latestInput: String = "")
