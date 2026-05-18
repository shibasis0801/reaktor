package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.ExchangePatRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.api.TokenRequest
import dev.shibasis.reaktor.auth.graph.AuthSession
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthDefaults
import dev.shibasis.reaktor.auth.kernel.AuthDenyReason
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.Delegation
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.ResourceRef
import dev.shibasis.reaktor.auth.kernel.RoleRef
import dev.shibasis.reaktor.auth.kernel.Scope
import dev.shibasis.reaktor.auth.kernel.anyOf
import dev.shibasis.reaktor.auth.kernel.requires
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AuthKernelIntegrationTest {
    private val userContext = AuthContext(
        principal = PrincipalRef("principal_usr_123", PrincipalKind.USER),
        identityId = "idn_123",
        appId = "app_manna",
        tenantId = "tenant_456",
        contextId = "event_group_789",
        sessionId = "ses_123",
        tokenId = "tok_123",
        issuer = AuthDefaults.ISSUER,
        audience = "manna-api",
        scopes = setOf(Scope("event.read"), Scope("event.write")),
        roles = setOf(RoleRef(name = "owner")),
        permissions = setOf(PermissionRef(name = "event.create")),
        method = AuthMethod.ACCESS_TOKEN,
    )

    @Test
    fun authorizerAllowsAudienceAppTenantScopeAndResourceMatch() {
        val requirement = requires("event.write")
            .audience("manna-api")
            .inApp("app_manna")
            .inTenant("tenant_456")
            .on(
                ResourceRef(
                    type = "event_group",
                    id = "event_group_789",
                    appId = "app_manna",
                    tenantId = "tenant_456",
                    contextId = "event_group_789",
                )
            )

        assertIs<AuthDecision.Allow>(LocalAuthorizer.authorize(userContext, requirement))
    }

    @Test
    fun authorizerDeniesMissingContextAndMismatchedAudienceBeforeAuthorizationChecks() {
        val missingContext = LocalAuthorizer.authorize(null, requires("event.read"))
        assertIs<AuthDecision.Deny>(missingContext)
        assertEquals(401, missingContext.statusCode)
        assertEquals(AuthDenyReason.MISSING_AUTH_CONTEXT, missingContext.reason)

        val invalidAudience = LocalAuthorizer.authorize(
            userContext,
            requires("event.read").audience("bestbuds-api"),
        )
        assertIs<AuthDecision.Deny>(invalidAudience)
        assertEquals(401, invalidAudience.statusCode)
        assertEquals(AuthDenyReason.INVALID_AUDIENCE, invalidAudience.reason)
    }

    @Test
    fun authorizerSupportsAnyOfAndPrincipalKindConstraints() {
        val readOrAdmin = anyOf(
            requires("admin.all"),
            requires("event.read"),
        ).forUsers()

        assertIs<AuthDecision.Allow>(LocalAuthorizer.authorize(userContext, readOrAdmin))

        val serviceOnly = requires("event.read").forServices()
        val denied = LocalAuthorizer.authorize(userContext, serviceOnly)
        assertIs<AuthDecision.Deny>(denied)
        assertEquals(AuthDenyReason.INVALID_PRINCIPAL_KIND, denied.reason)

        val serviceContext = userContext.copy(
            principal = PrincipalRef("principal_svc_worker", PrincipalKind.SERVICE),
            identityId = null,
            sessionId = null,
            method = AuthMethod.SERVICE_CREDENTIAL,
        )
        val compositeDenied = LocalAuthorizer.authorize(serviceContext, readOrAdmin)
        assertIs<AuthDecision.Deny>(compositeDenied)
        assertEquals(AuthDenyReason.INVALID_PRINCIPAL_KIND, compositeDenied.reason)
    }

    @Test
    fun delegatedCallsRequireExplicitDelegationAllowance() {
        val delegatedContext = userContext.copy(
            actor = PrincipalRef("principal_svc_worker", PrincipalKind.SERVICE),
            delegation = Delegation(
                actor = PrincipalRef("principal_svc_worker", PrincipalKind.SERVICE),
                subject = userContext.principal,
                reason = "job_123",
            ),
        )

        val denied = LocalAuthorizer.authorize(delegatedContext, requires("event.write"))
        assertIs<AuthDecision.Deny>(denied)
        assertEquals(AuthDenyReason.DELEGATION_DENIED, denied.reason)

        val allowed = LocalAuthorizer.authorize(
            delegatedContext,
            requires("event.write").allowDelegatedActor(),
        )
        assertIs<AuthDecision.Allow>(allowed)
    }

    @Test
    fun legacyAuthSessionConvertsToKernelAuthContext() {
        val user = User(
            id = "principal_svc_worker",
            name = "worker",
            socialId = null,
            appId = "app_manna",
            provider = null,
            status = UserStatus.ACTIVE,
            accountType = AccountType.SERVICE_ACCOUNT,
            data = JsonObject(emptyMap()),
        )

        val context = AuthSession(
            user = user,
            scopes = listOf("knowledge.ingest"),
            accessToken = "access",
            refreshToken = "refresh",
        ).toAuthContext()

        assertEquals(PrincipalKind.SERVICE, context.principal.kind)
        assertEquals("principal_svc_worker", context.principal.id)
        assertEquals("app_manna", context.appId)
        assertEquals(AuthDefaults.ISSUER, context.issuer)
        assertEquals("app_manna", context.audience)
        assertContains(context.scopes, Scope("knowledge.ingest"))
        assertContains(context.permissions, PermissionRef(name = "knowledge.ingest"))
    }

    @Test
    fun tokenContractsKeepCompatibilityDefaults() {
        val user = User(
            id = "principal_usr_123",
            name = "Ada",
            socialId = "google-sub",
            appId = "app_manna",
            provider = UserProvider.GOOGLE,
            status = UserStatus.ACTIVE,
            data = JsonObject(emptyMap()),
        )

        val login = LoginResponse.Success(
            user = user,
            profile = JsonObject(emptyMap()),
            accessToken = "access-token",
            refreshToken = "refresh-token",
        )

        assertEquals("access-token", login.tokenSet.accessToken)
        assertEquals("refresh-token", login.tokenSet.refreshToken)
        assertEquals("Bearer", login.tokenSet.tokenType)

        val token = TokenRequest()
        assertEquals("pat", token.grantType)
        assertEquals("manna-mcp", token.audience)
        assertEquals(AuthDefaults.ACCESS_TOKEN_TTL_SECONDS, token.ttlSeconds)

        val exchange = ExchangePatRequest()
        assertEquals("manna-mcp", exchange.audience)
        assertEquals(AuthDefaults.ACCESS_TOKEN_TTL_SECONDS, exchange.ttlSeconds)
    }
}
