@file:Suppress("UNCHECKED_CAST")

package dev.shibasis.reaktor.graph.di

import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.graph.di.DependencyAdapter.ScopeBuilder
import kotlin.reflect.KClass


abstract class DependencyAdapter<Controller>(
    controller: Controller
) : Adapter<Controller>(controller) {

    interface ScopeBuilder {
        fun <T : Any> factory(
            type: KClass<T>,
            qualifier: String? = null,
            definition: DependencyScopeCapability.() -> T
        )

        fun <T : Any> singleton(
            type: KClass<T>,
            qualifier: String? = null,
            definition: DependencyScopeCapability.() -> T
        )
    }

    /**
     * Create a logical scope (e.g. per Graph / Node subtree).
     * `parent` is optional if the underlying DI supports hierarchy.
     */
    abstract fun createScope(
        id: String,
        parent: DependencyScopeCapability? = null,
        configure: (ScopeBuilder.() -> Unit) = {}
    ): DependencyScopeCapability

    abstract fun closeScope(scope: DependencyScopeCapability)

    abstract fun <T : Any> get(
        scope: DependencyScopeCapability,
        type: KClass<T>,
        qualifier: String? = null,
        parameters: Map<String, Any?> = emptyMap()
    ): T
}

inline fun <reified T : Any> DependencyAdapter<*>.get(
    scope: DependencyScopeCapability,
    qualifier: String? = null,
    parameters: Map<String, Any?> = emptyMap()
): T = get(scope, T::class, qualifier, parameters)

inline fun <reified T: Any> ScopeBuilder.singleton(
    qualifier: String? = null,
    noinline definition: DependencyScopeCapability.() -> T
) = singleton(T::class, qualifier, definition)

inline fun <reified T: Any> ScopeBuilder.factory(
    qualifier: String? = null,
    noinline definition: DependencyScopeCapability.() -> T
) = factory(T::class, qualifier, definition)


var Feature.Dependency by CreateSlot<DependencyAdapter<*>>()

