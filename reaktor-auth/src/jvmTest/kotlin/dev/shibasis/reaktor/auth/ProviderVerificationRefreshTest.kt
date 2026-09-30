package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AnonymousAuthRequest
import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.auth.db.AuthRepository
import dev.shibasis.reaktor.auth.kernel.AuthProviderKind
import dev.shibasis.reaktor.auth.services.uuid
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import dev.shibasis.reaktor.service.Environment
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.test.*

class ProviderVerificationRefreshTest {
    @BeforeTest fun setup() = AuthDbFixture.ensure()
    @Test fun verifiedProviderLoginRefreshesLegacyFlagWithoutChangingIdentity() = runBlocking {
        val appId = AuthDbFixture.seedApp(); val subject = "google-${UUID.randomUUID()}"
        val principal = AuthDbFixture.seedUser(appId, socialId = subject)
        transaction { ProviderAccounts.update({ ProviderAccounts.subject eq subject }) { it[emailVerified] = false } }
        val request = AnonymousAuthRequest(appId, environment = Environment.STAGE)
        val adapter = AuthDbFixture.adapter()
        val app = AppRepository(adapter).findById(request, appId.uuid()).getOrThrow()!!
        val repo = AuthRepository(adapter)
        val verified = repo.resolveExternalLogin(request, app, AuthProviderKind.GOOGLE, "https://accounts.google.com", subject,
            "$subject@example.test", true, EMPTY_JSON).getOrThrow()
        assertEquals(principal, verified.principal.id)
        assertTrue(verified.providerAccount.emailVerified)
        val persisted = transaction { ProviderAccounts.selectAll().where { ProviderAccounts.subject eq subject }.single()[ProviderAccounts.emailVerified] }
        assertTrue(persisted)
        val changed = repo.resolveExternalLogin(request, app, AuthProviderKind.GOOGLE, "https://accounts.google.com", subject,
            "changed@example.test", false, EMPTY_JSON).getOrThrow()
        assertEquals(verified.identity.id, changed.identity.id)
        assertEquals("changed@example.test", changed.providerAccount.email)
        assertFalse(changed.providerAccount.emailVerified)
    }
}
