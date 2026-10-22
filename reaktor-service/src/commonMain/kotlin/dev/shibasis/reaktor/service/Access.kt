package dev.shibasis.reaktor.service

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.reflect.KProperty

@Serializable
sealed interface Callers {
    @Serializable
    @SerialName("everyone")
    data object Everyone : Callers

    @Serializable
    @SerialName("signed-in")
    data object SignedIn : Callers

    @Serializable
    @SerialName("holding")
    data class Holding(val permission: String) : Callers
}

@Serializable
data class ServiceAccess(val callers: Callers?, val operations: Map<String, Callers>) {
    val declared: Boolean get() = callers != null || operations.isNotEmpty()

    companion object {
        val Undeclared = ServiceAccess(null, emptyMap())
    }
}

fun ServiceAccess(callers: Callers, vararg operations: Pair<KProperty<*>, Callers>): ServiceAccess =
    ServiceAccess(callers, operations.associate { (operation, admitted) -> operation.name to admitted })
