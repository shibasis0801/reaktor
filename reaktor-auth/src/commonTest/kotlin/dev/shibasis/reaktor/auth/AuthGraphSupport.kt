package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.DependencyScopeCapability
import kotlin.reflect.KClass

internal class AuthGraphDependencyFixture : DependencyAdapter<Unit>(Unit) {
    override fun createScope(
        id: String,
        parent: DependencyScopeCapability?,
        configure: (ScopeBuilder.() -> Unit),
    ): DependencyScopeCapability = AuthGraphScopeFixture(id, parent as? AuthGraphScopeFixture)

    override fun closeScope(scope: DependencyScopeCapability) {
        scope.close()
    }

    override fun <T : Any> get(
        scope: DependencyScopeCapability,
        type: KClass<T>,
        qualifier: String?,
        parameters: Map<String, Any?>,
    ): T = (scope as AuthGraphScopeFixture).get(type, qualifier, parameters)

    override fun <T : Any> register(
        scope: DependencyScopeCapability,
        instance: T,
        type: KClass<T>,
        qualifier: String?,
    ) {
        (scope as AuthGraphScopeFixture).register(type, qualifier, instance)
    }
}

private class AuthGraphScopeFixture(
    override val id: String,
    private val parent: AuthGraphScopeFixture? = null,
) : DependencyScopeCapability {
    private val values = linkedMapOf<Pair<KClass<*>, String?>, Any>()

    override fun <T : Any> get(type: KClass<T>, qualifier: String?, parameters: Map<String, Any?>): T {
        @Suppress("UNCHECKED_CAST")
        return values[type to qualifier] as? T
            ?: parent?.get(type, qualifier, parameters)
            ?: error("Missing dependency type=$type qualifier=$qualifier")
    }

    fun <T : Any> register(type: KClass<T>, qualifier: String?, instance: T) {
        values[type to qualifier] = instance
    }

    override fun close() {
        values.clear()
    }
}
