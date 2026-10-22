package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.graph.AuthContextProvider
import dev.shibasis.reaktor.auth.graph.AuthGraph
import dev.shibasis.reaktor.auth.graph.guard
import dev.shibasis.reaktor.auth.graph.providesSecured
import dev.shibasis.reaktor.auth.graph.registerSecuredProvider
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.permits
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.consumes
import kotlinx.coroutines.flow.MutableStateFlow
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
        scopes = setOf(PermissionRef(name = "project:read")),
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
            auth.policy.authorize(consumer.contextProviderPort { current }, permits("project:read"))
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
        assertFalse(port.canConnect(context.copy(scopes = emptySet(), permissions = emptySet())))
    }

    @Test
    fun guardEnforcesSecuredPortsAtCallTimeAlongTheGraphPath() {
        val graph = Graph(dependencyAdapter = AuthGraphDependencyFixture(), label = "chat")
        val current = MutableStateFlow<AuthContext?>(null)
        val service = ChatServiceFixture(graph)
        val caller = ChatCallerFixture(graph)
        graph.attach(service)
        graph.attach(caller)
        graph.autoWire()
        graph.guard(object : AuthContextProvider { override val context = current })

        fun callAs(vararg permissions: String): Boolean {
            current.value = context.copy(scopes = emptySet(), permissions = permissions.map { PermissionRef(name = it) }.toSet())
            return runCatching { caller.send { send("hi") } }.isSuccess
        }

        assertTrue(callAs("messages:write"))
        assertTrue(callAs("messages:admin@chat"))
        assertTrue(callAs("messages:write@chat/ChatService"))
        assertFalse(callAs("messages:write@chat/Moderation"))
        assertFalse(callAs("messages:read"))
        assertFalse(callAs())
    }
}

private interface ChatFixture {
    fun send(text: String): String
}

private object ChatFixtureImpl : ChatFixture {
    override fun send(text: String) = text
}

private class ChatServiceFixture(graph: Graph) : BasicNode(graph) {
    override val label = "ChatService"
    val send by providesSecured<ChatFixture>(permits("messages:write"), ChatFixtureImpl)
}

private class ChatCallerFixture(graph: Graph) : BasicNode(graph) {
    val send by consumes<ChatFixture>()
}

private object AuthToolFixture

private class ContextConsumerFixture(graph: Graph) : BasicNode(graph) {
    val contextProviderPort by consumes<AuthContextProvider>()
}
