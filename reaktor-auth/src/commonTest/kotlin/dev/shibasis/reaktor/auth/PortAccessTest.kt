package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessDecisionSink
import dev.shibasis.reaktor.auth.fga.FgaDsl
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.graph.AccessGuard
import dev.shibasis.reaktor.auth.graph.AuthContextProvider
import dev.shibasis.reaktor.auth.graph.CodeAccess
import dev.shibasis.reaktor.auth.graph.CodeAccessPolicy
import dev.shibasis.reaktor.auth.graph.UnauthorizedException
import dev.shibasis.reaktor.auth.graph.guard
import dev.shibasis.reaktor.auth.graph.providesProtected
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.RoleRef
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.provides
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortAccessTest {
    private val model = FgaDsl.parse(CodeAccess.starter).model!!
    private val session = MutableStateFlow<AuthContext?>(null)
    private val provider = object : AuthContextProvider { override val context = session }
    private val effects = mutableListOf<String>()
    private val seen = mutableListOf<AccessDecision>()

    private fun person(vararg roles: String) = AuthContext(principal = PrincipalRef("ada", PrincipalKind.USER), appId = "app", audience = "app",
        method = AuthMethod.ACCESS_TOKEN, roles = roles.map { RoleRef(name = it) }.toSet())

    private fun policy(vararg grants: String) = CodeAccessPolicy(model, grants.map { RelationTuple.parse(it)!! })

    private fun lab() = Graph(dependencyAdapter = AuthGraphDependencyFixture(), label = "Lab")

    private fun Graph.guarded(policy: CodeAccessPolicy): AccessGuard = guard(provider, policy, AccessDecisionSink(seen::add))

    private fun opens(vault: VaultNode, vararg roles: String): Boolean {
        session.value = person(*roles)
        return runCatching { vault.vault { open() } }.isSuccess
    }

    @Test
    fun aProtectedPortRefusesBeforeItsEffectOnPlainAndSuspendingCalls() = runTest {
        val graph = lab()
        val vault = VaultNode(graph, effects)
        val visitor = Visitor(graph)
        graph.attach(vault)
        graph.attach(visitor)
        graph.autoWire()
        graph.guarded(policy("node:lab/Vault#caller@role:keeper#assignee"))

        session.value = person()
        assertFailsWith<UnauthorizedException> { visitor.vault { open() } }
        assertFailsWith<UnauthorizedException> { visitor.vault.suspended { seal() } }
        assertFailsWith<UnauthorizedException> { vault.vault { open() } }
        assertEquals(emptyList(), effects)
        assertEquals("port:lab/Vault/vault", seen.last().target)
        assertEquals("Nothing grants can_invoke on port:lab/Vault/vault", seen.last().rule)

        session.value = person("keeper")
        assertEquals("opened", visitor.vault { open() })
        assertEquals("sealed", visitor.vault.suspended { seal() })
        assertEquals(listOf("opened", "sealed"), effects)
        assertEquals("node:lab/Vault#caller@role:keeper#assignee", seen.last().rule)
    }

    @Test
    fun anUnprotectedPortIsNotChecked() {
        val graph = lab()
        val hall = HallNode(graph)
        graph.attach(hall)
        graph.guarded(policy())
        session.value = person()
        assertEquals("welcome", hall.hall { enter() })
        assertTrue(seen.isEmpty())
    }

    @Test
    fun nodesAttachedLaterAndNestedGraphsAreGuardedWithTheirGraphsGrants() {
        val graph = lab()
        graph.guarded(policy("graph:lab#operator@role:ops#assignee", "port:lab/wing/Vault/vault#invoker@role:keeper#assignee",
            "port:lab/wing/Vault/vault#blocked@role:visitor#assignee"))
        val wing = graph.Graph { Graph(parentGraph = it, label = "Wing") }
        val vault = VaultNode(wing, effects)
        wing.attach(vault)

        assertFalse(opens(vault))
        assertTrue(opens(vault, "keeper"))
        assertTrue(opens(vault, "ops"))
        assertEquals("graph:lab#operator@role:ops#assignee", seen.last().rule)
        assertEquals("port:lab/wing/Vault/vault", seen.last().target)
        assertFalse(opens(vault, "ops", "visitor"))
        assertEquals("Blocked by port:lab/wing/Vault/vault#blocked@role:visitor#assignee", seen.last().rule)
        assertEquals(listOf("opened", "opened"), effects)
    }

    @Test
    fun aConsumerConnectedAfterTheGuardIsChecked() {
        val graph = lab()
        val vault = VaultNode(graph, effects)
        graph.attach(vault)
        graph.guarded(policy("port:lab/Vault/vault#invoker@role:keeper#assignee"))
        val visitor = Visitor(graph)
        graph.attach(visitor)
        graph.autoWire()
        session.value = person()
        assertFailsWith<UnauthorizedException> { visitor.vault { open() } }
        session.value = person("keeper")
        assertEquals("opened", visitor.vault { open() })
    }

    @Test
    fun aModelWithoutPortsRefusesInsteadOfAllowing() {
        val graph = lab()
        val vault = VaultNode(graph, effects)
        graph.attach(vault)
        graph.guarded(CodeAccessPolicy(FgaDsl.parse(CodeAccess.starter.substringBefore("\ntype node") + "\n").model!!, emptyList()))
        assertFalse(opens(vault, "keeper"))
        assertFalse(seen.single().allowed)
        assertEquals(emptyList(), effects)
    }

    @Test
    fun detachingANodeOrClosingTheGraphReleasesEveryPort() {
        val graph = lab()
        val vault = VaultNode(graph, effects)
        val visitor = Visitor(graph)
        graph.attach(vault)
        graph.attach(visitor)
        graph.autoWire()
        val guard = graph.guarded(policy())
        assertEquals(2, guard.watching)
        graph.detach(visitor)
        assertEquals(1, guard.watching)
        graph.close()
        assertEquals(0, guard.watching)
        assertEquals(0, guard.covering)
    }
}

private interface Vault {
    fun open(): String
    suspend fun seal(): String
}

private class VaultFixture(private val effects: MutableList<String>) : Vault {
    override fun open() = "opened".also { effects += it }
    override suspend fun seal() = "sealed".also { effects += it }
}

private interface Hall {
    fun enter(): String
}

private object HallFixture : Hall {
    override fun enter() = "welcome"
}

private class VaultNode(graph: Graph, effects: MutableList<String>) : BasicNode(graph) {
    override val label = "Vault"
    val vault by providesProtected<Vault>(VaultFixture(effects))
}

private class HallNode(graph: Graph) : BasicNode(graph) {
    override val label = "Hall"
    val hall by provides<Hall>(HallFixture)
}

private class Visitor(graph: Graph) : BasicNode(graph) {
    val vault by consumes<Vault>()
}
