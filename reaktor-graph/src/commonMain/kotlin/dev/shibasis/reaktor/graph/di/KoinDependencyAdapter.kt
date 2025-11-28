package dev.shibasis.reaktor.graph.di

import org.koin.core.KoinApplication
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.startKoin
import org.koin.core.definition.BeanDefinition
import org.koin.core.definition.Definition
import org.koin.core.definition.Kind
import org.koin.core.instance.FactoryInstanceFactory
import org.koin.core.instance.ScopedInstanceFactory
import org.koin.core.module.Module
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * Koin-backed DependencyAdapter for reaktor-graph.
 *
 * For each Graph, we:
 * - Create a real Koin scope with id = graph.id.toString()
 * - Materialize bindings from configureDependencies into that scope
 * - Expose them via:
 *      - DependencyScopeCapability.get(...)
 *      - koin.getScope(graphId).get(...)
 *
 * All definitions are bound with scopeQualifier = named(graphId),
 * so they behave like proper Koin scoped definitions.
 */
@OptIn(KoinInternalApi::class)
class KoinDependencyAdapter(
    private val app: KoinApplication
) : DependencyAdapter<KoinApplication>(app) {

    private val koin get() = app.koin

    // -------------------- Internal Scope Wrapper --------------------

    private class KoinScopeCapability(
        override val id: String,
        val scope: Scope,
        val module: Module
    ) : DependencyScopeCapability {

        override fun <T : Any> get(
            type: KClass<T>,
            qualifier: String?,
            parameters: Map<String, Any?>
        ): T {
            val q = qualifier?.let { named(it) }
            return scope.get(type, q) {
                parametersOf(*parameters.values.toTypedArray())
            }
        }

        override fun close() {
            scope.close()
        }
    }

    /**
     * Ephemeral DependencyScopeCapability used inside Koin definitions.
     * Backed directly by a Koin Scope so user lambdas can call `get(...)`.
     */
    private class EphemeralScopeCapability(
        override val id: String,
        private val scope: Scope
    ) : DependencyScopeCapability {

        override fun <T : Any> get(
            type: KClass<T>,
            qualifier: String?,
            parameters: Map<String, Any?>
        ): T {
            val q = qualifier?.let { named(it) }
            return scope.get(type, q) {
                parametersOf(*parameters.values.toTypedArray())
            }
        }

        override fun close() {
            // no-op
        }
    }

    // -------------------- ScopeBuilder Implementation --------------------

    /**
     * Collects bean registrations and materializes them into a Module
     * for a given graph scope.
     */
    private class ScopeBuilderImpl(
        private val scopeId: String
    ) : ScopeBuilder {

        /**
         * Each recorder installs one bean definition into the given Module,
         * using the provided scopeQualifier (named(scopeId)).
         */
        val recorders = mutableListOf<(Module, Qualifier) -> Unit>()

        override fun <T : Any> factory(
            type: KClass<T>,
            qualifier: String?,
            definition: DependencyScopeCapability.() -> T
        ) {
            recorders += { module, scopeQualifier ->
                val q = qualifier?.let { named(it) }

                val def: Definition<T> = { _ ->
                    // `this` is Koin Scope inside the definition
                    val depScope = EphemeralScopeCapability(scopeId, this)
                    definition(depScope)
                }

                val beanDef = BeanDefinition(
                    scopeQualifier = scopeQualifier,
                    primaryType = type,
                    qualifier = q,
                    definition = def,
                    kind = Kind.Factory,
                    secondaryTypes = emptyList()
                )

                val factory = FactoryInstanceFactory(beanDef)
                module.indexPrimaryType(factory)
            }
        }

        override fun <T : Any> singleton(
            type: KClass<T>,
            qualifier: String?,
            definition: DependencyScopeCapability.() -> T
        ) {
            // Scoped to this graph id (Koin "scoped" semantics)
            recorders += { module, scopeQualifier ->
                val q = qualifier?.let { named(it) }

                val def: Definition<T> = { _ ->
                    val depScope = EphemeralScopeCapability(scopeId, this)
                    definition(depScope)
                }

                val beanDef = BeanDefinition(
                    scopeQualifier = scopeQualifier,
                    primaryType = type,
                    qualifier = q,
                    definition = def,
                    kind = Kind.Scoped,
                    secondaryTypes = emptyList()
                )

                val factory = ScopedInstanceFactory(beanDef)
                module.indexPrimaryType(factory)
            }
        }
    }

    // -------------------- DependencyAdapter --------------------

    override fun createScope(
        id: String,
        parent: DependencyScopeCapability?,
        configure: (ScopeBuilder.() -> Unit)
    ): DependencyScopeCapability {
        val scopeQualifier = named(id)

        val builder = ScopeBuilderImpl(id).apply(configure)

        val module = Module()
        builder.recorders.forEach { it(module, scopeQualifier) }

        // Register all definitions for this graph scope
        koin.loadModules(listOf(module))

        // Create real Koin scope
        val scope = koin.createScope(id, scopeQualifier)

        // If parent is also Koin-backed, link scopes for resolution fallback
        if (parent is KoinScopeCapability) {
            scope.linkTo(parent.scope)
        }

        return KoinScopeCapability(id, scope, module)
    }

    override fun closeScope(scope: DependencyScopeCapability) {
        val s = scope as? KoinScopeCapability ?: return
        koin.unloadModules(listOf(s.module))
        s.close()
    }

    override fun <T : Any> get(
        scope: DependencyScopeCapability,
        type: KClass<T>,
        qualifier: String?,
        parameters: Map<String, Any?>
    ): T = (scope as KoinScopeCapability).get(type, qualifier, parameters)
}
