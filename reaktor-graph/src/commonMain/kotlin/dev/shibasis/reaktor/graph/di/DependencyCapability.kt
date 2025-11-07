package dev.shibasis.reaktor.graph.di

import dev.shibasis.reaktor.core.capabilities.Capability
import kotlin.reflect.KClass

interface DependencyCapability : Capability {
    val diScope: DependencyScopeCapability
}

class DependencyCapabilityImpl(
    private val adapter: DependencyAdapter<*>,
    id: String,
    parentScope: DependencyScopeCapability? = null,
    configure: (DependencyAdapter.ScopeBuilder.() -> Unit) = {}
) : DependencyCapability {
    override val diScope: DependencyScopeCapability =
        adapter.createScope(id, parentScope, configure)

    override fun close() = adapter.closeScope(diScope)
}

inline fun <reified T : Any> DependencyCapability.inject(
    qualifier: String? = null,
    parameters: Map<String, Any?> = emptyMap()
): T = diScope.get(qualifier, parameters)
