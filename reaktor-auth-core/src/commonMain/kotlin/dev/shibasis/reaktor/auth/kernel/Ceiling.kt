package dev.shibasis.reaktor.auth.kernel

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class CredentialCeiling(val version: Int = Version, val scopes: Set<String> = emptySet()) {
    val unbounded: Boolean get() = Capability.Everything in scopes

    fun covers(permission: String): Boolean = unbounded || permission in scopes ||
        Capability.parse(permission)?.let { wanted -> scopes.mapNotNull(Capability::parse).any { it.covers(wanted) } } == true

    fun covers(required: Collection<PermissionRef>): Boolean = required.all { covers(it.name) }

    fun narrow(requested: Collection<String>): List<String> = requested.filter(::covers)

    companion object {
        const val Claim = "ceil"
        const val Version = 1
        val Closed = CredentialCeiling(Version, emptySet())
        private val json = Json { ignoreUnknownKeys = true }

        fun of(claims: JsonObject): CredentialCeiling? {
            val raw = claims[Claim] ?: return null
            return runCatching { json.decodeFromJsonElement(serializer(), raw) }.getOrNull()?.takeIf { it.version == Version } ?: Closed
        }
    }
}

val AuthContext.ceiling: CredentialCeiling? get() = CredentialCeiling.of(claims)
