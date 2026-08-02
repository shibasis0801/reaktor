package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.runtime.ports.AuthSessionLifecycle
import dev.shibasis.reaktor.auth.services.NewSession
import dev.shibasis.reaktor.auth.services.RotatedSession
import dev.shibasis.reaktor.auth.services.SessionRefreshService
import dev.shibasis.reaktor.db.service.ExposedAdapter
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.provides
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.auth.runtime.AUTH_EXPOSED_ADAPTER_DEPENDENCY
import dev.shibasis.reaktor.graph.di.dependency

class AuthSessionLifecycleNode(graph: Graph) : BasicNode(graph), AuthSessionLifecycle {
    val sessions = SessionRefreshService()
    private val adapter by dependency<ExposedAdapter>(AUTH_EXPOSED_ADAPTER_DEPENDENCY)
    val sessionLifecyclePort by provides<AuthSessionLifecycle>(this)

    override fun createSession(
        principalId: String,
        appId: String,
        tenantId: String?,
        contextId: String?,
        environment: Environment,
    ): NewSession =
        sessions.createSession(principalId, appId, tenantId, contextId, adapter.databaseFor(environment))

    override fun rotate(rawRefresh: String, environment: Environment): RotatedSession? =
        sessions.rotate(rawRefresh, adapter.databaseFor(environment))

    override fun revokeAllForPrincipal(principalId: String, environment: Environment): Int =
        sessions.revokeAllForPrincipal(principalId, adapter.databaseFor(environment))

    override fun revokeByRefreshToken(rawRefresh: String, environment: Environment): Boolean =
        sessions.revokeByRefreshToken(rawRefresh, adapter.databaseFor(environment))
}
