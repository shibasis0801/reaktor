package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.db.AuthAuditRepository
import dev.shibasis.reaktor.auth.runtime.AUTH_EXPOSED_ADAPTER_DEPENDENCY
import dev.shibasis.reaktor.auth.runtime.ports.AuthAuditEventDraft
import dev.shibasis.reaktor.auth.runtime.ports.AuthAuditSink
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.dependency
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.db.service.ExposedAdapter
import dev.shibasis.reaktor.portgraph.port.provides

class AuthAuditNode(
    graph: Graph,
) : BasicNode(graph), AuthAuditSink {
    private val adapter by dependency<ExposedAdapter>(AUTH_EXPOSED_ADAPTER_DEPENDENCY)
    private val repository = AuthAuditRepository(adapter)

    val auditPort by provides<AuthAuditSink>(this)

    override suspend fun record(request: Request, event: AuthAuditEventDraft): Result<Unit> =
        repository.record(request, event)
}
