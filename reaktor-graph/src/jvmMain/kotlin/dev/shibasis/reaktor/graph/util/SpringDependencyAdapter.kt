package dev.shibasis.reaktor.graph.util

import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.DependencyScopeCapability
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.GenericBeanDefinition
import org.springframework.context.ConfigurableApplicationContext
import java.util.function.Supplier
import kotlin.reflect.KClass

/**
 * Spring-backed DependencyAdapter for reaktor-graph.
 *
 * Behavior:
 * - For each Graph scope, dynamically registers beans in the Spring context.
 * - singleton(...) -> real Spring singleton bean (destroyed on scope close).
 * - factory(...)   -> real Spring prototype bean (removed on scope close).
 *
 * This makes definitions visible to normal Spring mechanisms:
 *  - @Autowired
 *  - ctx.getBean(...)
 */
class SpringDependencyAdapter(
    private val ctx: ConfigurableApplicationContext
) : DependencyAdapter<ConfigurableApplicationContext>(ctx) {

    private val beanFactory: DefaultListableBeanFactory =
        ctx.beanFactory as DefaultListableBeanFactory

    private class SpringScopeCapability(
        override val id: String,
        private val ctx: ConfigurableApplicationContext,
        private val beanFactory: DefaultListableBeanFactory
    ) : DependencyScopeCapability {

        private val singletonBeans = mutableListOf<String>()
        private val prototypeBeans = mutableListOf<String>()

        fun registerSingleton(name: String, instance: Any) {
            beanFactory.registerSingleton(name, instance)
            singletonBeans += name
        }

        fun registerPrototype(name: String, type: Class<*>, supplier: () -> Any) {
            val bd = GenericBeanDefinition().apply {
                beanClass = type
                scope = BeanDefinition.SCOPE_PROTOTYPE
                instanceSupplier = Supplier { supplier() }
            }
            beanFactory.registerBeanDefinition(name, bd)
            prototypeBeans += name
        }

        override fun <T : Any> get(
            type: KClass<T>,
            qualifier: String?,
            parameters: Map<String, Any?>
        ): T {
            // parameters are ignored for now; if needed we can wire them
            return if (qualifier != null) {
                ctx.getBean(qualifier, type.java)
            } else {
                ctx.getBean(type.java)
            }
        }

        override fun close() {
            // Destroy registered singletons
            singletonBeans.forEach { name ->
                if (beanFactory.containsSingleton(name)) {
                    beanFactory.destroySingleton(name)
                }
            }
            singletonBeans.clear()

            // Remove prototype bean definitions
            prototypeBeans.forEach { name ->
                if (beanFactory.containsBeanDefinition(name)) {
                    beanFactory.removeBeanDefinition(name)
                }
            }
            prototypeBeans.clear()
        }
    }

    private class Builder(
        private val scope: SpringScopeCapability
    ) : ScopeBuilder {

        override fun <T : Any> factory(
            type: KClass<T>,
            qualifier: String?,
            definition: DependencyScopeCapability.() -> T
        ) {
            val name = qualifier ?: "${type.qualifiedName}:${scope.id}:factory"
            scope.registerPrototype(name, type.java) {
                definition(scope)
            }
        }

        override fun <T : Any> singleton(
            type: KClass<T>,
            qualifier: String?,
            definition: DependencyScopeCapability.() -> T
        ) {
            val name = qualifier ?: "${type.qualifiedName}:${scope.id}"
            val instance = definition(scope)
            scope.registerSingleton(name, instance)
        }
    }

    override fun createScope(
        id: String,
        parent: DependencyScopeCapability?,
        configure: (ScopeBuilder.() -> Unit)
    ): DependencyScopeCapability {
        val scope = SpringScopeCapability(id, ctx, beanFactory)
        val builder = Builder(scope)
        configure(builder)
        return scope
    }

    override fun closeScope(scope: DependencyScopeCapability) {
        (scope as? SpringScopeCapability)?.close()
    }

    override fun <T : Any> get(
        scope: DependencyScopeCapability,
        type: KClass<T>,
        qualifier: String?,
        parameters: Map<String, Any?>
    ): T = (scope as SpringScopeCapability).get(type, qualifier, parameters)
}
