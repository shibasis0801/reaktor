package dev.shibasis.reaktor.work

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import kotlinx.serialization.Serializable
import dev.shibasis.reaktor.core.framework.json

@Serializable
data class WorkScope(
    val environment: String,
    val appId: String,
    val tenantId: String?,
    val principal: PrincipalRef,
) {
    init {
        require(environment.isNotBlank() && appId.isNotBlank())
        require(listOfNotNull(environment, appId, tenantId, principal.id).all { it.length <= 256 })
    }

    internal val storeName: String get() = "$STORE_PREFIX${json.encodeToString(serializer(), this)}"

    fun matches(context: AuthContext): Boolean =
        context.appId == appId && context.tenantId == tenantId &&
            (context.delegation?.subject ?: context.principal) == principal

    companion object {
        const val STORE_PREFIX: String = "__reaktor_work:"
        fun from(environment: String, context: AuthContext) =
            WorkScope(environment, context.appId, context.tenantId, context.delegation?.subject ?: context.principal)
    }
}

@Serializable
data class WorkIntent(
    val id: String,
    val scope: WorkScope,
    val definition: String,
    val definitionVersion: Int,
    val payloadSchema: String,
    val payload: String,
    val maxAttempts: Int = 3,
) {
    init {
        require(id.isNotBlank() && definition.isNotBlank() && definitionVersion > 0 && maxAttempts > 0)
        require(payload.encodeToByteArray().size <= 65_536) { "Work payload must reference large resources" }
    }

    val effectKey: String get() = "${scope.storeName}:$id"
}

@Serializable
enum class WorkState { QUEUED, RUNNING, HANDED_OFF, SUCCEEDED, FAILED, BLOCKED, UNKNOWN, QUARANTINED, CANCELLED }

@Serializable
data class WorkHandoff(val profile: String, val provider: String, val id: String)

@Serializable
data class WorkTransition(
    val revision: Long,
    val state: WorkState,
    val atMillis: Long,
    val attempt: Int,
    val fence: Long,
)

@Serializable
data class WorkRecord(
    val intent: WorkIntent,
    val createdAtMillis: Long,
    val nextRunAtMillis: Long,
    val state: WorkState = WorkState.QUEUED,
    val attempt: Int = 0,
    val revision: Long = 0,
    val fence: Long = 0,
    val leaseOwner: String? = null,
    val leaseUntilMillis: Long? = null,
    val checkpoint: String? = null,
    val receipt: String? = null,
    val reason: String? = null,
    val handoff: WorkHandoff? = null,
    val transitions: List<WorkTransition> = emptyList(),
)

data class ClaimToken(val scope: WorkScope, val id: String, val owner: String, val fence: Long)
data class ClaimedWork(val record: WorkRecord, val token: ClaimToken)

sealed interface WorkResult {
    data class HandedOff(val receipt: WorkHandoff) : WorkResult
    data class Success(val receipt: String) : WorkResult {
        init { require(receipt.isNotBlank() && receipt.encodeToByteArray().size <= 65_536) }
    }
    data class Retry(val reason: String, val nextRunAtMillis: Long) : WorkResult
    data class Failed(val reason: String) : WorkResult
    data class Blocked(val reason: String) : WorkResult
    data class Unknown(val reason: String) : WorkResult
    data class Quarantined(val reason: String) : WorkResult
}

sealed interface WorkAdmission {
    data class Accepted(val record: WorkRecord, val existing: Boolean) : WorkAdmission
    data class Conflict(val id: String) : WorkAdmission
}
