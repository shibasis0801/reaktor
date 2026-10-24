package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessDecisionSink
import dev.shibasis.reaktor.auth.fga.AccessDecisions
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
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.RoleRef
import dev.shibasis.reaktor.auth.transport.AuthRejectedException
import dev.shibasis.reaktor.auth.transport.AuthRequirements
import dev.shibasis.reaktor.auth.transport.RelationAccess
import dev.shibasis.reaktor.auth.transport.setAuthContext
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.ServiceSpan
import dev.shibasis.reaktor.service.SpanSink
import dev.shibasis.reaktor.service.TraceContext
import dev.shibasis.reaktor.service.contract.Interceptor
import dev.shibasis.reaktor.service.contract.Spans
import dev.shibasis.reaktor.service.contract.over
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.*
import dev.shibasis.reaktor.service.contract.Service
import dev.shibasis.reaktor.service.contract.LocalLink

class AccessDecisionTest {
    private val model = FgaDsl.parse(CodeAccess.starter).model!!
    private val grants = listOf(
        "service:rooms#caller@role:authenticated#assignee",
        "operation:rooms.open#invoker@role:everyone#assignee",
        "operation:rooms.close#blocked@role:guest#assignee",
        "service:jobs#caller@permission:worker:server#holder",
    ).map { RelationTuple.parse(it)!! }

    private fun policy(vararg modes: Pair<String, AccessMode>) = CodeAccessPolicy(model, grants, modes = modes.toMap())

    private fun caller(vararg roles: String, kind: PrincipalKind = PrincipalKind.USER, tenant: String? = null, permissions: List<String> = emptyList()) = AuthContext(
        principal = PrincipalRef("ada", kind), appId = "app", tenantId = tenant, audience = "app", method = AuthMethod.ACCESS_TOKEN,
        roles = roles.map { RoleRef(name = it) }.toSet(), permissions = permissions.map { PermissionRef(name = it) }.toSet())

    @Test fun everyCallerIsEveryoneAndEverySignedInCallerIsAuthenticated() {
        val access = policy()
        assertTrue(access.check(null, "rooms", "open").allowed)
        assertEquals("user:anonymous", access.check(null, "rooms", "open").subject)
        assertFalse(access.check(null, "rooms", "close").allowed)
        assertEquals("Nothing grants can_invoke on operation:rooms.close to callers who are not signed in", access.check(null, "rooms", "close").rule)
        assertTrue(access.check(caller(), "rooms", "close").allowed)
        assertEquals("service:rooms#caller@role:authenticated#assignee", access.check(caller(), "rooms", "close").rule)
        assertTrue(access.check(caller(kind = PrincipalKind.SERVICE), "rooms", "close").allowed)
        assertFalse(access.check(caller("guest"), "rooms", "close").allowed)
        assertEquals("Blocked by operation:rooms.close#blocked@role:guest#assignee", access.check(caller("guest"), "rooms", "close").rule)
        assertFalse(access.check(caller(kind = PrincipalKind.SERVICE), "jobs", "run").allowed)
        assertTrue(access.check(caller(kind = PrincipalKind.SERVICE, permissions = listOf("worker:server")), "jobs", "run").allowed)
        assertEquals(listOf(CodeAccess.role("everyone"), CodeAccess.role("authenticated")), CodeAccess.builtInRoles)
    }

    @Test fun theCallersTenantArrivesAsAMembershipTheModelCanUse() {
        val product = FgaDsl.parse(CodeAccess.starter + "\ntype tenant\n  relations\n    define member: [user]\n\ntype room\n  relations\n    define tenant: [tenant]\n    define viewer: member from tenant\n").model!!
        val fga = CodeAccessPolicy(product, listOf(RelationTuple.parse("room:lobby#tenant@tenant:acme")!!)).fga
        fun views(context: AuthContext) = fga.check(CodeAccess.subject(context), "viewer", ObjectRef("room", "lobby"), CodeAccess.contextual(context)).allowed
        assertTrue(views(caller(tenant = "acme")))
        assertFalse(views(caller(tenant = "globex")))
        assertFalse(views(caller()))
    }

    @Test fun eachDecisionNamesTheRuleTheFactsAndTheModelItCameFrom() {
        val access = policy()
        val decision = access.check(caller(), "rooms", "close", atMillis = 42)
        assertEquals(listOf("service:rooms#caller@role:authenticated#assignee", "role:authenticated#assignee@user:ada"), decision.facts)
        assertEquals(access.digest, decision.model)
        assertEquals(16, decision.model.length)
        assertNotEquals(access.digest, CodeAccessPolicy(model, grants.drop(1)).digest)
        assertEquals(decision, AccessDecision.decode(decision.encode()))
        assertEquals("allowed", decision.verdict)
        assertEquals("would deny", access.check(null, "rooms", "close").copy(mode = AccessMode.Shadow).verdict)
        assertEquals("denied", access.check(null, "rooms", "close").verdict)
    }

    @Test fun shadowRecordsWithoutBlockingAndEnforceBlocksWithTheRightStatus() = runTest {
        val seen = mutableListOf<AccessDecision>()
        var access = policy(FgaStore.EveryObject to AccessMode.Shadow)
        val rooms = rooms(RelationAccess({ access }, decisions = AccessDecisionSink(seen::add)))
        suspend fun serve(context: AuthContext?) = runCatching { rooms.close(RoomRequest().apply { setAuthContext(context) }) }
        assertTrue(serve(null).isSuccess)
        assertEquals("would deny", seen.single().verdict)
        assertEquals("operation:rooms.close", seen.single().target)
        access = policy(FgaStore.EveryObject to AccessMode.Shadow, "service:rooms" to AccessMode.Enforce)
        assertEquals(StatusCode.UNAUTHORIZED, (serve(null).exceptionOrNull() as AuthRejectedException).statusCode)
        assertEquals(StatusCode.FORBIDDEN, (serve(caller("guest")).exceptionOrNull() as AuthRejectedException).statusCode)
        assertTrue(serve(caller()).isSuccess)
        assertEquals(listOf("would deny", "denied", "denied", "allowed"), seen.map { it.verdict })
    }

    @Test fun aModelThatCannotAnswerFailsClosedAndNoModelLeavesCallsAlone() = runTest {
        val seen = mutableListOf<AccessDecision>()
        val broken = rooms(RelationAccess({ error("model missing") }, decisions = AccessDecisionSink(seen::add)))
        assertEquals(StatusCode.UNAUTHORIZED, (runCatching { broken.open(RoomRequest()) }.exceptionOrNull() as AuthRejectedException).statusCode)
        assertEquals(StatusCode.FORBIDDEN, (runCatching { broken.open(RoomRequest().apply { setAuthContext(caller()) }) }.exceptionOrNull() as AuthRejectedException).statusCode)
        assertEquals(listOf("The access model could not answer: model missing"), seen.map { it.rule }.distinct())
        assertEquals(listOf(AccessMode.Enforce), seen.map { it.mode }.distinct())
        val unguarded = rooms(RelationAccess({ null }, decisions = AccessDecisionSink(seen::add)))
        assertTrue(runCatching { unguarded.open(RoomRequest()) }.isSuccess)
        assertEquals(2, seen.size)
    }

    @Test fun policiesLoadFromTheFilesHangarWrites() {
        val store = FgaStore(tuples = grants.map { it.key }, modes = mapOf(FgaStore.EveryObject to AccessMode.Shadow, "service:rooms" to AccessMode.Enforce)).encode()
        val access = CodeAccessPolicy.load(CodeAccess.starter, store).getOrThrow()
        assertEquals(AccessMode.Enforce, access.mode("rooms"))
        assertEquals(AccessMode.Shadow, access.mode("jobs"))
        assertEquals(AccessMode.Shadow, CodeAccessPolicy.load(CodeAccess.starter, null).getOrThrow().mode("rooms"))
        assertTrue(CodeAccessPolicy.load("model\n  schema 1.1\n\ntype room\n  relations\n    define viewer: [nobody]\n", null).isFailure)
        assertTrue(FgaStore.decode(store).encode().contains("\"service:rooms\": \"enforce\""))
        assertEquals(AccessMode.Enforce, FgaStore.decode(store).mode(ObjectRef("service", "rooms")))
        assertNull(FgaStore().withMode("service:rooms", AccessMode.Enforce).withMode("service:rooms", null).mode(ObjectRef("service", "rooms")))
    }

    @Test fun roleRequirementsRecordTheSameDecision() = runTest {
        val seen = mutableListOf<AccessDecision>()
        val rooms = rooms(AuthRequirements({ AuthRequirement(roles = setOf(RoleRef(name = "host"))) }, AccessDecisionSink(seen::add)))
        assertTrue(runCatching { rooms.open(RoomRequest().apply { setAuthContext(caller("host")) }) }.isSuccess)
        assertEquals(StatusCode.FORBIDDEN, (runCatching { rooms.open(RoomRequest().apply { setAuthContext(caller()) }) }.exceptionOrNull() as AuthRejectedException).statusCode)
        assertEquals(listOf(true, false), seen.map { it.allowed })
        assertEquals("operation:rooms.open", seen.first().target)
        assertEquals(AuthRequirements.Requirement, seen.first().relation)
    }

    @Test fun theServerSpanCarriesTheDecisionWithTheFactsTheCallBrought() = runTest {
        val spans = mutableListOf<ServiceSpan>()
        val rooms = rooms(Spans("rooms", SpanSink(spans::add)), RelationAccess({ policy(FgaStore.EveryObject to AccessMode.Shadow) }, decisions = AccessDecisionSink { }))
        withContext(ServiceCall(TraceContext.root(), null, emptyMap())) {
            rooms.close(RoomRequest().apply { setAuthContext(caller("member", tenant = "acme")) })
            rooms.close(RoomRequest())
        }
        val (member, anonymous) = spans.map { assertNotNull(AccessDecision.decode(it.access)) }
        assertEquals("user:ada", member.subject)
        assertTrue(member.allowed)
        assertEquals(listOf("role:everyone#assignee@user:ada", "role:authenticated#assignee@user:ada", "tenant:acme#member@user:ada", "role:member#assignee@user:ada"), member.contextual)
        assertNull(member.trace)
        assertEquals(0, member.atMillis)
        assertEquals("would deny", anonymous.verdict)
        assertEquals(listOf("role:everyone#assignee@user:anonymous"), anonymous.contextual)
        assertEquals(listOf("operation:rooms.close"), spans.map { AccessDecision.decode(it.access)!!.target }.distinct())
    }

    @Test fun theHubKeepsTheLatestDecisionsAndTellsListeners() {
        AccessDecisions.clear()
        val heard = mutableListOf<String>()
        val stop = AccessDecisions.listen { heard += it.target }
        repeat(AccessDecisions.Kept + 3) { AccessDecisions.record(policy().check(null, "rooms", "open", atMillis = it.toLong())) }
        stop()
        AccessDecisions.record(policy().check(null, "rooms", "close"))
        assertEquals(AccessDecisions.Kept + 3, heard.size)
        assertEquals(AccessDecisions.Kept, AccessDecisions.latest.size)
        assertEquals("operation:rooms.close", AccessDecisions.latest.last().target)
        AccessDecisions.clear()
    }

    @Test fun oneTokenBecomesOneContextOnEveryPlatform() {
        val claims = JsonObject(mapOf(
            "sub" to JsonPrimitive("u1"), "aud" to JsonArray(listOf(JsonPrimitive("bestbuds"))), "tid" to JsonPrimitive("t1"), "ctx" to JsonPrimitive("c1"),
            "roles" to JsonArray(listOf(JsonPrimitive("member"))), "permissions" to JsonArray(listOf(JsonPrimitive("circles:read"))),
            "scp" to JsonPrimitive("worker:server read"), "principal_type" to JsonPrimitive("agent"), "credential_type" to JsonPrimitive("pat"),
            "act" to JsonObject(mapOf("sub" to JsonPrimitive("svc"))), "sid" to JsonPrimitive("s1"),
        ))
        val context = TokenClaims.context(claims)!!
        assertEquals(PrincipalRef("u1", PrincipalKind.AGENT), context.principal)
        assertEquals("bestbuds", context.appId)
        assertEquals("t1", context.tenantId)
        assertEquals("c1", context.contextId)
        assertEquals(setOf("member"), context.roles.mapNotNull { it.name }.toSet())
        assertEquals(setOf("circles:read", "worker:server", "read"), context.permissions.map { it.name }.toSet())
        assertEquals(setOf("worker:server", "read"), context.scopes.map { it.name }.toSet())
        assertEquals(AuthMethod.PERSONAL_ACCESS_TOKEN, context.method)
        assertEquals("svc", context.delegation?.actor?.id)
        assertNull(TokenClaims.context(JsonObject(mapOf("aud" to JsonPrimitive("x")))))
        assertEquals("fallback", TokenClaims.context(JsonObject(mapOf("sub" to JsonPrimitive("u"))), defaultApp = "fallback")?.appId)
        assertTrue(RelationTuple(CodeAccess.role("member"), CodeAccess.Assignee, Subject.of(CodeAccess.Agent, "u1")) in CodeAccess.contextual(context))
    }
}

@Serializable private class RoomRequest : Request()
@Serializable private class RoomResponse : Response()

private open class RoomDoors : Service("rooms") {
    open val open by get<RoomRequest, RoomResponse>("/rooms/open")
    open val close by post<RoomRequest, RoomResponse>("/rooms/close")
}

private fun rooms(vararg interceptors: Interceptor): RoomDoors = RoomDoors() over LocalLink(object : RoomDoors() {
    override val open = super.open.handle { RoomResponse() }
    override val close = super.close.handle { RoomResponse() }
}).also { it.served.intercept(*interceptors) }
