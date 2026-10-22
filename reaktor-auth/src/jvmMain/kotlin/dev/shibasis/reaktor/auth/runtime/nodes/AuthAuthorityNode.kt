package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.api.*
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.runtime.AUTH_RUNTIME_CONFIG_DEPENDENCY
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.runtime.ports.*
import dev.shibasis.reaktor.auth.services.AuthorityService
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.di.dependency
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides

class AuthAuthorityNode(graph: Graph) : BasicNode(graph), AuthAuthority {
    private val config by dependency<AuthRuntimeConfig>(AUTH_RUNTIME_CONFIG_DEPENDENCY)
    val authorityDirectoryPort by consumes<AuthAuthorityDirectory>()
    val jwtVerifierPort by consumes<JwtVerifier>()
    val jwtMinterPort by consumes<JwtMinter>()
    val auditPort by consumes<AuthAuditSink>()
    val authorityPort by provides<AuthAuthority>(this)
    private fun service() = AuthorityService(jwtVerifierPort(), jwtMinterPort(), authorityDirectoryPort(), auditPort(), config.access)
    override suspend fun grants(request: AuthorityGrantsRequest) = service().grants(request)
    override suspend fun resolve(request: AuthorityResolveRequest) = service().resolve(request)
}
