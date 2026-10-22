package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AuthorityResolveRequest
import dev.shibasis.reaktor.auth.kernel.AuthorityTarget
import dev.shibasis.reaktor.auth.api.MeRequest
import dev.shibasis.reaktor.auth.api.TokenRequest
import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessDecisionSink
import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.fga.FgaStore
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.graph.CodeAccess
import dev.shibasis.reaktor.auth.graph.CodeAccessPolicy
import dev.shibasis.reaktor.auth.runtime.AuthAccess
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeGraph
import dev.shibasis.reaktor.auth.transport.AuthRejectedException
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.ServiceCall
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthServerAccessTest {
    private val recorded = mutableListOf<AccessDecision>()

    @BeforeTest
    fun setup() = AuthDbFixture.ensure()

    @Test
    fun theServerGuardsItsOwnServicesWithTheCallerItVerifies() = runBlocking {
        val runtime = runtime(store())
        val (appId, principalId) = AuthDbFixture.seedUser()
        fun AuthRuntimeGraph.token() = jwt.minter.mintAccessToken(principalId, appId, emptyList())

        val signedIn = MeRequest(headers = bearer(runtime.token()))
        runtime.service.sessionMe(signedIn)
        val seen = recorded.single()
        assertEquals("user:$principalId", seen.subject)
        assertEquals("operation:reaktor.auth.sessionMe", seen.target)
        assertTrue(seen.allowed)
        assertEquals("service:reaktor.auth#caller@role:authenticated#assignee", seen.rule)
        assertEquals(seen.encodeForSpan(), signedIn.attributes[ServiceCall.AccessAttribute])

        assertEquals(StatusCode.UNAUTHORIZED, runtime.service.sessionMe(MeRequest()).statusCode)
        assertEquals("user:anonymous", recorded.last().subject)
        assertFalse(recorded.last().allowed)
        assertEquals(AccessMode.Shadow, recorded.last().mode)

        val enforced = runtime(store(enforce = true))
        assertFailsWith<AuthRejectedException> { enforced.service.sessionMe(MeRequest()) }
        assertEquals(StatusCode.OK, enforced.service.sessionMe(MeRequest(headers = bearer(enforced.token()))).statusCode)
    }

    @Test
    fun everyTokenGrantAsksTheModelAboutTheCallerItIdentified() = runBlocking {
        val (appId, _) = AuthDbFixture.seedUser()
        val clientId = "svc-${UUID.randomUUID()}"
        val secret = "secret-${UUID.randomUUID()}"
        val account = AuthDbFixture.seedServiceAccount(clientId = clientId, appId = appId, authMethod = "SECRET", secretHash = sha256(secret),
            scopes = "delegate:read", allowedAudiences = """["manna-mcp"]""")
        fun grant() = TokenRequest(grantType = "client_credentials", clientId = clientId, clientSecret = secret, audience = "manna-mcp",
            scopes = listOf("delegate:read"), environment = Environment.STAGE)
        val request = grant()
        assertEquals(StatusCode.OK, runtime(store()).service.token(request).statusCode)
        val identified = recorded.last()
        assertEquals("service_account:$account", identified.subject)
        assertEquals("operation:reaktor.auth.token", identified.target)
        assertTrue(identified.allowed)
        assertTrue("permission:delegate:read#holder@service_account:$account" in identified.contextual)
        assertEquals("operation:reaktor.auth.token#invoker@role:everyone#assignee", identified.rule)
        assertEquals(identified.encodeForSpan(), request.attributes[ServiceCall.AccessAttribute])
        assertEquals(identified.encodeForSpan(), audited { it.eventType == AuthAuditEventType.TOKEN_MINT && it.subjectPrincipalId == account })

        val blocked = runtime(store("operation:reaktor.auth.token#blocked@service_account:$account", enforce = true))
        assertEquals(StatusCode.FORBIDDEN, blocked.service.token(grant()).statusCode)
        assertEquals("Blocked by operation:reaktor.auth.token#blocked@service_account:$account", recorded.last().rule)
        assertTrue(AuthDbFixture.auditEvents().any { it.subjectPrincipalId == account && it.reason == "access_model_refused" })
    }

    @Test
    fun theSixtySecondGrantAsksTheModelWhetherThePrincipalHoldsThePermission() = runBlocking {
        val shadow = Tenancy(runtime(store()))
        assertEquals(StatusCode.OK, shadow.resolve("data:query").statusCode)
        val held = recorded.last()
        assertEquals(CodeAccess.Holder, held.relation)
        assertEquals("permission:data:query", held.target)
        assertTrue(held.allowed)
        assertEquals(held.encodeForSpan(), audited { it.reason == "current_tenant_grant" && it.subjectPrincipalId == shadow.targetPrincipal })

        assertEquals(StatusCode.FORBIDDEN, shadow.resolve("deploy:run").statusCode)
        assertFalse(recorded.last().allowed)
        assertEquals("Nothing grants holder on permission:deploy:run", recorded.last().rule)

        val modelled = Tenancy(runtime(store("permission:deploy:run#holder@role:analyst#assignee", enforce = true)))
        val response = modelled.resolve("deploy:run")
        assertEquals(StatusCode.OK, response.statusCode)
        assertTrue("deploy:run" in assertNotNull(response.resolution).context.permissions.map { it.name })
        assertEquals("permission:deploy:run#holder@role:analyst#assignee", recorded.last().rule)
        assertTrue(AuthDbFixture.auditEvents().any { it.subjectPrincipalId == modelled.targetPrincipal && it.reason == "access_model_grant" })
    }

    private fun runtime(store: FgaStore): AuthRuntimeGraph {
        val policy = CodeAccessPolicy.load(CodeAccess.starter, store.encode()).getOrThrow()
        return AuthRuntimeGraph.create(AuthDbFixture.adapter(), AuthRuntimeConfig(access = AuthAccess({ policy }, AccessDecisionSink { recorded += it })))
    }

    private fun store(vararg tuples: String, enforce: Boolean = false) = FgaStore("server", "server.fga", tuples.map { requireNotNull(RelationTuple.parse(it)).key },
        modes = mapOf(FgaStore.EveryObject to if (enforce) AccessMode.Enforce else AccessMode.Shadow))

    private fun audited(event: (AuthAuditEvent) -> Boolean): String? =
        AuthDbFixture.auditEvents().last(event).data.jsonObject["access"]?.toString()

    private class Tenancy(val runtime: AuthRuntimeGraph) {
        val sourceApp = AuthDbFixture.seedApp()
        val sourcePrincipal = AuthDbFixture.seedUser(sourceApp)
        val identity = requireNotNull(AuthDbFixture.principalById(sourcePrincipal)?.identityId)
        val target = AuthDbFixture.seedApp().let { AuthorityTarget(it, AuthDbFixture.seedTenant(it)) }
        val targetPrincipal = AuthDbFixture.seedUserPrincipalForIdentity(identity, target.appId, target.tenantId)
        val session = runtime.sessionLifecycle.createSession(sourcePrincipal, sourceApp)
        val token = runtime.jwt.minter.mintAccessToken(sourcePrincipal, sourceApp, emptyList(), identityId = identity, sessionId = session.sessionId)

        init {
            AuthDbFixture.grantPermissions(targetPrincipal, target.appId, listOf("data:query"), "analyst")
            transaction { PrincipalRoles.update({ PrincipalRoles.principalId eq UUID.fromString(targetPrincipal) }) {
                it[PrincipalRoles.tenantId] = Tenants.entityId(UUID.fromString(target.tenantId))
            } }
        }

        suspend fun resolve(permission: String) = runtime.service.authorityResolve(AuthorityResolveRequest(sourceApp, target, permission, headers = bearer(token)))
    }
}

private fun bearer(token: String) = mutableMapOf("Authorization" to "Bearer $token")

private fun sha256(value: String): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
