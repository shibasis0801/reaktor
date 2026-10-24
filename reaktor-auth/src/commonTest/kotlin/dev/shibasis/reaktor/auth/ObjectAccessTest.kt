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
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.RoleRef
import dev.shibasis.reaktor.auth.transport.RelationAccess
import dev.shibasis.reaktor.auth.transport.objectAccess
import dev.shibasis.reaktor.auth.transport.setAuthContext
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Callers
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.ServiceSpan
import dev.shibasis.reaktor.service.SpanSink
import dev.shibasis.reaktor.service.TraceContext
import dev.shibasis.reaktor.service.contract.Interceptor
import dev.shibasis.reaktor.service.contract.Spans
import dev.shibasis.reaktor.service.contract.over
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.LocalLink

class ObjectAccessTest {
    private val model = FgaDsl.parse(CodeAccess.starter + "\ntype room\n  relations\n    define member: [user]\n    define viewer: [user, role#assignee] or member\n").model!!

    private fun policy(mode: AccessMode = AccessMode.Shadow, vararg grants: String) =
        CodeAccessPolicy(model, grants.map { RelationTuple.parse(it)!! }, modes = mapOf(FgaStore.EveryObject to mode))

    private fun person(id: String) = AuthContext(principal = PrincipalRef(id, PrincipalKind.USER), appId = "app", audience = "app", method = AuthMethod.ACCESS_TOKEN)

    @Test fun aHandlerAsksAboutOneObjectWithTheFactsItLoaded() = runTest {
        val seen = mutableListOf<AccessDecision>()
        val rooms = Rooms(members = mapOf("lobby" to setOf("ada"))).guarded(RelationAccess({ policy() }, decisions = AccessDecisionSink(seen::add)))
        assertEquals(StatusCode.OK, rooms.room(RoomAsk("lobby").apply { setAuthContext(person("ada")) }).statusCode)
        assertEquals(StatusCode.OK, rooms.room(RoomAsk("lobby").apply { setAuthContext(person("alan")) }).statusCode)
        val (ada, alan) = seen.filter { it.relation == "viewer" }
        assertTrue(ada.allowed)
        assertEquals("room:lobby#member@user:ada", ada.rule)
        assertTrue("room:lobby#member@user:ada" in ada.contextual)
        assertEquals("would deny", alan.verdict)
        assertEquals("Nothing grants viewer on room:lobby", alan.rule)
        assertEquals(listOf("operation:rooms.room", "room:lobby", "operation:rooms.room", "room:lobby"), seen.map { it.target })
    }

    @Test fun whenEnforcedTheHandlerTurnsTheCallerAwayAndTheGrantsStillApply() = runTest {
        val rooms = Rooms(members = mapOf("lobby" to setOf("ada")))
            .guarded(RelationAccess({ policy(AccessMode.Enforce, "room:lobby#viewer@role:host#assignee") }, decisions = AccessDecisionSink { }))
        assertEquals(StatusCode.OK, rooms.room(RoomAsk("lobby").apply { setAuthContext(person("ada")) }).statusCode)
        assertEquals(StatusCode.NOT_FOUND, rooms.room(RoomAsk("lobby").apply { setAuthContext(person("alan")) }).statusCode)
        val host = person("grace").copy(roles = setOf(RoleRef(name = "host")))
        assertEquals(StatusCode.OK, rooms.room(RoomAsk("lobby").apply { setAuthContext(host) }).statusCode)
    }

    @Test fun aRefusalReplacesTheCallDecisionOnTheSpanSoItCanBeSeen() = runTest {
        val spans = mutableListOf<ServiceSpan>()
        val rooms = Rooms(members = mapOf("lobby" to setOf("ada")))
            .guarded(Spans("rooms", SpanSink(spans::add)), RelationAccess({ policy() }, decisions = AccessDecisionSink { }))
        withContext(ServiceCall(TraceContext.root(), null, emptyMap())) {
            rooms.room(RoomAsk("lobby").apply { setAuthContext(person("ada")) })
            rooms.room(RoomAsk("lobby").apply { setAuthContext(person("alan")) })
        }
        val (member, stranger) = spans.map { assertNotNull(AccessDecision.decode(it.access)) }
        assertEquals("operation:rooms.room", member.target)
        assertEquals("room:lobby", stranger.target)
        assertEquals("viewer", stranger.relation)
        assertFalse(stranger.allowed)
    }

    @Test fun aServiceActingForSomeoneAsksAboutThatPerson() = runTest {
        val seen = mutableListOf<AccessDecision>()
        val rooms = Rooms(members = mapOf("lobby" to setOf("ada")), actingFor = "ada")
            .guarded(RelationAccess({ policy() }, decisions = AccessDecisionSink(seen::add)))
        rooms.room(RoomAsk("lobby").apply { setAuthContext(AuthContext(principal = PrincipalRef("digest", PrincipalKind.SERVICE), appId = "app", audience = "app",
            method = AuthMethod.SERVICE_CREDENTIAL)) })
        val viewed = seen.single { it.relation == "viewer" }
        assertEquals("user:ada", viewed.subject)
        assertTrue(viewed.allowed)
        assertNull(Rooms.outside())
    }
}

@Serializable private class RoomAsk(val id: String = "") : Request()
@Serializable private class RoomAnswer(override val statusCode: StatusCode = StatusCode.OK) : Response()

private open class RoomsApi : Service("rooms") {
    override val access = ServiceAccess(Callers.SignedIn)

    open val room by get<RoomAsk, RoomAnswer>("/rooms/{id}")
}

private class Rooms(private val members: Map<String, Set<String>>, private val actingFor: String? = null) : RoomsApi() {
    override val room = super.room.handle { request ->
        val access = assertNotNull(objectAccess())
        val viewer = actingFor?.let { Subject.of(CodeAccess.User, it) } ?: access.caller
        val facts = members[request.id].orEmpty().map { RelationTuple(ObjectRef("room", request.id), "member", Subject.of(CodeAccess.User, it)) }
        if (access.decide("viewer", ObjectRef("room", request.id), facts, viewer).blocks) RoomAnswer(StatusCode.NOT_FOUND) else RoomAnswer()
    }

    fun guarded(vararg interceptors: Interceptor): RoomsApi = RoomsApi() over LocalLink(this).apply { served.intercept(*interceptors) }

    companion object {
        suspend fun outside() = objectAccess()
    }
}
