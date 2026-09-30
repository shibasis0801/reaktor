package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.*
import dev.shibasis.reaktor.auth.kernel.*
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.PostHandler
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlin.test.*

class AuthAdapterSessionTest {
    private fun client(login: suspend (LoginRequest) -> LoginResponse, logout: suspend (LogoutRequest) -> LogoutResponse = { LogoutResponse(success = true) }) =
        object : AuthServiceClient("https://unused.example.test") {
            override val login = PostHandler("/fixture/login", requestSerializer = LoginRequest.serializer(),
                responseSerializer = LoginResponse.serializer(), handler = { login(it) })
            override val sessionLogout = PostHandler("/fixture/logout", requestSerializer = LogoutRequest.serializer(),
                responseSerializer = LogoutResponse.serializer(), handler = { logout(it) })
        }
    private fun DesktopAuthAdapter.provider(block: suspend () -> Result<GoogleUser>) {
        register(UserProvider.GOOGLE, object : GoogleAuthProvider<DesktopAuthAdapter>(this) {
            override suspend fun login() = block()
            override suspend fun getUser() = block()
        })
    }
    private val user = GoogleUser("fixture-id-token", "Ada", "Lovelace", "ada@example.test", "")

    @Test fun providerAndServerCancellationPropagateAndRestoreIdleState() = runBlocking<Unit> {
        val adapter = DesktopAuthAdapter(client({ throw CancellationException("server cancelled") }))
        adapter.provider { Result.failure(CancellationException("provider cancelled")) }
        assertFailsWith<CancellationException> { adapter.login("app", userProvider = UserProvider.GOOGLE) }
        assertIs<AuthLoginState.Idle>(adapter.currentLoginState)
        adapter.provider { Result.success(user) }
        assertFailsWith<CancellationException> { adapter.login("app", userProvider = UserProvider.GOOGLE) }
        assertIs<AuthLoginState.Idle>(adapter.currentLoginState)
    }

    @Test fun concurrentLoginsCannotMixProviderOrEnvironmentState() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val environments = mutableListOf<Environment>()
        var providerCalls = 0
        val adapter = DesktopAuthAdapter(client({ environments += it.environment; LoginResponse.Failure.InvalidAppId }))
        adapter.provider { providerCalls++; release.await(); Result.success(user) }
        val first = async(start = CoroutineStart.UNDISPATCHED) { adapter.login("app", Environment.PROD, UserProvider.GOOGLE) }
        val second = async(start = CoroutineStart.UNDISPATCHED) { adapter.login("app", Environment.STAGE, UserProvider.GOOGLE) }
        assertEquals(1, providerCalls)
        release.complete(Unit); first.await(); second.await()
        assertEquals(listOf(Environment.PROD, Environment.STAGE), environments)
        assertEquals(2, providerCalls)
    }

    @Test fun failedOrCancelledSecureStorageRevokesTheNewSessionAndClearsPartialCredentials() = runBlocking {
        for (cancelled in listOf(false, true)) {
            val context = AuthContext(principal = PrincipalRef("fixture-user", PrincipalKind.USER), appId = "app", audience = "app", method = AuthMethod.ACCESS_TOKEN)
            val response = LoginResponse.Success(context.toSnapshot(), JsonObject(emptyMap()), TokenSet("access", "refresh", expiresInSeconds = 60))
            var stored: StoredAuthSession? = null
            var revoked: String? = null
            val store = object : AuthSessionStore {
                override suspend fun read(environment: Environment) = stored
                override suspend fun write(environment: Environment, session: StoredAuthSession) {
                    stored = session
                    if (cancelled) throw CancellationException("storage cancelled") else error("storage failed after partial write")
                }
                override suspend fun clear(environment: Environment) { stored = null }
            }
            val adapter = DesktopAuthAdapter(client({ response }, { revoked = it.refreshToken; LogoutResponse(success = true) }), store)
            adapter.provider { Result.success(user) }
            if (cancelled) assertFailsWith<CancellationException> { adapter.login("app", userProvider = UserProvider.GOOGLE) }
            else assertIs<LoginResponse.Failure>(adapter.login("app", userProvider = UserProvider.GOOGLE))
            assertEquals("refresh", revoked)
            assertNull(stored)
            assertFalse(adapter.currentLoginState is AuthLoginState.Authenticated)
        }
    }
}
