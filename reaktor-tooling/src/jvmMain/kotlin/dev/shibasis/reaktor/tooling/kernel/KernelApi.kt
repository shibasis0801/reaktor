package dev.shibasis.reaktor.tooling.kernel

import dev.shibasis.reaktor.service.contract.ContractShape
import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.tooling.operation.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.minutes

open class KernelApi : Service(Id) {
    open val hello by call<Unit, KernelHello>(effect = Effect.ReadOnly, summary = "The owner identity and the contracts it serves.")
    open val catalog by call<CatalogQuery, List<CatalogEntry>>(effect = Effect.ReadOnly, summary = "The workspace operation catalog.")
    open val describe by call<DescribeRequest, OperationDescriptor>(effect = Effect.ReadOnly, summary = "One workspace operation's descriptor.")
    open val invoke by call<KernelInvocation, JsonElement>(effect = Effect.UnknownRemoteEffect, deadline = 15.minutes,
        summary = "Invokes an operation that has not yet moved to a domain contract.")

    companion object {
        const val Id = "reaktor.kernel"
        const val WorkspaceHeader = "reaktor-workspace"
        const val CallerHeader = "reaktor-caller"
        const val IncarnationAttribute = "reaktor.owner.incarnation"
    }
}

@Serializable
data class KernelInvocation(val operation: OperationId, val arguments: JsonObject = JsonObject(emptyMap()))

@Serializable
data class KernelHello(
    val workspaceRoot: String,
    val incarnation: String,
    val contracts: List<ContractShape>,
    val activeRelease: String? = null,
    val sourceDigest: String? = null,
    val imageDigest: String? = null,
    val updateStatus: String = "unmanaged",
)
