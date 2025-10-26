package dev.shibasis.reaktor.navigation.capabilities

import dev.shibasis.reaktor.core.capabilities.Capability
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.navigation.koin.Koin
import org.koin.core.parameter.ParametersDefinition
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.ScopeDSL
import org.koin.dsl.module


typealias ScopedDependency = ScopeDSL.() -> Unit

// todo, remove koin refs and allow server side code to use spring DI instead of koin.
// https://chatgpt.com/c/68fd312b-8684-8323-aebd-a5fc63d8486d

interface DependencyCapability: Capability {
    val koinScope: Scope
    val koinQualifier: Qualifier
}


class DependencyCapabilityImpl(
    id: String,
    override val koinQualifier: Qualifier,
    val parentScope: Scope?,
    dependencies: ScopedDependency
): DependencyCapability {
    override val koinScope: Scope = Feature.Koin.koin().createScope(id, koinQualifier)
    val module = module {
        scope(koinQualifier) {
            dependencies()
        }
    }

    init {
        parentScope?.let { koinScope.linkTo(it) }
        // todo no thread safety
        Feature.Koin.load(module)
    }

    override fun close() {
        Feature.Koin.unload(module)
        parentScope?.let { koinScope.unlink(it) }
        koinScope.close()
    }
}

inline fun <reified T : Any> DependencyCapability.Get(
    qualifier: Qualifier? = null,
    noinline parameters: ParametersDefinition? = null,
): T {
    return koinScope.get(T::class, qualifier, parameters)
}

object ReaktorScope {
    val Graph = named("Reaktor.Graph")
}
