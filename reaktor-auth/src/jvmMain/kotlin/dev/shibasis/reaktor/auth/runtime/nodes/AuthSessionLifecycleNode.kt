package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.runtime.ports.AuthSessionLifecycle
import dev.shibasis.reaktor.auth.services.NewSession
import dev.shibasis.reaktor.auth.services.RotatedSession
import dev.shibasis.reaktor.auth.services.SessionRefreshService
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.provides

class AuthSessionLifecycleNode(graph: Graph) : BasicNode(graph), AuthSessionLifecycle {
    val sessions = SessionRefreshService()
    val sessionLifecyclePort by provides<AuthSessionLifecycle>(this)

    override fun createSession(
        principalId: String,
        appId: String,
        tenantId: String?,
        contextId: String?,
    ): NewSession = sessions.createSession(principalId, appId, tenantId, contextId)

    override fun rotate(rawRefresh: String): RotatedSession? =
        sessions.rotate(rawRefresh)

    override fun revokeAllForPrincipal(principalId: String): Int =
        sessions.revokeAllForPrincipal(principalId)

    override fun revokeByRefreshToken(rawRefresh: String): Boolean =
        sessions.revokeByRefreshToken(rawRefresh)
}
