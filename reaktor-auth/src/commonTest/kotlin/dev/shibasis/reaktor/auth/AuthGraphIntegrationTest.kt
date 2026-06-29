package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.graph.AuthContextProvider
import dev.shibasis.reaktor.auth.graph.AuthGraph
import dev.shibasis.reaktor.auth.graph.registerSecuredProvider
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.Scope
import dev.shibasis.reaktor.auth.kernel.requires
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.consumes
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthGraphIntegrationTest {
    private val context = AuthContext(
        principal = PrincipalRef("principal-1", PrincipalKind.USER),
        appId = "app-1",
        audience = "app-1",
        scopes = setOf(Scope("project:read")),
        permissions = setOf(PermissionRef(name = "project:read")),
        method = AuthMethod.ACCESS_TOKEN,
    )

    @Test
    fun authGraphExposesContextProviderThroughPorts() {
        val graph = Graph(dependencyAdapter = AuthGraphDependencyFixture())
        val auth = AuthGraph.install(graph, initialContext = context, persist = false)
        val consumer = ContextConsumerFixture(graph)
        graph.attach(consumer)
        graph.autoWire()

        assertTrue(consumer.contextProviderPort.isConnected())
        assertSame(context, consumer.contextProviderPort { current })
        assertIs<AuthDecision.Allow>(
            auth.policy.authorize(consumer.contextProviderPort { current }, requires("project:read"))
        )
    }

    @Test
    fun securedProviderAuthorizesAgainstAuthContext() {
        val graph = Graph(dependencyAdapter = AuthGraphDependencyFixture())
        val node = BasicNode(graph)
        val port = node.registerSecuredProvider<AuthToolFixture>(
            requirement = AuthRequirement(permissions = setOf(PermissionRef(name = "project:read"))),
            impl = AuthToolFixture,
        )

        assertTrue(port.canConnect(context))
        assertFalse(port.canConnect(context.copy(permissions = emptySet())))
    }
}

private object AuthToolFixture

private class ContextConsumerFixture(graph: Graph) : BasicNode(graph) {
    val contextProviderPort by consumes<AuthContextProvider>()
}
