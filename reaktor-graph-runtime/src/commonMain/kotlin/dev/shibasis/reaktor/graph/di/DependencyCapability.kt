package dev.shibasis.reaktor.graph.di

import dev.shibasis.reaktor.core.capabilities.Capability
import dev.shibasis.reaktor.graph.core.node.Node
import kotlin.properties.ReadOnlyProperty

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

inline fun <reified T : Any> Node.dependency(
    qualifier: String? = null,
    parameters: Map<String, Any?> = emptyMap()
): ReadOnlyProperty<Node, T> = ReadOnlyProperty { thisRef, _ ->
    thisRef.graph.diScope.get(T::class, qualifier, parameters)
}