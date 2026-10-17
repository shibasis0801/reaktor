package dev.shibasis.reaktor.graph.di

import dev.shibasis.reaktor.core.capabilities.Capability
import kotlin.reflect.KClass

interface DependencyScopeCapability: Capability {
    val id: String

    fun <T : Any> get(
        type: KClass<T>,
        qualifier: String? = null,
        parameters: Map<String, Any?> = emptyMap()
    ): T
}

inline fun <reified T : Any> DependencyScopeCapability.get(
    qualifier: String? = null,
    parameters: Map<String, Any?> = emptyMap()
): T = get(T::class, qualifier, parameters)
