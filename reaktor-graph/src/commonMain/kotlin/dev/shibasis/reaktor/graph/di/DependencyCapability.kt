package dev.shibasis.reaktor.graph.di

import dev.shibasis.reaktor.core.capabilities.Capability

interface DependencyCapability : Capability {
    val diAdapter: DependencyAdapter<*>
    val diScope: DependencyScopeCapability
}

class DependencyCapabilityImpl(
    override val diAdapter: DependencyAdapter<*>,
    id: String,
    parentScope: DependencyScopeCapability? = null,
    configure: (DependencyAdapter.ScopeBuilder.() -> Unit) = {}
) : DependencyCapability {
    override val diScope: DependencyScopeCapability =
        diAdapter.createScope(id, parentScope, configure)

    override fun close() = diAdapter.closeScope(diScope)
}

inline fun <reified T : Any> DependencyCapability.inject(
    qualifier: String? = null,
    parameters: Map<String, Any?> = emptyMap()
): T = diScope.get(qualifier, parameters)
