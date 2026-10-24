package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessDecisionSink
import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.fga.FgaDsl
import dev.shibasis.reaktor.auth.fga.FgaStore
import dev.shibasis.reaktor.auth.fga.ObjectRef
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.fga.Subject
import dev.shibasis.reaktor.auth.graph.CodeAccess
import dev.shibasis.reaktor.auth.graph.CodeAccessPolicy
import dev.shibasis.reaktor.auth.graph.declared
import dev.shibasis.reaktor.auth.graph.resources
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.transport.RelationAccess
import dev.shibasis.reaktor.auth.transport.setAuthContext
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.graph.serve
import dev.shibasis.reaktor.service.Callers
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.contract.over
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.LocalLink

class DeclaredAccessTest {
    private val model = FgaDsl.parse(CodeAccess.starter).model!!
    private val nothingStored = CodeAccessPolicy(model, emptyList(), modes = mapOf(FgaStore.EveryObject to AccessMode.Shadow))

    private fun person() = AuthContext(principal = PrincipalRef("ada", PrincipalKind.USER), appId = "app", audience = "app", method = AuthMethod.ACCESS_TOKEN)

    private fun worker() = AuthContext(principal = PrincipalRef("sweeper", PrincipalKind.SERVICE), appId = "app", audience = "app",
        method = AuthMethod.SERVICE_CREDENTIAL, scopes = setOf(PermissionRef(name = "worker:server")))

    @Test fun aContractStatesWhoMayCallItAsTuplesTheModelReads() {
        assertEquals(listOf("service:lobby#caller@role:authenticated#assignee", "operation:lobby.enter#invoker@role:everyone#assignee"),
            CodeAccess.declared("lobby", Lobby().access).map(RelationTuple::toString))
        assertEquals(listOf("service:lobby#caller@role:authenticated#assignee"), CodeAccess.declared("lobby", Lobby().access, "leave").map(RelationTuple::toString))
        assertEquals(listOf("service:sweeps#caller@permission:worker:server#holder"),
            CodeAccess.declared("sweeps", ServiceAccess(Callers.Holding("worker:server"))).map(RelationTuple::toString))
        assertFalse(ServiceAccess.Undeclared.declared)
        assertTrue(CodeAccess.declared("lobby", ServiceAccess.Undeclared).isEmpty())
    }

    @Test fun aGuardedServiceNeedsNoStoredGrantForWhatItsContractDeclares() = runTest {
        val seen = mutableListOf<AccessDecision>()
        val lobby = Lobby() over LocalLink(LobbyRoom()).apply { served.intercept(RelationAccess({ nothingStored }, decisions = AccessDecisionSink(seen::add))) }
        lobby.enter(LobbyCall())
        lobby.leave(LobbyCall())
        lobby.leave(LobbyCall().apply { setAuthContext(person()) })
        assertEquals(listOf("allowed", "would deny", "allowed"), seen.map { it.verdict })
        assertEquals("operation:lobby.enter#invoker@role:everyone#assignee", seen[0].rule)
        assertEquals("service:lobby#caller@role:authenticated#assignee", seen[2].rule)
        assertEquals(listOf("role:everyone#assignee@user:ada", "role:authenticated#assignee@user:ada", "service:lobby#caller@role:authenticated#assignee"), seen[2].contextual)

        val decided = seen[2]
        val target = ObjectRef.parse(decided.target)!!
        val replayed = nothingStored.fga.check(Subject.parse(decided.subject)!!, CodeAccess.Invoke, target,
            decided.contextual.mapNotNull(RelationTuple::parse) + RelationTuple(target, CodeAccess.ServiceRelation, Subject.Object(CodeAccess.service("lobby"))))
        assertTrue(replayed.allowed)
    }

    @Test fun permissionHoldersAreTheOnlyCallersOfAServiceDeclaredForThem() = runTest {
        val seen = mutableListOf<AccessDecision>()
        val sweeps = Sweeps() over LocalLink(object : Sweeps() {
            override val sweep = super.sweep.handle { LobbyAnswer() }
        }).apply { served.intercept(RelationAccess({ nothingStored }, decisions = AccessDecisionSink(seen::add))) }
        sweeps.sweep(LobbyCall().apply { setAuthContext(worker()) })
        sweeps.sweep(LobbyCall().apply { setAuthContext(person()) })
        assertEquals(listOf("allowed", "would deny"), seen.map { it.verdict })
        assertEquals("service:sweeps#caller@permission:worker:server#holder", seen[0].rule)
        assertEquals("service_account:sweeper", seen[0].subject)
    }

    @Test fun theShapeCarriesTheDeclarationSoTheMapShowsIt() {
        val graph = Graph(dependencyAdapter = AuthGraphDependencyFixture(), label = "Rooms")
        graph.serve(LobbyRoom())
        val shape = graph.shape()
        assertEquals(Lobby().access, shape.nodes.single { it.kind == NodeKind.Service }.access)
        assertEquals(shape, json.decodeFromString(GraphShape.serializer(), json.encodeToString(GraphShape.serializer(), shape)))
        val code = CodeAccess.resources(shape)
        assertEquals(CodeAccess.declared("lobby", Lobby().access), code.service(CodeAccess.service("lobby"))?.declared)
        assertTrue(code.tuples.containsAll(code.declared + code.structure))
        assertTrue(CodeAccessPolicy(model, emptyList(), code).check(person(), "lobby", "leave").allowed)
        assertFalse(CodeAccessPolicy(model, emptyList(), code).check(null, "lobby", "leave").allowed)
    }
}

@Serializable private class LobbyCall : Request()
@Serializable private class LobbyAnswer : Response()

private open class Lobby : Service("lobby") {
    override val access = ServiceAccess(Callers.SignedIn, ::enter to Callers.Everyone)

    open val enter by get<LobbyCall, LobbyAnswer>("/lobby")
    open val leave by post<LobbyCall, LobbyAnswer>("/lobby/leave")
}

private class LobbyRoom : Lobby() {
    override val enter = super.enter.handle { LobbyAnswer() }
    override val leave = super.leave.handle { LobbyAnswer() }
}

private open class Sweeps : Service("sweeps") {
    override val access = ServiceAccess(Callers.Holding("worker:server"))

    open val sweep by post<LobbyCall, LobbyAnswer>("/sweep")
}
