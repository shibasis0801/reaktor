package dev.shibasis.reaktor.auth.runtime

import dev.shibasis.reaktor.auth.jwt.toAuthContext
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.transport.AUTHORIZATION_HEADER
import dev.shibasis.reaktor.auth.transport.RelationAccess
import dev.shibasis.reaktor.auth.transport.bearerTokenFromHeader
import dev.shibasis.reaktor.service.contract.Exchange
import dev.shibasis.reaktor.service.contract.Served
import dev.shibasis.reaktor.auth.runtime.nodes.AuthAccountNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthAppServiceNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthAuditNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthHttpServiceNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthJwtNode
import dev.shibasis.reaktor.auth.runtime.nodes.LoginInteractorNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthPatNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthPersonalTokenNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthRepositoryNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthServiceAccountNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthSessionLifecycleNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthSessionNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthAuthorityNode
import dev.shibasis.reaktor.auth.runtime.nodes.AuthTokenGrantNode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.graph.core.requireFullyWired
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.singleton
import dev.shibasis.reaktor.graph.serve
import dev.shibasis.reaktor.db.service.ExposedAdapter

class AuthRuntimeGraph(
    parent: Graph? = null,
    database: ExposedAdapter,
    config: AuthRuntimeConfig = AuthRuntimeConfig(),
    dependencyAdapter: DependencyAdapter<*> = AuthRuntimeDependencyAdapter(),
    graphLabel: String = "reaktor-auth",
) : Graph(
    parentGraph = parent,
    label = graphLabel,
    dependencyAdapter = dependencyAdapter,
    dependencies = {
        singleton<ExposedAdapter>(AUTH_EXPOSED_ADAPTER_DEPENDENCY) { database }
        singleton<AuthRuntimeConfig>(AUTH_RUNTIME_CONFIG_DEPENDENCY) { config }
    },
) {
    val audit = Node(::AuthAuditNode)
    val repositories = Node(::AuthRepositoryNode)
    val jwt = Node(::AuthJwtNode)
    val personalTokens = Node(::AuthPersonalTokenNode)
    val sessionLifecycle = Node(::AuthSessionLifecycleNode)
    val serviceAccounts = Node(::AuthServiceAccountNode)
    val login = Node(::LoginInteractorNode)
    val pat = Node(::AuthPatNode)
    val tokenGrants = Node(::AuthTokenGrantNode)
    val sessions = Node(::AuthSessionNode)
    val authority = Node(::AuthAuthorityNode)
    val account = Node(::AuthAccountNode)
    val authHttp = Node(::AuthHttpServiceNode)
    val appHttp = Node(::AuthAppServiceNode)
    val service = Served(authHttp.service)
    val appService = Served(appHttp.service)
    val authHandlers = serve(service)
    val appHandlers = serve(appService)
    val served: List<Served<*>> get() = listOf(service, appService)

    init {
        autoWire()
        requireFullyWired()
        config.access?.let { access ->
            served.forEach { it.intercept(RelationAccess(access.policy, ::caller, access.decisions)) }
        }
    }

    private fun caller(exchange: Exchange<*, *>): AuthContext? =
        bearerTokenFromHeader(exchange.metadata[AUTHORIZATION_HEADER])?.let { jwt.verifier.verifyReaktorSignature(it).getOrNull()?.toAuthContext() }

    companion object {
        fun create(
            adapter: ExposedAdapter,
            config: AuthRuntimeConfig = AuthRuntimeConfig(),
            dependencyAdapter: DependencyAdapter<*> = AuthRuntimeDependencyAdapter(),
        ): AuthRuntimeGraph =
            AuthRuntimeGraph(
                database = adapter,
                config = config,
                dependencyAdapter = dependencyAdapter,
            )
    }
}

fun Graph.installAuthRuntime(
    adapter: ExposedAdapter,
    config: AuthRuntimeConfig = AuthRuntimeConfig(),
): AuthRuntimeGraph =
    AuthRuntimeGraph(
        parent = this,
        database = adapter,
        config = config,
        dependencyAdapter = diAdapter,
    )
