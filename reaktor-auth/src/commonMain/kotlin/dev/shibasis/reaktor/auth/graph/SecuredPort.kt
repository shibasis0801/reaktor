package dev.shibasis.reaktor.auth.graph

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.User
import dev.shibasis.reaktor.graph.core.node.Node
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
    key: String = "",
    type: Type,
    val contract: Contract
): ProviderPort<Contract>(owner, Key(key), type, contract) {

    // Ensures the auth session (e.g. hydrated from an AuthNode or the parent Graph Context) possesses the required scopes.
    fun canConnect(session: AuthSession?): Boolean {
        if (session == null) return false
        val userScopes = session.scopes ?: emptyList()
        // Here we simulate an RBAC check. If ALL required scopes are present in the user's scopes:
        return requiredScopes.all { it in userScopes }
    }
}

class SecuredConsumerPort<Contract: Any>(
    owner: PortCapability,
    val requiredScopes: List<String>,
    key: String = "",
    type: Type
): ConsumerPort<Contract>(owner, Key(key), type) {
    
    fun enforceConnectionSecurity(session: AuthSession?) {
        if (session == null) {
            Logger.e { "SecuredConsumerPort denied connection: No active AuthSession found in context." }
            throw UnauthorizedException("Unauthorized: Missing AuthSession")
        }
        val userScopes = session.scopes ?: emptyList()
        if (!requiredScopes.all { it in userScopes }) {
            Logger.e { "SecuredConsumerPort denied connection: Missing required scopes $requiredScopes" }
            throw UnauthorizedException("Unauthorized: Insufficient scopes")
        }
    }
}

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.registerSecuredProvider(requiredScopes: List<String>, key: Key, type: Type, impl: Functionality): SecuredProviderPort<Functionality> {
    return providerPorts
        .getOrPut(type) { linkedMapOf() }
        .getOrPut(key) { SecuredProviderPort(this, requiredScopes, key.key, type, impl) } as SecuredProviderPort<Functionality>
}

inline fun <reified Functionality: Any> PortCapability.registerSecuredProvider(requiredScopes: List<String>, key: String = "", impl: Functionality): SecuredProviderPort<Functionality> {
    return registerSecuredProvider(requiredScopes, Key(key), Type<Functionality>(), impl)
}

inline fun <reified Functionality: Any> PortCapability.providesSecured(requiredScopes: List<String>, impl: Functionality) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ProviderPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerSecuredProvider(requiredScopes, property.name, impl)
        ReadOnlyProperty { _, _ -> port }
    }

@Suppress("UNCHECKED_CAST")
fun <Functionality: Any> PortCapability.registerSecuredConsumer(requiredScopes: List<String>, key: Key, type: Type): SecuredConsumerPort<Functionality> {
    return consumerPorts
        .getOrPut(type) { linkedMapOf() }
        .getOrPut(key) { SecuredConsumerPort(this, requiredScopes, key.key, type) } as SecuredConsumerPort<Functionality>
}

inline fun <reified Functionality: Any> PortCapability.registerSecuredConsumer(requiredScopes: List<String>, key: String = ""): SecuredConsumerPort<Functionality> {
    return registerSecuredConsumer(requiredScopes, Key(key), Type<Functionality>())
}

inline fun <reified Functionality: Any> PortCapability.consumesSecured(requiredScopes: List<String>) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ConsumerPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerSecuredConsumer<Functionality>(requiredScopes, property.name)
        ReadOnlyProperty { _, _ -> port }
    }
