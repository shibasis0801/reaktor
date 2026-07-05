package dev.shibasis.reaktor.auth.graph

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.portgraph.port.ConsumerPort
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.Type.Companion.Type
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortDelegate
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty

/**
 * Extension to Reaktor Graph Ports that introduces explicit Capability-Based Security boundaries.
 */

class UnauthorizedException(
    message: String
) : IllegalStateException(message)

class SecuredProviderPort<Contract: Any>(
    owner: PortCapability,
    val requiredScopes: List<String>,
    val requirement: AuthRequirement,
    key: String = "",
    type: Type,
    val contract: Contract
): ProviderPort<Contract>(owner, Key(key), type, contract) {

    // Ensures the auth session (e.g. hydrated from an AuthNode or the parent Graph Context) possesses the required scopes.
    fun canConnect(context: AuthContext?): Boolean =
        LocalAuthorizer.authorize(context, requirement) is AuthDecision.Allow

    fun canConnect(provider: AuthContextProvider): Boolean =
        canConnect(provider.current)
}

class SecuredConsumerPort<Contract: Any>(
    owner: PortCapability,
    val requiredScopes: List<String>,
    val requirement: AuthRequirement,
    key: String = "",
    type: Type
): ConsumerPort<Contract>(owner, Key(key), type) {
    
    fun enforceConnectionSecurity(context: AuthContext?) {
        when (val decision = LocalAuthorizer.authorize(context, requirement)) {
            is AuthDecision.Allow -> Unit
            is AuthDecision.Deny -> {
                Logger.e { "SecuredConsumerPort denied connection: ${decision.safeMessage}; requiredScopes=$requiredScopes" }
                throw UnauthorizedException("Unauthorized: ${decision.safeMessage}")
            }
        }
    }

    fun enforceConnectionSecurity(provider: AuthContextProvider) {
        enforceConnectionSecurity(provider.current)
    }
}

fun requirementFromScopes(requiredScopes: List<String>): AuthRequirement =
    AuthRequirement(scopes = requiredScopes.map { PermissionRef(name = it) }.toSet())

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.registerSecuredProvider(requiredScopes: List<String>, key: Key, type: Type, impl: Functionality): SecuredProviderPort<Functionality> {
    return registerSecuredProvider(requirementFromScopes(requiredScopes), requiredScopes, key, type, impl)
}

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.registerSecuredProvider(requirement: AuthRequirement, requiredScopes: List<String>, key: Key, type: Type, impl: Functionality): SecuredProviderPort<Functionality> {
    return providerPorts
        .getOrPut(type) { linkedMapOf() }
        .getOrPut(key) { SecuredProviderPort(this, requiredScopes, requirement, key.key, type, impl) } as SecuredProviderPort<Functionality>
}

inline fun <reified Functionality: Any> PortCapability.registerSecuredProvider(requiredScopes: List<String>, key: String = "", impl: Functionality): SecuredProviderPort<Functionality> {
    return registerSecuredProvider(requiredScopes, Key(key), Type<Functionality>(), impl)
}

inline fun <reified Functionality: Any> PortCapability.registerSecuredProvider(requirement: AuthRequirement, key: String = "", impl: Functionality): SecuredProviderPort<Functionality> {
    return registerSecuredProvider(requirement, requirement.scopes.map { it.value }, Key(key), Type<Functionality>(), impl)
}

inline fun <reified Functionality: Any> PortCapability.providesSecured(requiredScopes: List<String>, impl: Functionality) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ProviderPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerSecuredProvider(requiredScopes, property.name, impl)
        ReadOnlyProperty { _, _ -> port }
    }

inline fun <reified Functionality: Any> PortCapability.providesSecured(requirement: AuthRequirement, impl: Functionality) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ProviderPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerSecuredProvider(requirement, property.name, impl)
        ReadOnlyProperty { _, _ -> port }
    }

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.registerSecuredConsumer(requiredScopes: List<String>, key: Key, type: Type): SecuredConsumerPort<Functionality> {
    return registerSecuredConsumer(requirementFromScopes(requiredScopes), requiredScopes, key, type)
}

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.registerSecuredConsumer(requirement: AuthRequirement, requiredScopes: List<String>, key: Key, type: Type): SecuredConsumerPort<Functionality> {
    return consumerPorts
        .getOrPut(type) { linkedMapOf() }
        .getOrPut(key) { SecuredConsumerPort(this, requiredScopes, requirement, key.key, type) } as SecuredConsumerPort<Functionality>
}

inline fun <reified Functionality: Any> PortCapability.registerSecuredConsumer(requiredScopes: List<String>, key: String = ""): SecuredConsumerPort<Functionality> {
    return registerSecuredConsumer(requiredScopes, Key(key), Type<Functionality>())
}

inline fun <reified Functionality: Any> PortCapability.registerSecuredConsumer(requirement: AuthRequirement, key: String = ""): SecuredConsumerPort<Functionality> {
    return registerSecuredConsumer(requirement, requirement.scopes.map { it.value }, Key(key), Type<Functionality>())
}

inline fun <reified Functionality: Any> PortCapability.consumesSecured(requiredScopes: List<String>) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ConsumerPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerSecuredConsumer<Functionality>(requiredScopes, property.name)
        ReadOnlyProperty { _, _ -> port }
    }

inline fun <reified Functionality: Any> PortCapability.consumesSecured(requirement: AuthRequirement) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ConsumerPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerSecuredConsumer<Functionality>(requirement, property.name)
        ReadOnlyProperty { _, _ -> port }
    }
