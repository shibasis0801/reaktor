package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AnonymousAuthRequest
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.auth.db.AuthRepository
import dev.shibasis.reaktor.auth.jwt.AuthenticatedUser
import dev.shibasis.reaktor.auth.jwt.JwtMinter
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.jwt.SigningKeys
import dev.shibasis.reaktor.auth.jwt.toAuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.services.LoginInteractor
import dev.shibasis.reaktor.auth.services.SessionRefreshService
import dev.shibasis.reaktor.service.Environment
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end login integration across app/auth repositories, principal resolution, RBAC permission lookup,
 * real session rows, and ES256 access-token minting. The external IdP verifier is stubbed at the
 * JwtVerifier boundary so the test stays local; crypto/JWKS verification is covered by JwtRoundTripTest
 * and ServiceAccountServiceTest.
 */
class LoginInteractorIntegrationTest {
    @BeforeTest
    fun setup() = AuthDbFixture.ensure()

    @Test
    fun anonymousLoginCreatesPrincipalSessionAndAnonymousJwt() = runBlocking {
        val appId = AuthDbFixture.seedApp()
        val signingKeys = SigningKeys("", "")

        val response = loginInteractor(signingKeys).anonymous(
            AnonymousAuthRequest(appId = appId, environment = Environment.STAGE)
        )

        assertTrue(response is LoginResponse.Success)
        assertEquals(AuthMethod.ANONYMOUS, response.context.method)
        assertEquals(PrincipalKind.USER, response.context.principalKind)
        assertNull(response.context.identityId)
        assertEquals(listOf("guest"), response.context.roles)
        assertTrue(response.tokenSet.refreshToken?.startsWith("rkr_") == true)
        assertNotNull(response.tokenSet.sessionId)

        val principal = AuthDbFixture.principalById(response.context.principalId)
        assertNotNull(principal)
        assertEquals(PrincipalKind.USER, principal.kind)
        assertNull(principal.identityId)

        val session = AuthDbFixture.sessionById(response.tokenSet.sessionId)
        assertNotNull(session)
        assertEquals(response.context.principalId, session.principalId)
        assertEquals(appId, session.appId)

        val claims = JwtVerifier(emptyList(), signingKeys)
            .verifyReaktorToken(response.tokenSet.accessToken, listOf(appId))
            .getOrNull()
        assertNotNull(claims)
        assertEquals(response.context.principalId, claims.subject)
        assertEquals("anonymous", claims.getStringClaim("credential_type"))
        assertEquals("user", claims.getStringClaim("principal_type"))
        assertNull(claims.getStringClaim("identity_id"))
        assertEquals(listOf("guest"), claims.getStringListClaim("roles"))
        assertEquals(AuthMethod.ANONYMOUS, claims.toAuthContext().method)
    }

    @Test
    fun firstSocialLoginCreatesPrincipalSessionAndJwt() = runBlocking {
        val appId = AuthDbFixture.seedApp()
        val subject = "google-${UUID.randomUUID()}"
        val signingKeys = SigningKeys("", "")
        val login = loginInteractor(signingKeys)

        val response = login.login(
            LoginRequest(
                idToken = subject,
                appId = appId,
                provider = UserProvider.GOOGLE,
                givenName = "Ada",
                familyName = "Lovelace",
                environment = Environment.STAGE,
            )
        )

        assertTrue(response is LoginResponse.Success)
        assertEquals(appId, response.context.appId)
        assertEquals("Ada", response.profile.jsonObject["givenName"]?.jsonPrimitive?.contentOrNull)
        assertTrue(response.tokenSet.refreshToken?.startsWith("rkr_") == true)
        assertNotNull(response.tokenSet.sessionId)

        val providerAccounts = AuthDbFixture.providerAccountsByProviderAndSubject(UserProvider.GOOGLE, subject)
        assertEquals(1, providerAccounts.size, "first login persists exactly one provider account")

        val session = AuthDbFixture.sessionById(response.tokenSet.sessionId)
        assertNotNull(session)
        assertEquals(response.context.principalId, session.principalId)
        assertEquals(appId, session.appId)
        assertEquals(1, AuthDbFixture.refreshTokensForSession(session.id).size)

        val claims = JwtVerifier(emptyList(), signingKeys)
            .verifyReaktorToken(response.tokenSet.accessToken, listOf(appId))
            .getOrNull()
        assertNotNull(claims)
        assertEquals(response.context.principalId, claims.subject)
        assertEquals(session.id, claims.getStringClaim("sid"))
        assertEquals(response.context.identityId, claims.getStringClaim("identity_id"))
        assertEquals("user", claims.getStringClaim("principal_type"))
    }

    @Test
    fun existingUserLoginReusesUserAndCarriesPermissionsIntoAccessToken() = runBlocking {
        val appId = AuthDbFixture.seedApp()
        val subject = "google-${UUID.randomUUID()}"
        val principalId = AuthDbFixture.seedUser(appId = appId, socialId = subject)
        val permissions = AuthDbFixture.grantPermissions(
            principalId = principalId,
            appId = appId,
            permissionNames = listOf("auth:pat:mint", "mcp:read"),
        )
        val signingKeys = SigningKeys("", "")
        val login = loginInteractor(signingKeys)

        val response = login.login(
            LoginRequest(
                idToken = subject,
                appId = appId,
                provider = UserProvider.GOOGLE,
                environment = Environment.STAGE,
            )
        )

        assertTrue(response is LoginResponse.Success)
        assertEquals(principalId, response.context.principalId)
        assertEquals(1, AuthDbFixture.providerAccountsByProviderAndSubject(UserProvider.GOOGLE, subject).size)

        val claims = JwtVerifier(emptyList(), signingKeys)
            .verifyReaktorToken(response.tokenSet.accessToken, listOf(appId))
            .getOrNull()
        assertNotNull(claims)
        assertEquals(permissions.toSet(), claims.getStringListClaim("scp").toSet())
        assertEquals(permissions.toSet(), response.tokenSet.scopes.toSet())
    }

    @Test
    fun firstVerifiedProviderLoginClaimsPreProvisionedIdentityByEmail() = runBlocking {
        val appId = AuthDbFixture.seedApp()
        val subject = "google-${UUID.randomUUID()}"
        val principalId = AuthDbFixture.seedInvitedUser(
            appId = appId,
            email = "$subject@example.test",
        )
        val permissions = AuthDbFixture.grantPermissions(
            principalId = principalId,
            appId = appId,
            permissionNames = listOf("companyos:access", "companyos:finance:view"),
            roleName = "companyos:ca",
        )
        val signingKeys = SigningKeys("", "")

        val response = loginInteractor(signingKeys).login(
            LoginRequest(
                idToken = subject,
                appId = appId,
                provider = UserProvider.GOOGLE,
                environment = Environment.STAGE,
            )
        )

        assertTrue(response is LoginResponse.Success)
        assertEquals(principalId, response.context.principalId)
        assertEquals(permissions.toSet(), response.tokenSet.scopes.toSet())

        val providerAccounts = AuthDbFixture.providerAccountsByProviderAndSubject(UserProvider.GOOGLE, subject)
        assertEquals(1, providerAccounts.size)
        assertEquals(response.context.identityId, providerAccounts.single().identityId)
    }

    @Test
    fun existingProviderLoginPrefersPrincipalWithRequestedTenantMembership() = runBlocking {
        val appId = AuthDbFixture.seedApp()
        val otherAppId = AuthDbFixture.seedApp()
        val tenantId = AuthDbFixture.seedTenant(appId)
        val subject = "google-${UUID.randomUUID()}"
        val email = "$subject@example.test"
        val identityId = AuthDbFixture.seedIdentity(email)
        val legacyPrincipalId = AuthDbFixture.seedUserPrincipalForIdentity(
            identityId = identityId,
            appId = otherAppId,
        )
        val tenantPrincipalId = AuthDbFixture.seedUserPrincipalForIdentity(
            identityId = identityId,
            appId = appId,
            tenantId = tenantId,
        )
        AuthDbFixture.seedProviderAccount(
            identityId = identityId,
            subject = subject,
            email = email,
        )
        val permissions = AuthDbFixture.grantPermissions(
            principalId = tenantPrincipalId,
            appId = appId,
            permissionNames = listOf("companyos:access", "companyos:finance:view"),
            roleName = "companyos:superadmin",
        )
        val signingKeys = SigningKeys("", "")

        val response = loginInteractor(signingKeys).login(
            LoginRequest(
                idToken = subject,
                appId = appId,
                provider = UserProvider.GOOGLE,
                tenantHint = tenantId,
                environment = Environment.STAGE,
            )
        )

        assertTrue(response is LoginResponse.Success)
        assertTrue(legacyPrincipalId != tenantPrincipalId)
        assertEquals(tenantPrincipalId, response.context.principalId)
        assertEquals(tenantId, response.context.tenantId)
        assertEquals(permissions.toSet(), response.tokenSet.scopes.toSet())

        val claims = JwtVerifier(emptyList(), signingKeys)
            .verifyReaktorToken(response.tokenSet.accessToken, listOf(appId))
            .getOrNull()
        assertNotNull(claims)
        assertEquals(tenantPrincipalId, claims.subject)
        assertEquals(tenantId, claims.getStringClaim("tid"))
        assertEquals(setOf("companyos:superadmin"), claims.getStringListClaim("roles").toSet())
        assertEquals(permissions.toSet(), claims.getStringListClaim("permissions").toSet())
    }

    @Test
    fun newSocialLoginWithoutNamesStillCreatesPrincipal() = runBlocking {
        val appId = AuthDbFixture.seedApp()
        val subject = "google-${UUID.randomUUID()}"

        val response = loginInteractor(SigningKeys("", "")).login(
            LoginRequest(
                idToken = subject,
                appId = appId,
                provider = UserProvider.GOOGLE,
                environment = Environment.STAGE,
            )
        )

        assertTrue(response is LoginResponse.Success)
        assertEquals(appId, response.context.appId)
        assertEquals(1, AuthDbFixture.providerAccountsByProviderAndSubject(UserProvider.GOOGLE, subject).size)
    }

    @Test
    fun invalidAppAndInvalidExternalTokenAreRejected() = runBlocking {
        val signingKeys = SigningKeys("", "")
        val appId = AuthDbFixture.seedApp()
        val unknownAppId = UUID.randomUUID().toString()

        assertEquals(
            LoginResponse.Failure.InvalidAppId,
            loginInteractor(signingKeys).login(
                LoginRequest(
                    idToken = "google-${UUID.randomUUID()}",
                    appId = unknownAppId,
                    provider = UserProvider.GOOGLE,
                    givenName = "Bad",
                    familyName = "App",
                    environment = Environment.STAGE,
                )
            ),
        )

        assertEquals(
            LoginResponse.Failure.InvalidIdToken,
            loginInteractor(signingKeys).login(
                LoginRequest(
                    idToken = "not-a-jwt",
                    appId = appId,
                    provider = UserProvider.GOOGLE,
                    givenName = "Bad",
                    familyName = "Token",
                    environment = Environment.STAGE,
                )
            ),
        )
    }

    private fun loginInteractor(signingKeys: SigningKeys): LoginInteractor {
        val adapter = AuthDbFixture.adapter()
        val verifier = StubJwtVerifier(signingKeys)
        return LoginInteractor(
            authRepository = AuthRepository(adapter),
            appRepository = AppRepository(adapter),
            jwtVerifier = verifier,
            jwtMinter = JwtMinter(signingKeys),
            sessionRefreshService = SessionRefreshService(),
        )
    }
}

private class StubJwtVerifier(signingKeys: SigningKeys) : JwtVerifier(emptyList(), signingKeys) {
    override suspend fun invoke(loginRequest: LoginRequest): Result<AuthenticatedUser> =
        if (loginRequest.idToken == "not-a-jwt") {
            Result.failure(IllegalArgumentException("invalid test token"))
        } else {
            Result.success(
                AuthenticatedUser(
                    subject = loginRequest.idToken,
                    provider = loginRequest.provider,
                    issuer = when (loginRequest.provider) {
                        UserProvider.GOOGLE -> "https://accounts.google.com"
                        UserProvider.APPLE -> "https://appleid.apple.com"
                        UserProvider.SUPABASE -> "supabase"
                        UserProvider.REAKTOR -> "https://api.reaktor.build/auth"
                    },
                    email = "${loginRequest.idToken}@example.test",
                    emailVerified = true,
                )
            )
        }
}
