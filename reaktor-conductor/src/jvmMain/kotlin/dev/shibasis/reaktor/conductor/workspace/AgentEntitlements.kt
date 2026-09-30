package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import java.nio.file.Files
import java.nio.file.Path

class AgentEntitlements(private val directory: Path) {
    init { privateDirectory(directory) }
    private fun path(pool: Entitlement) = directory.resolve("${pool.name}.json")
    @Synchronized fun get(pool: Entitlement): EntitlementAdmission = if (Files.exists(path(pool)))
        ConductorJson.decodeFromString(EntitlementAdmission.serializer(), Files.readString(path(pool))) else EntitlementAdmission(pool)
    @Synchronized fun set(pool: Entitlement, paused: Boolean, reason: String, expectedRevision: Long): EntitlementAdmission {
        val current = get(pool)
        require(current.revision == expectedRevision) { "Entitlement policy changed; reload before editing it" }
        require(reason.length <= 1000)
        return current.copy(paused = paused, reason = reason, revision = current.revision + 1, updatedAt = System.currentTimeMillis()).also {
            atomicWrite(path(pool), ConductorJson.encodeToString(EntitlementAdmission.serializer(), it))
        }
    }
    fun requireAdmitted(kinds: Collection<RuntimeKind>) {
        val blocked = kinds.flatMap { it.entitlements() }.map { it.entitlement }.distinct().map(::get).filter { it.paused }
        require(blocked.isEmpty()) { "Entitlement paused: ${blocked.joinToString { "${it.entitlement}: ${it.reason}" }}. Choose another council or re-enable the pool; no provider or billing fallback is automatic." }
    }
}
