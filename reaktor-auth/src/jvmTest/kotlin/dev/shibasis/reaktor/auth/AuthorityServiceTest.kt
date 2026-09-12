package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.*
import dev.shibasis.reaktor.auth.kernel.*
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeGraph
import dev.shibasis.reaktor.core.network.StatusCode
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.*

class AuthorityServiceTest {
    @BeforeTest fun setup() = AuthDbFixture.ensure()

    @Test fun resolvesFreshTenantSuperadminAcrossApplicationsForTheSameIdentity() = runBlocking {
        val fx = Fixture()
        val grants = fx.runtime.service.authorityGrants(fx.grants())
        assertEquals(StatusCode.OK, grants.statusCode)
        assertEquals(listOf(fx.target), grants.grants.map { it.target })
        val response = fx.runtime.service.authorityResolve(fx.resolve("data:admin:future-permission"))
        assertEquals(StatusCode.OK, response.statusCode)
        val resolution = assertNotNull(response.resolution)
        assertEquals(fx.targetPrincipal, resolution.context.principal.id)
        assertEquals(fx.identity, resolution.context.identityId)
        assertEquals(fx.target.appId, resolution.context.appId)
        assertEquals(fx.target.tenantId, resolution.context.tenantId)
        assertEquals("PROD", resolution.environment)
        assertTrue(resolution.expiresAtEpochMillis <= System.currentTimeMillis() + 60_000)
        assertNotNull(fx.runtime.jwt.verifier.verifyReaktorToken(resolution.accessToken, listOf(fx.target.appId)).getOrNull())
        assertTrue(fx.runtime.jwt.verifier.verifyReaktorToken(resolution.accessToken, listOf(fx.sourceApp)).isFailure)
        assertEquals(AuthDecision.Allow(resolution.context), LocalAuthorizer.authorize(resolution.context,
            AuthRequirement(permissions = setOf(PermissionRef(name = "some:new:permission")))))
        val audit = AuthDbFixture.auditEvents().last { it.sessionId == fx.session.sessionId }
        assertEquals(fx.sourcePrincipal, audit.actorPrincipalId)
        assertEquals(fx.targetPrincipal, audit.subjectPrincipalId)
        assertFalse(response.toString().contains(resolution.accessToken))
    }

    @Test fun incomingRolesCannotCreateGrantsOrCrossASelectedTenantBoundary() = runBlocking {
        val fx = Fixture()
        val otherTenant = AuthDbFixture.seedTenant(fx.target.appId)
        assertEquals(StatusCode.FORBIDDEN, fx.runtime.service.authorityResolve(fx.resolve().copy(target = fx.target.copy(tenantId = otherTenant))).statusCode)
        assertEquals(StatusCode.FORBIDDEN, fx.runtime.service.authorityResolve(fx.resolve().copy(target = fx.target.copy(appId = fx.sourceApp))).statusCode)
        transaction { PrincipalRoles.update({ PrincipalRoles.principalId eq UUID.fromString(fx.targetPrincipal) }) { it[PrincipalRoles.tenantId] = null } }
        assertEquals(StatusCode.FORBIDDEN, fx.runtime.service.authorityResolve(fx.resolve()).statusCode,
            "An unscoped superadmin assignment must not become authority over every tenant")
    }

    @Test fun sourceLogoutAndCurrentMembershipRevocationTakeEffectBeforeAnotherResolution() = runBlocking {
        val fx = Fixture()
        assertEquals(StatusCode.OK, fx.runtime.service.authorityResolve(fx.resolve()).statusCode)
        transaction { Memberships.update({ Memberships.principalId eq UUID.fromString(fx.targetPrincipal) }) { it[Memberships.status] = MembershipStatus.SUSPENDED } }
        assertEquals(StatusCode.FORBIDDEN, fx.runtime.service.authorityResolve(fx.resolve()).statusCode)
        fx.runtime.sessionLifecycle.revokeByRefreshToken(fx.session.rawRefreshToken)
        assertEquals(StatusCode.UNAUTHORIZED, fx.runtime.service.authorityGrants(fx.grants()).statusCode)
    }

    @Test fun missingExpiredServiceOrWrongAudienceCredentialsCannotResolveAuthority() = runBlocking {
        val fx = Fixture()
        assertEquals(StatusCode.UNAUTHORIZED, fx.runtime.service.authorityGrants(fx.grants().copy(headers = mutableMapOf())).statusCode)
        assertEquals(StatusCode.UNAUTHORIZED, fx.runtime.service.authorityGrants(fx.grants().copy(sourceAppId = fx.target.appId)).statusCode)
        val expired = fx.runtime.jwt.minter.mintAccessToken(fx.sourcePrincipal, fx.sourceApp, emptyList(),
            sessionId = fx.session.sessionId, expirationMs = -1000)
        assertEquals(StatusCode.UNAUTHORIZED, fx.runtime.service.authorityGrants(fx.grants().copy(headers = bearer(expired))).statusCode)
        val service = fx.runtime.jwt.minter.mintServiceToken(fx.sourcePrincipal, fx.sourceApp, listOf("*"))
        assertEquals(StatusCode.UNAUTHORIZED, fx.runtime.service.authorityGrants(fx.grants().copy(headers = bearer(service))).statusCode)
        assertEquals(StatusCode.BAD_REQUEST, fx.runtime.service.authorityResolve(fx.resolve(" ")).statusCode)
    }

    @Test fun unrelatedIdentityAndDisabledTenantNeverAppearInAuthorityInventory() = runBlocking {
        val fx = Fixture()
        val otherUser = AuthDbFixture.seedUser(fx.target.appId)
        AuthDbFixture.grantPermissions(otherUser, fx.target.appId, emptyList(), "superadmin")
        assertEquals(1, fx.runtime.service.authorityGrants(fx.grants()).grants.size)
        transaction { Tenants.update({ Tenants.id eq UUID.fromString(fx.target.tenantId) }) { it[Tenants.status] = MembershipStatus.SUSPENDED } }
        assertTrue(fx.runtime.service.authorityGrants(fx.grants()).grants.isEmpty())
        assertEquals(StatusCode.FORBIDDEN, fx.runtime.service.authorityResolve(fx.resolve()).statusCode)
    }

    private class Fixture {
        val runtime = AuthRuntimeGraph.create(AuthDbFixture.adapter())
        val sourceApp = AuthDbFixture.seedApp()
        val sourcePrincipal = AuthDbFixture.seedUser(sourceApp)
        val identity = requireNotNull(AuthDbFixture.principalById(sourcePrincipal)?.identityId)
        val target = AuthDbFixture.seedApp().let { AuthorityTarget(it, AuthDbFixture.seedTenant(it)) }
        val targetPrincipal = AuthDbFixture.seedUserPrincipalForIdentity(identity, target.appId, target.tenantId)
        val session = runtime.sessionLifecycle.createSession(sourcePrincipal, sourceApp)
        val token = runtime.jwt.minter.mintAccessToken(sourcePrincipal, sourceApp, emptyList(), roles = listOf("superadmin"),
            identityId = identity, sessionId = session.sessionId)
        init {
            AuthDbFixture.grantPermissions(targetPrincipal, target.appId, emptyList(), "superadmin")
            transaction { PrincipalRoles.update({ PrincipalRoles.principalId eq UUID.fromString(targetPrincipal) }) {
                it[PrincipalRoles.tenantId] = Tenants.entityId(UUID.fromString(target.tenantId))
            } }
        }
        fun grants() = AuthorityGrantsRequest(sourceApp, headers = bearer(token))
        fun resolve(permission: String = "data:query") = AuthorityResolveRequest(sourceApp, target, permission, headers = bearer(token))
    }

}

private fun bearer(token: String) = mutableMapOf("Authorization" to "Bearer $token")
