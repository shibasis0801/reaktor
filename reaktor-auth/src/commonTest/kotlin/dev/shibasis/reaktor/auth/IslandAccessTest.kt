package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.fga.FgaDsl
import dev.shibasis.reaktor.auth.fga.ObjectRef
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.fga.Subject
import dev.shibasis.reaktor.auth.graph.CodeAccess
import dev.shibasis.reaktor.auth.graph.CodeAccessPolicy
import dev.shibasis.reaktor.auth.graph.at
import dev.shibasis.reaktor.auth.graph.island
import dev.shibasis.reaktor.auth.graph.islands
import dev.shibasis.reaktor.graph.remote
import dev.shibasis.reaktor.graph.serve
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.IslandShape
import dev.shibasis.reaktor.graph.core.shape
import dev.shibasis.reaktor.service.Callers
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.contract.Service
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IslandAccessTest {
    private val model = FgaDsl.parse(CodeAccess.starter).model!!

    private fun shape(island: String, vararg served: Service, objects: List<IslandShape> = emptyList()): IslandShape {
        val graph = Graph(dependencyAdapter = AuthGraphDependencyFixture(), label = island)
        served.forEach { graph.serve(it) }
        graph.remote(Courier())
        return IslandShape(island, "test", graph.shape(), objects)
    }

    private fun grant(vararg tuples: String) = tuples.map { RelationTuple.parse(it)!! }

    private fun CodeAccessPolicy.lets(user: String, contract: String, operation: String) =
        check(Subject.of(CodeAccess.User, user), emptyList(), contract, operation).allowed

    @Test fun anIslandIsAGraphUnderItsAppHoldingOnlyTheContractsItServes() {
        val code = CodeAccess.island("BestBuds", "social", listOf(Postbox()))
        assertEquals(listOf("graph:bestbuds", "graph:bestbuds/social"), code.graphs.map { it.ref.toString() })
        assertEquals(listOf(
            "graph:bestbuds/social#parent@graph:bestbuds", "service:postbox#graph@graph:bestbuds/social",
            "operation:postbox.collect#service@service:postbox", "operation:postbox.post#service@service:postbox",
            "operation:postbox.collect#invoker@permission:worker:server#holder", "operation:postbox.post#invoker@role:authenticated#assignee",
        ), code.tuples.map(RelationTuple::toString))
    }

    @Test fun theExportedShapeGivesTheSameTuplesAsTheServedContracts() {
        val served = CodeAccess.island("BestBuds", "social", listOf(Postbox(), Kiosk()))
        val exported = CodeAccess.island("BestBuds", shape("social", PostboxServer(), KioskServer()))
        assertEquals(served.tuples.toSet(), exported.tuples.toSet())
        assertEquals(listOf("service:kiosk", "service:postbox"), exported.services.map { it.ref.toString() })
        assertEquals(CodeAccessPolicy(model, emptyList(), served).digest, CodeAccessPolicy(model, emptyList(), exported).digest)
    }

    @Test fun aGrantOnTheAppOrTheIslandReachesItsOperationsAndAnotherIslandsDoesNot() {
        val code = CodeAccess.island("BestBuds", "social", listOf(Postbox()))
        val stored = grant("graph:bestbuds#operator@user:ada", "graph:bestbuds/social#owner@user:bo", "graph:bestbuds/messaging#operator@user:cy")
        val policy = CodeAccessPolicy(model, stored, code)
        assertTrue(policy.lets("ada", "postbox", "collect"))
        assertEquals("graph:bestbuds#operator@user:ada", policy.check(Subject.of(CodeAccess.User, "ada"), emptyList(), "postbox", "collect").rule)
        assertTrue(policy.lets("bo", "postbox", "collect"))
        assertFalse(policy.lets("cy", "postbox", "collect"))
        assertFalse(policy.lets("dee", "postbox", "post"))
        assertFalse(CodeAccessPolicy(model, stored).lets("ada", "postbox", "collect"))
    }

    @Test fun theSameContractOnTwoIslandsKeepsEachIslandsInheritance() {
        val all = CodeAccess.islands("BestBuds", listOf(shape("social", PostboxServer()), shape("messaging", PostboxServer())))
        assertEquals(listOf(ObjectRef("graph", "bestbuds/social"), ObjectRef("graph", "bestbuds/messaging")), all.service(CodeAccess.service("postbox"))?.graphs)
        val stored = grant("graph:bestbuds/messaging#operator@user:cy")
        assertTrue(CodeAccessPolicy(model, stored, CodeAccess.island("BestBuds", shape("messaging", PostboxServer()))).lets("cy", "postbox", "collect"))
        assertFalse(CodeAccessPolicy(model, stored, CodeAccess.island("BestBuds", shape("social", PostboxServer()))).lets("cy", "postbox", "collect"))
    }

    @Test fun objectIslandsNestUnderTheIslandThatHostsThem() {
        val chat = shape("chat", objects = listOf(shape("ChatRoom", KioskServer())))
        val all = CodeAccess.islands("BestBuds", listOf(chat))
        assertEquals(listOf("graph:bestbuds", "graph:bestbuds/chat", "graph:bestbuds/chat/chatroom"), all.graphs.map { it.ref.toString() })
        assertEquals(listOf(ObjectRef("graph", "bestbuds/chat/chatroom")), all.service(CodeAccess.service("kiosk"))?.graphs)
        assertEquals(emptyList(), CodeAccess.island("BestBuds", chat).services)
    }

    @Test fun anObjectHoldsTheStructureItsHostsExportedShapeGivesIt() {
        val chat = shape("chat", objects = listOf(shape("ChatRoom", KioskServer())))
        val runtime = CodeAccess.island("BestBuds", listOf("chat", "ChatRoom"), listOf(Kiosk()))
        val exported = CodeAccess.islands("BestBuds", listOf(chat)).at(ObjectRef("graph", "bestbuds/chat/chatroom"))
        assertEquals(exported.tuples.toSet(), runtime.tuples.toSet())
        assertEquals(CodeAccessPolicy(model, emptyList(), exported).digest, CodeAccessPolicy(model, emptyList(), runtime).digest)
        val stored = grant("graph:bestbuds/chat#operator@user:cy", "graph:bestbuds/social#operator@user:dee")
        assertTrue(CodeAccessPolicy(model, stored, runtime).lets("cy", "kiosk", "browse"))
        assertFalse(CodeAccessPolicy(model, stored, runtime).lets("dee", "kiosk", "browse"))
    }
}

@Serializable private class PostboxRequest : Request()
@Serializable private class PostboxResponse : Response()

private open class Postbox : Service("postbox") {
    override val access = ServiceAccess(Callers.SignedIn, ::collect to Callers.Holding("worker:server"))
    open val post by post<PostboxRequest, PostboxResponse>("/postbox/post")
    open val collect by post<PostboxRequest, PostboxResponse>("/postbox/collect")
}

private class PostboxServer : Postbox() {
    override val post = super.post.handle { PostboxResponse() }
    override val collect = super.collect.handle { PostboxResponse() }
}

private open class Kiosk : Service("kiosk") {
    open val browse by get<PostboxRequest, PostboxResponse>("/kiosk")
}

private class KioskServer : Kiosk() {
    override val browse = super.browse.handle { PostboxResponse() }
}

private class Courier : Service("courier") {
    val send by post<PostboxRequest, PostboxResponse>("/courier")
}
