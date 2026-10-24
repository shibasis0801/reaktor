package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.fga.FgaDsl
import dev.shibasis.reaktor.auth.fga.ModelRules
import dev.shibasis.reaktor.auth.fga.ObjectRef
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.fga.Subject
import dev.shibasis.reaktor.auth.graph.AuthContextProvider
import dev.shibasis.reaktor.auth.graph.CodeAccess
import dev.shibasis.reaktor.auth.graph.resources
import dev.shibasis.reaktor.auth.graph.CodeAccessPolicy
import dev.shibasis.reaktor.auth.graph.guard
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDenyReason
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.RoleRef
import dev.shibasis.reaktor.auth.transport.AuthRejectedException
import dev.shibasis.reaktor.auth.transport.RelationAccess
import dev.shibasis.reaktor.auth.transport.setAuthContext
import dev.shibasis.reaktor.graph.api
import dev.shibasis.reaktor.graph.serve
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.service.HttpBinding
import dev.shibasis.reaktor.service.HttpMethod
import dev.shibasis.reaktor.service.Interaction
import dev.shibasis.reaktor.service.OperationDescriptor
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.SchemaRef
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.contract.LocalLink
import dev.shibasis.reaktor.service.contract.over
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.*
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.Served

class CodeAccessTest {
    private fun descriptor(contract: String, operation: String, method: HttpMethod, route: String) = OperationDescriptor(contract, 1, "app.$contract.Request.$operation",
        Interaction.RequestReply, ServiceExecutionPhase.CLIENT, SchemaRef("app.$contract.Request", "0"), SchemaRef("app.$contract.Response", "0"), HttpBinding(method, route))

    private val shape = GraphShape(
        listOf(ScopeShape("r", "BestBuds App"), ScopeShape("c", "Circles", parent = "r"), ScopeShape("e", "Empty", parent = "r"), ScopeShape("s", "Circles", parent = "e")),
        listOf(
            NodeShape("n1", "c", "CirclesServiceNode", "CirclesServiceNode", NodeKind.Service,
                attributes = mapOf("service" to "Circles", "contract" to "circles", "version" to "2", "role" to "client", "baseUrl" to "https://api.example.test"),
                operations = listOf(descriptor("circles", "discover", HttpMethod.GET, "/circles/discover"), descriptor("circles", "join", HttpMethod.POST, "/circles/join"))),
            NodeShape("n2", "r", "RoundsServiceNode", "RoundsServiceNode", NodeKind.Service,
                attributes = mapOf("service" to "Circles", "contract" to "circles", "role" to "server"),
                operations = listOf(descriptor("circles", "join", HttpMethod.POST, "/circles/join"))),
            NodeShape("n3", "s", "Messaging", "MessagingServiceNode", NodeKind.Service,
                attributes = mapOf("contract" to "messaging", "role" to "client"),
                operations = listOf(descriptor("messaging", "send", HttpMethod.POST, "/messages"))),
            NodeShape("n4", "c", "Screen", "CircleScreen", NodeKind.Screen),
        ),
        emptyList(), emptyList(),
    )

    private val grants = listOf(
        "service:circles#caller@role:member#assignee", "operation:circles.join#blocked@role:guest#assignee",
        "graph:bestbuds#operator@role:ops#assignee", "operation:messaging.send#invoker@user:*",
        "operation:circles.join#invoker@permission:circles:write#holder",
    ).map { RelationTuple.parse(it)!! }

    private val model = FgaDsl.parse(CodeAccess.starter).model!!
    private val resources = CodeAccess.resources(shape, rootLabel = "BestBuds")
    private val policy = CodeAccessPolicy(model, grants, resources)

    private fun person(vararg roles: String, permissions: List<String> = emptyList(), kind: PrincipalKind = PrincipalKind.USER) = AuthContext(
        principal = PrincipalRef("ada", kind), appId = "app", audience = "app", method = AuthMethod.ACCESS_TOKEN,
        roles = roles.map { RoleRef(name = it) }.toSet(), permissions = permissions.map { PermissionRef(name = it) }.toSet())

    private fun allows(context: AuthContext?, contract: String, operation: String) = policy.check(context, contract, operation).allowed

    @Test fun theStarterModelIsValid() {
        assertTrue(FgaDsl.parse(CodeAccess.starter).ok)
        assertEquals(emptyList(), ModelRules.issues(model))
        assertEquals(CodeAccess.starter, FgaDsl.print(model))
    }

    @Test fun graphShapesBecomeGraphsServicesAndOperationsLinkedByTheirParents() {
        assertEquals(listOf("graph:bestbuds", "graph:bestbuds/circles", "graph:bestbuds/empty", "graph:bestbuds/empty/circles"), resources.graphs.map { it.ref.toString() })
        assertEquals(listOf(null, "graph:bestbuds", "graph:bestbuds", "graph:bestbuds/empty"), resources.graphs.map { it.parent?.toString() })
        val circles = resources.services.first()
        assertEquals("service:circles", circles.ref.toString())
        assertEquals(listOf("graph:bestbuds/circles", "graph:bestbuds"), circles.graphs.map { it.toString() })
        assertEquals(setOf("client", "server"), circles.roles)
        assertEquals(listOf("n1", "n2"), circles.nodes)
        assertEquals(listOf("operation:circles.discover", "operation:circles.join"), circles.operations.map { it.ref.toString() })
        assertEquals("POST", circles.operations.last().method)
        assertEquals("/circles/join", circles.operations.last().route)
        assertEquals(circles, resources.serviceOfNode("n2"))
        assertTrue(RelationTuple.parse("graph:bestbuds/empty/circles#parent@graph:bestbuds/empty")!! in resources.tuples)
        assertTrue(RelationTuple.parse("service:messaging#graph@graph:bestbuds/empty/circles")!! in resources.tuples)
        assertTrue(RelationTuple.parse("operation:circles.join#service@service:circles")!! in resources.tuples)
        assertEquals(9, resources.objects.size)
    }

    @Test fun accessFlowsDownTheCodeGraphAndRespectsBlocks() {
        assertTrue(allows(person("member"), "circles", "discover"))
        assertTrue(allows(person("member"), "circles", "join"))
        assertFalse(allows(person("member", "guest"), "circles", "join"))
        assertTrue(allows(person("ops"), "circles", "discover"))
        assertTrue(allows(person("ops"), "messaging", "send"))
        assertTrue(allows(person(), "messaging", "send"))
        assertFalse(allows(person(), "circles", "discover"))
        assertFalse(allows(person(kind = PrincipalKind.SERVICE), "messaging", "send"))
        assertTrue(allows(person(permissions = listOf("circles:write")), "circles", "join"))
        assertFalse(allows(person(permissions = listOf("circles:write")), "circles", "discover"))
        val denied = CodeAccessPolicy.denial(policy.check(person(), "circles", "discover"), person())
        assertEquals(AuthDenyReason.RESOURCE_DENIED, denied.reason)
        assertEquals("Nothing grants can_invoke on operation:circles.discover", denied.safeMessage)
        assertEquals(401, CodeAccessPolicy.denial(policy.check(null, "circles", "discover"), null).statusCode)
        assertTrue(allows(null, "messaging", "send"))
        val why = policy.fga.check(Subject.of("user", "ada"), "can_invoke", ObjectRef("operation", "circles.discover"), CodeAccess.contextual(person("ops")))
        assertEquals(setOf("operation:circles.discover#service@service:circles", "service:circles#graph@graph:bestbuds/circles",
            "graph:bestbuds/circles#parent@graph:bestbuds", "graph:bestbuds#operator@role:ops#assignee", "role:ops#assignee@user:ada"), why.explanation!!.facts.map { it.toString() }.toSet())
    }

    @Test fun servicesAndGraphPortsEnforceTheRelationsAtCallTime() = runTest {
        val graph = Graph(dependencyAdapter = AuthGraphDependencyFixture(), label = "BestBuds")
        val served = Served(CirclesFixture())
        graph.serve(served)
        val caller = CirclesCallerFixture(graph)
        graph.attach(caller)
        graph.autoWire()
        val live = CodeAccessPolicy(model, grants, CodeAccess.resources(graph.shape()))
        assertEquals(listOf("operation:circles.discover", "operation:circles.join"), live.resources.operations.map { it.ref.toString() })
        served.intercept(RelationAccess({ live }))
        val circles = Circles() over LocalLink(served)
        suspend fun serve(context: AuthContext?) = runCatching { circles.discover(CircleRequest().apply { setAuthContext(context) }) }
        assertTrue(serve(person("member")).isSuccess)
        assertIs<AuthRejectedException>(serve(person()).exceptionOrNull())
        val current = MutableStateFlow<AuthContext?>(null)
        graph.guard(object : AuthContextProvider { override val context = current }, live)
        suspend fun callAs(vararg roles: String): Boolean {
            current.value = person(*roles)
            return runCatching { caller.discover(CircleRequest().apply { setAuthContext(person("member")) }) }.isSuccess
        }
        assertTrue(callAs("member"))
        assertFalse(callAs())
        assertFalse(callAs("guest"))
    }
}

@Serializable private class CircleRequest : Request()
@Serializable private class CircleResponse : Response()

private open class Circles : Service("circles") {
    open val discover by get<CircleRequest, CircleResponse>("/circles/discover")
    open val join by post<CircleRequest, CircleResponse>("/circles/join")
}

private class CirclesFixture : Circles() {
    override val discover = super.discover.handle { CircleResponse() }
    override val join = super.join.handle { CircleResponse() }
}

private class CirclesCallerFixture(graph: Graph) : BasicNode(graph) {
    val discover by api(Circles().discover)
}
