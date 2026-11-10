package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AccountDeletionOptions
import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.api.DeactivateAccountRequest
import dev.shibasis.reaktor.auth.api.DeactivateAccountResponse
import dev.shibasis.reaktor.auth.api.TokenSet
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.contract.HttpLink
import dev.shibasis.reaktor.service.contract.over
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppleDeletionAdapterTest {
    @Test
    fun linkedAccountsRequestFreshAppleAuthorizationWithoutReplacingTheSession() = runBlocking {
        val fx = Fixture(linked = true, code = UUID.randomUUID().toString())
        assertTrue(fx.adapter.deactivateAccount("test-app", Environment.STAGE).deactivated)
        assertEquals(1, fx.confirmations)
        val request = assertNotNull(fx.sent)
        assertEquals(fx.code, request.appleAuthorizationCode)
        assertEquals("native-client", request.appleClientId)
        assertEquals(Environment.STAGE, request.environment)
        assertEquals("Bearer ${fx.session.tokens.accessToken}", request.headers["Authorization"])
        assertEquals(fx.session, fx.stored)
    }

    @Test
    fun accountsWithoutAnAppleLinkDoNotShowTheAppleSheet() = runBlocking {
        val fx = Fixture(linked = false, code = UUID.randomUUID().toString())
        assertTrue(fx.adapter.deactivateAccount("test-app", Environment.STAGE).deactivated)
        assertEquals(0, fx.confirmations)
        assertNull(fx.sent?.appleAuthorizationCode)
    }

    @Test
    fun missingAppleAuthorizationStillSendsTheDeletionRequest() = runBlocking {
        val fx = Fixture(linked = true, code = null)
        assertTrue(fx.adapter.deactivateAccount("test-app", Environment.STAGE).deactivated)
        assertEquals(1, fx.confirmations)
        assertNull(fx.sent?.appleAuthorizationCode)
    }

    @Test
    fun failedAppleConfirmationStillSendsTheDeletionRequest() = runBlocking {
        val fx = Fixture(linked = true, code = null, confirmationFails = true)
        assertTrue(fx.adapter.deactivateAccount("test-app", Environment.STAGE).deactivated)
        assertEquals(1, fx.confirmations)
        assertNull(fx.sent?.appleAuthorizationCode)
    }

    @Test
    fun failedOptionsResponseDoesNotShowTheAppleSheetOrPreventDeletion() = runBlocking {
        val fx = Fixture(linked = true, code = null, optionsStatus = StatusCode.INTERNAL_SERVER_ERROR)
        assertTrue(fx.adapter.deactivateAccount("test-app", Environment.STAGE).deactivated)
        assertEquals(0, fx.confirmations)
        assertNull(fx.sent?.appleAuthorizationCode)
    }

    private class Fixture(
        val linked: Boolean,
        val code: String?,
        val confirmationFails: Boolean = false,
        val optionsStatus: StatusCode = StatusCode.OK,
    ) {
        var confirmations = 0
        var sent: DeactivateAccountRequest? = null
        val session = StoredAuthSession(
            AuthContext(principal = PrincipalRef(UUID.randomUUID().toString(), PrincipalKind.USER),
                appId = "test-app", audience = "test-app", method = AuthMethod.ACCESS_TOKEN),
            TokenSet(UUID.randomUUID().toString(), UUID.randomUUID().toString(), expiresInSeconds = 3600),
            System.currentTimeMillis() + 3_600_000,
        )
        var stored: StoredAuthSession? = session
        private val store = object : AuthSessionStore {
            override suspend fun read(environment: Environment) = stored
            override suspend fun write(environment: Environment, session: StoredAuthSession) { stored = session }
            override suspend fun clear(environment: Environment) { stored = null }
        }
        private val client = object : AuthService() {
            override val accountDeletionOptions = super.accountDeletionOptions.handle {
                AccountDeletionOptions(appleLinked = linked, statusCode = optionsStatus)
            }
            override val accountDeactivate = super.accountDeactivate.handle { request ->
                sent = request
                DeactivateAccountResponse(deactivated = true)
            }
        } over HttpLink("https://unused.example.test")
        val adapter = object : AuthAdapter<Unit>(Unit, client, store) {
            override val appleDeletionClientId = "native-client"
            override suspend fun appleDeletionCode(): String? {
                confirmations++
                check(!confirmationFails)
                return code
            }
            override suspend fun logout() = logoutSession()
        }
    }
}
