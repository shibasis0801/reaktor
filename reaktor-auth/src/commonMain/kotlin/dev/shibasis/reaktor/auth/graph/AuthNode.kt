package dev.shibasis.reaktor.auth.graph

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.db.AuthObjectStore
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.db.Database
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.provides
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface AuthContextProvider {
    val context: StateFlow<AuthContext?>
    val current: AuthContext?
        get() = context.value
}

interface AuthContextSink {
    fun setContext(context: AuthContext)
    fun clearContext()
}

interface AuthPolicy {
    fun authorize(context: AuthContext?, requirement: AuthRequirement): AuthDecision
}

/**
 * Graph-native auth state. Non-graph callers can still use AuthAdapter, but the runtime model is AuthContext.
 */
open class AuthNode(
    graph: Graph,
    initialContext: AuthContext? = null,
    private val persist: Boolean = true
) : BasicNode(graph), AuthContextProvider, AuthContextSink {
    private val _context = MutableStateFlow(initialContext)
    override val context: StateFlow<AuthContext?> = _context.asStateFlow()

    val contextProviderPort by provides<AuthContextProvider>(this)
    val contextSinkPort by provides<AuthContextSink>(this)

    init {
        if (initialContext == null) {
            hydrate()
        }
    }

    override fun setContext(context: AuthContext) {
        _context.value = context
        if (persist) {
            launch {
                runCatching {
                    Feature.Database?.let { AuthObjectStore(it).setContext(context) }
                }.onFailure { error ->
                    Logger.e(error) { "Failed to persist AuthContext" }
                }
            }
        }
    }

    override fun clearContext() {
        _context.value = null
        if (persist) {
            launch {
                runCatching {
                    Feature.Database?.let { AuthObjectStore(it).clear() }
                }.onFailure { error ->
                    Logger.e(error) { "Failed to clear persisted AuthContext" }
                }
            }
        }
    }

    private fun hydrate() {
        if (!persist) return
        launch {
            try {
                Feature.Database?.let { db ->
                    _context.value = AuthObjectStore(db).getContext()
                }
            } catch (e: Exception) {
                Logger.e(e) { "Failed to hydrate AuthContext from ObjectDatabase" }
                _context.value = null
            }
        }
    }
}

open class AuthPolicyNode(graph: Graph) : BasicNode(graph), AuthPolicy {
    val policyPort by provides<AuthPolicy>(this)

    override fun authorize(context: AuthContext?, requirement: AuthRequirement): AuthDecision =
        LocalAuthorizer.authorize(context, requirement)
}

data class AuthGraph(
    val graph: Graph,
    val context: AuthNode,
    val policy: AuthPolicyNode
) {
    companion object {
        fun install(
            graph: Graph,
            initialContext: AuthContext? = null,
            persist: Boolean = true,
            autoWire: Boolean = true
        ): AuthGraph {
            val auth = AuthGraph(
                graph = graph,
                context = AuthNode(graph, initialContext, persist),
                policy = AuthPolicyNode(graph)
            )
            graph.attach(auth.context)
            graph.attach(auth.policy)
            if (autoWire) graph.autoWire()
            return auth
        }
    }
}
