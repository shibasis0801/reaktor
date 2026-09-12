package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.TokenSet
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.AuthMethod
import dev.shibasis.reaktor.security.MacKeychainCredentialStore
import dev.shibasis.reaktor.service.Environment
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.*

/** Explicit local integration gate; uses only ephemeral fixture credentials, never a user's session. */
class KeychainSessionIntegrationTest {
    @Test fun nativeKeychainPersistsReplacesIsolatesAndDeletesSessionRecords() = runBlocking {
        if (System.getenv("REAKTOR_TEST_KEYCHAIN") != "1") return@runBlocking
        val namespace = "dev.reaktor.test.${UUID.randomUUID()}"
        val keychain = MacKeychainCredentialStore(namespace)
        val store = CredentialAuthSessionStore(keychain, "fixture-app")
        val context = AuthContext(principal = PrincipalRef("fixture-user", PrincipalKind.USER), appId = "fixture-app", audience = "fixture-app", method = AuthMethod.ACCESS_TOKEN)
        val first = StoredAuthSession(context, TokenSet("fixture-access", "fixture-refresh", expiresInSeconds = 60), System.currentTimeMillis() + 60_000)
        try {
            assertNull(store.read(Environment.PROD))
            store.write(Environment.PROD, first)
            assertEquals(first, CredentialAuthSessionStore(MacKeychainCredentialStore(namespace), "fixture-app").read(Environment.PROD))
            assertNull(store.read(Environment.STAGE))
            val rotated = first.copy(tokens = first.tokens.copy(refreshToken = "fixture-rotated"))
            store.write(Environment.PROD, rotated)
            assertEquals(rotated, store.read(Environment.PROD))
            assertFailsWith<IllegalArgumentException> { store.write(Environment.PROD, first.copy(context = context.copy(appId = "wrong"))) }
            store.clear(Environment.PROD)
            assertNull(store.read(Environment.PROD))
        } finally { store.clear(Environment.PROD); store.clear(Environment.STAGE) }
    }
}
