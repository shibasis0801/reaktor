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
import dev.shibasis.reaktor.auth.kernel.IdentityStatus
import dev.shibasis.reaktor.auth.kernel.PrincipalStatus
import dev.shibasis.reaktor.auth.services.LoginInteractor
import dev.shibasis.reaktor.auth.services.SessionRefreshService
import dev.shibasis.reaktor.auth.services.uuid
import dev.shibasis.reaktor.service.Environment
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * Grace-period account deletion end-to-end over the real repositories + H2 fixture:
 * softDeleteAccount marks the principal + identity SOFT_DELETED (stamping deactivated_at), a
 * login within the window auto-restores (restoreWithinGrace inside resolveExternalLogin), and a
 * login past the window is rejected and left soft-deleted for the purge.
 */
class AccountDeactivationIntegrationTest {
    @BeforeTest
    fun setup() = AuthDbFixture.ensure()

    @Test
    fun softDeleteMarksPrincipalAndIdentitySoftDeleted() = runBlocking {
        val appId = AuthDbFixture.seedApp()
        val principalId = AuthDbFixture.seedUser(appId = appId, socialId = "google-${UUID.randomUUID()}")

        val ok = AuthRepository(AuthDbFixture.adapter())
            .softDeleteAccount(request(appId), principalId.uuid())
            .getOrThrow()

        assertTrue(ok)
        val principal = AuthDbFixture.principalById(principalId)
        assertNotNull(principal)
        assertEquals(PrincipalStatus.SOFT_DELETED, principal.status)
        assertEquals(IdentityStatus.SOFT_DELETED, AuthDbFixture.identityById(principal.identityId!!)?.status)
        assertTrue(AuthDbFixture.deactivatedAtOf(principalId) != null, "deactivated_at should be stamped")
    }

    @Test
    fun loginWithinGraceRestoresSoftDeletedAccount() = runBlocking {
        val appId = AuthDbFixture.seedApp()
        val subject = "google-${UUID.randomUUID()}"
        val principalId = AuthDbFixture.seedUser(appId = appId, socialId = subject)
        AuthRepository(AuthDbFixture.adapter()).softDeleteAccount(request(appId), principalId.uuid()).getOrThrow()

        val response = loginInteractor().login(loginRequest(appId, subject))

        assertTrue(response is LoginResponse.Success, "sign-in within grace should succeed")
        assertEquals(principalId, response.context.principalId)
        val principal = AuthDbFixture.principalById(principalId)
        assertEquals(PrincipalStatus.ACTIVE, principal?.status, "principal restored to ACTIVE")
        assertEquals(IdentityStatus.ACTIVE, AuthDbFixture.identityById(principal!!.identityId!!)?.status)
        assertNull(AuthDbFixture.deactivatedAtOf(principalId), "deactivated_at cleared on restore")
    }

    @Test
    fun loginPastGraceIsRejectedAndNotRestored() = runBlocking {
        val appId = AuthDbFixture.seedApp()
        val subject = "google-${UUID.randomUUID()}"
        val principalId = AuthDbFixture.seedUser(appId = appId, socialId = subject)
        AuthRepository(AuthDbFixture.adapter()).softDeleteAccount(request(appId), principalId.uuid()).getOrThrow()
        AuthDbFixture.setDeactivatedAt(principalId, Clock.System.now() - 31.days)

        val response = loginInteractor().login(loginRequest(appId, subject))

        assertEquals(LoginResponse.Failure.PrincipalUnavailable, response, "sign-in past grace is rejected")
        assertEquals(
            PrincipalStatus.SOFT_DELETED,
            AuthDbFixture.principalById(principalId)?.status,
            "principal stays soft-deleted for the purge",
        )
    }

    private fun request(appId: String) = AnonymousAuthRequest(appId = appId, environment = Environment.STAGE)

    private fun loginRequest(appId: String, subject: String) = LoginRequest(
        idToken = subject,
        appId = appId,
        provider = UserProvider.GOOGLE,
        environment = Environment.STAGE,
    )

    private fun loginInteractor(): LoginInteractor {
        val adapter = AuthDbFixture.adapter()
        val signingKeys = SigningKeys("", "")
        return LoginInteractor(
            authRepository = AuthRepository(adapter),
            appRepository = AppRepository(adapter),
            jwtVerifier = StubVerifier(signingKeys),
            jwtMinter = JwtMinter(signingKeys),
            sessionRefreshService = SessionRefreshService(),
        )
    }
}

private class StubVerifier(signingKeys: SigningKeys) : JwtVerifier(emptyList(), signingKeys) {
    override suspend fun invoke(loginRequest: LoginRequest): Result<AuthenticatedUser> =
        Result.success(
            AuthenticatedUser(
                subject = loginRequest.idToken,
                provider = loginRequest.provider,
                issuer = "https://accounts.google.com",
                email = "${loginRequest.idToken}@example.test",
                emailVerified = true,
            )
        )
}
