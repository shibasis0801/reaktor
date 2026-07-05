package dev.shibasis.reaktor.auth.runtime.nodes

import dev.shibasis.reaktor.auth.ServiceAccount
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.runtime.ports.AuthServiceAccounts
import dev.shibasis.reaktor.auth.services.ServiceAccountService
import dev.shibasis.reaktor.auth.services.ServiceTokenResult
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides

class AuthServiceAccountNode(graph: Graph) : BasicNode(graph), AuthServiceAccounts {
    val jwtMinterPort by consumes<JwtMinter>()
    val serviceAccountsPort by provides<AuthServiceAccounts>(this)

    private val service: ServiceAccountService by lazy {
        ServiceAccountService(jwtMinterPort { this })
    }

    override fun audienceAllowed(serviceAccount: ServiceAccount, audience: String): Boolean =
        service.audienceAllowed(serviceAccount, audience)

    override fun scopesFor(serviceAccount: ServiceAccount, requested: List<String>): List<String> =
        service.scopesFor(serviceAccount, requested)

    override fun subjectOf(serviceAccount: ServiceAccount): String =
        service.subjectOf(serviceAccount)

    override fun authenticateClient(
        clientId: String?,
        clientSecret: String?,
        clientAssertion: String?,
        clientAssertionType: String?,
    ): ServiceAccount? = service.authenticateClient(clientId, clientSecret, clientAssertion, clientAssertionType)

    override fun issueClientCredentialsToken(
        clientId: String?,
        clientSecret: String?,
        clientAssertion: String?,
        clientAssertionType: String?,
        audience: String,
        requestedScopes: List<String>,
        ttlSeconds: Int,
    ): ServiceTokenResult? = service.issueClientCredentialsToken(
        clientId = clientId,
        clientSecret = clientSecret,
        clientAssertion = clientAssertion,
        clientAssertionType = clientAssertionType,
        audience = audience,
        requestedScopes = requestedScopes,
        ttlSeconds = ttlSeconds,
    )
}
