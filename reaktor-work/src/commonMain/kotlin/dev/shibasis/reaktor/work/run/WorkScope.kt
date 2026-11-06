package dev.shibasis.reaktor.work.run

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.core.framework.json
import kotlinx.serialization.Serializable

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

    val key: String get() = json.encodeToString(serializer(), this)

    fun matches(context: AuthContext): Boolean =
        context.appId == appId && context.tenantId == tenantId &&
            (context.delegation?.subject ?: context.principal) == principal

    companion object {
        fun from(environment: String, context: AuthContext) =
            WorkScope(environment, context.appId, context.tenantId, context.delegation?.subject ?: context.principal)
    }
}
