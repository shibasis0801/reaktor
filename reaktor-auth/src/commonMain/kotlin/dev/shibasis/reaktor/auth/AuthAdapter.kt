package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.api.DeactivateAccountRequest
import dev.shibasis.reaktor.auth.api.DeactivateAccountResponse
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.api.RefreshRequest
import dev.shibasis.reaktor.auth.api.RefreshResponse
import dev.shibasis.reaktor.auth.api.LogoutRequest
import dev.shibasis.reaktor.auth.api.TokenSet
import dev.shibasis.reaktor.auth.transport.AUTHORIZATION_HEADER
import dev.shibasis.reaktor.auth.transport.bearerAuthorization
import dev.shibasis.reaktor.auth.db.AuthObjectStore
import dev.shibasis.reaktor.auth.toAuthContext
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.db.Database
import dev.shibasis.reaktor.service.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock

abstract class AuthAdapter<Controller>(
    controller: Controller,
    private val authClient: AuthService,
    private val sessionStore: AuthSessionStore? = null,
): Adapter<Controller>(controller) {
    private val refreshMutex = Mutex()
    protected var activeEnvironment = Environment.PROD
        private set
    private val _loginState = MutableStateFlow<AuthLoginState>(AuthLoginState.Idle)
    val loginState: StateFlow<AuthLoginState> = _loginState.asStateFlow()
    val currentLoginState: AuthLoginState
        get() = _loginState.value

    private val _sessionEndedAt = MutableStateFlow(0L)
    val sessionEndedAt: StateFlow<Long> = _sessionEndedAt.asStateFlow()

    protected val providers = hashMapOf<UserProvider, AuthProvider<AuthAdapter<*>, out AuthProviderUser>>()

    fun register(provider: UserProvider, authProvider: AuthProvider<AuthAdapter<*>, out AuthProviderUser>) {
        providers[provider] = authProvider
    }
    fun unregister(provider: UserProvider) {
        providers.remove(provider)
    }
    fun clear() {
        providers.clear()
    }

    suspend fun login(
        appId: String,
        environment: Environment = Environment.PROD,
        userProvider: UserProvider,
        mode: AuthLoginMode = AuthLoginMode.Interactive,
        tenantHint: String? = null,
        contextHint: String? = null,
    ): LoginResponse = refreshMutex.withLock {
        try { loginLocked(appId, environment, userProvider, mode, tenantHint, contextHint) }
        catch (cancelled: CancellationException) { resetLoginState(); throw cancelled }
    }

    private suspend fun loginLocked(
        appId: String,
        environment: Environment,
        userProvider: UserProvider,
        mode: AuthLoginMode,
        tenantHint: String?,
        contextHint: String?,
    ): LoginResponse {
        activeEnvironment = environment
        transitionTo(AuthLoginState.LoadingProvider(userProvider, mode))
        val authProvider = providers[userProvider]
            ?: return failLogin(
                userProvider,
                mode,
                AuthLoginFailure.UnsupportedProvider,
                LoginResponse.Failure.UnsupportedUserProvider
            )

        transitionTo(AuthLoginState.WaitingForProvider(userProvider, mode))
        val providerUser = when (mode) {
            AuthLoginMode.Interactive -> authProvider.login()
            AuthLoginMode.ExistingSession -> authProvider.getUser()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            return failLogin(
                userProvider,
                mode,
                AuthLoginFailure.ProviderFailed(error.message ?: error::class.simpleName ?: "Provider login failed"),
                LoginResponse.Failure.AppLoginFailure(userProvider)
            )
        }

        Logger.i {
            "Provider login succeeded: provider=$userProvider email=${providerUser.emailId.ifBlank { "<empty>" }} idTokenPresent=${providerUser.idToken.isNotBlank()}"
        }

        transitionTo(AuthLoginState.SigningIntoReaktor(userProvider, providerUser.emailId))
        val response = runCatching {
            authClient.login(
                LoginRequest(
                    idToken = providerUser.idToken,
                    appId = appId,
                    provider = userProvider,
                    tenantHint = tenantHint,
                    contextHint = contextHint,
                    givenName = providerUser.givenName,
                    familyName = providerUser.familyName,
                    profile = providerUser.json(),
                    environment = environment
                )
            )
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            LoginResponse.Failure.ServerError(error.message ?: "Auth service login failed")
        }

        when (response) {
            is LoginResponse.Success -> {
                val context = response.context.toAuthContext()
                try {
                    currentCoroutineContext().ensureActive()
                    cache(response, environment)
                } catch (error: Exception) {
                    withContext(NonCancellable) {
                        runCatching { withTimeout(10_000) {
                            response.tokenSet.refreshToken?.let { token ->
                                authClient.sessionLogout(LogoutRequest(token, environment = environment))
                            }
                        } }
                        runCatching {
                            if (sessionStore != null) sessionStore.clear(environment) else authStoreOrNull()?.clear()
                        }
                    }
                    if (error is CancellationException) throw error
                    return failLogin(userProvider, mode, AuthLoginFailure.ProviderFailed("Could not securely store the Reaktor session"),
                        LoginResponse.Failure.ServerError("Could not securely store the Reaktor session"))
                }
                transitionTo(AuthLoginState.Authenticated(context))
            }
            is LoginResponse.Failure -> {
                transitionTo(AuthLoginState.Failed(userProvider, mode, AuthLoginFailure.ReaktorRejected(response)))
            }
        }

        Logger.i { "Reaktor login response: ${response::class.simpleName}" }
        return response
    }

    abstract suspend fun logout(): Result<Unit>

    suspend fun resumeSession(environment: Environment = Environment.PROD): Boolean {
        activeEnvironment = environment
        if (sessionHeaders(environment).isEmpty()) { resetLoginState(); return false }
        val context = if (sessionStore != null) sessionStore.read(environment)?.context else authStoreOrNull()?.getContext()
        if (context == null) { resetLoginState(); return false }
        transitionTo(AuthLoginState.Authenticated(context))
        return true
    }

    /**
     * Grace-period account deletion. Sends the caller's session access token to
     * `/auth/account/deactivate`, which revokes every session + refresh token and
     * marks the principal + identity SOFT_DELETED (stamping deactivated_at). The
     * hard purge runs later off that timestamp. Local teardown (clearing cached
     * user/session, same as logout) is the caller's responsibility.
     *
     * [appId] is the token audience — session access tokens are minted with the
     * app id as their audience, so it must match here for verification to pass.
     */
    suspend fun deactivateAccount(
        appId: String,
        environment: Environment = Environment.PROD,
    ): DeactivateAccountResponse {
        val headers = sessionHeaders(environment)
        if (headers.isEmpty()) {
            return DeactivateAccountResponse(statusCode = StatusCode.UNAUTHORIZED)
        }
        return runCatching {
            authClient.accountDeactivate(
                DeactivateAccountRequest(
                    audience = appId,
                    headers = headers,
                    environment = environment,
                )
            )
        }.getOrElse { error ->
            Logger.e(error) { "Account deactivation request failed" }
            DeactivateAccountResponse(statusCode = StatusCode.INTERNAL_SERVER_ERROR)
        }
    }

    suspend fun accessToken(): String? = if (sessionStore != null) sessionStore.read(activeEnvironment)?.tokens?.accessToken
        else authStoreOrNull()?.getAccessToken()

    suspend fun refreshToken(): String? = if (sessionStore != null) sessionStore.read(activeEnvironment)?.tokens?.refreshToken
        else authStoreOrNull()?.getRefreshToken()

    suspend fun sessionHeaders(
        environment: Environment = Environment.PROD,
        refreshIfMissing: Boolean = true,
    ): MutableMap<String, String> {
        if (sessionStore != null) {
            val session = sessionStore.read(environment)
            val cached = session?.takeIf { it.expiresAtEpochMillis > Clock.System.now().toEpochMilliseconds() + REFRESH_SKEW_MILLIS }?.tokens
            val tokens = cached ?: if (refreshIfMissing) refreshSession(environment) else null
            return tokens?.let { mutableMapOf(AUTHORIZATION_HEADER to bearerAuthorization(it.accessToken)) } ?: mutableMapOf()
        }
        val store = authStoreOrNull()
        val cached = store?.getFreshAccessToken()
        val refreshed = if (cached == null && refreshIfMissing) {
            refreshSession(environment)?.accessToken
        } else {
            null
        }
        val accessToken = cached ?: refreshed
            ?: return mutableMapOf()

        return mutableMapOf(AUTHORIZATION_HEADER to bearerAuthorization(accessToken))
    }

    suspend fun refreshSession(environment: Environment = Environment.PROD): TokenSet? = refreshMutex.withLock {
        if (sessionStore != null) {
            val current = sessionStore.read(environment) ?: return@withLock null
            if (current.expiresAtEpochMillis > Clock.System.now().toEpochMilliseconds() + REFRESH_SKEW_MILLIS) return@withLock current.tokens
            val token = current.tokens.refreshToken ?: return@withLock null
            val response = authClient.sessionRefresh(RefreshRequest(token, environment = environment))
            if (response.statusCode == StatusCode.UNAUTHORIZED) {
                sessionStore.clear(environment); resetLoginState(); _sessionEndedAt.value = Clock.System.now().toEpochMilliseconds(); return@withLock null
            }
            check(response.statusCode == StatusCode.OK) { "Reaktor session refresh is temporarily unavailable" }
            val tokens = response.tokenSet ?: return@withLock null
            require(!tokens.refreshToken.isNullOrBlank() && tokens.expiresInSeconds > 0) { "Refresh did not return a complete rotated session" }
            sessionStore.write(environment, current.copy(tokens = tokens, expiresAtEpochMillis = requireNotNull(tokens.accessTokenExpiresAtEpochMillis())))
            return@withLock tokens
        }
        val store = authStoreOrNull() ?: return@withLock null
        val refreshToken = store.getRefreshToken() ?: return@withLock null
        val response = runCatching {
            authClient.sessionRefresh(RefreshRequest(refreshToken = refreshToken, environment = environment))
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            Logger.e(error) { "Failed to refresh Reaktor auth session" }
            return@withLock null
        }

        if (response.statusCode == StatusCode.UNAUTHORIZED) {
            store.clear(); resetLoginState(); _sessionEndedAt.value = Clock.System.now().toEpochMilliseconds(); return@withLock null
        }
        if (response.statusCode != StatusCode.OK) return@withLock null
        val tokenSet = response.tokenSet ?: return@withLock null
        cache(tokenSet)
        tokenSet
    }

    suspend fun exchangeRefreshToken(refreshToken: String, environment: Environment = activeEnvironment): RefreshResponse =
        authClient.sessionRefresh(RefreshRequest(refreshToken = refreshToken, environment = environment))

    /** Revoke remotely, then clear local credentials even when offline; report an unconfirmed revocation. */
    protected suspend fun logoutSession(environment: Environment = activeEnvironment): Result<Unit> = refreshMutex.withLock {
        val token = if (sessionStore != null) sessionStore.read(environment)?.tokens?.refreshToken else authStoreOrNull()?.getRefreshToken()
        val revoked = runCatching {
            if (token != null) {
                val response = authClient.sessionLogout(LogoutRequest(token, environment = environment))
                check(response.success || response.statusCode == StatusCode.UNAUTHORIZED) { "Server logout was not confirmed" }
            }
        }
        withContext(NonCancellable) {
            try { if (sessionStore != null) sessionStore.clear(environment) else authStoreOrNull()?.clear() }
            finally { resetLoginState() }
        }
        (revoked.exceptionOrNull() as? CancellationException)?.let { throw it }
        revoked
    }

    protected fun resetLoginState() {
        transitionTo(AuthLoginState.Idle)
    }

    private suspend fun cache(response: LoginResponse.Success, environment: Environment) {
        if (sessionStore != null) {
            val expiry = requireNotNull(response.tokenSet.accessTokenExpiresAtEpochMillis()) { "Login returned no session expiry" }
            sessionStore.write(environment, StoredAuthSession(response.context.toAuthContext(), response.tokenSet, expiry))
            return
        }
        val db = Feature.Database ?: return
        transitionTo(AuthLoginState.CachingSession(response.context.principalId))
        try {
            val authStore = AuthObjectStore(db)
            authStore.setContext(response.context.toAuthContext())
            authStore.setTokenSet(response.tokenSet)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to cache auth tokens to ObjectDatabase" }
            throw e
        }
    }

    private suspend fun cache(tokenSet: TokenSet) {
        val store = authStoreOrNull() ?: return
        runCatching {
            store.setTokenSet(tokenSet)
        }.onFailure { error ->
            Logger.e(error) { "Failed to cache refreshed auth tokens to ObjectDatabase" }
        }
    }

    private fun authStoreOrNull(): AuthObjectStore? =
        Feature.Database?.let(::AuthObjectStore)

    private suspend fun AuthObjectStore.setTokenSet(tokenSet: TokenSet) {
        setAccessToken(tokenSet.accessToken)
        tokenSet.accessTokenExpiresAtEpochMillis()?.let {
            setAccessTokenExpiresAtEpochMillis(it)
        } ?: clearAccessTokenExpiresAtEpochMillis()
        tokenSet.refreshToken?.let { setRefreshToken(it) }
    }

    private suspend fun AuthObjectStore.getFreshAccessToken(): String? {
        val token = getAccessToken() ?: return null
        val expiresAt = getAccessTokenExpiresAtEpochMillis() ?: return token
        return token.takeIf { expiresAt > Clock.System.now().toEpochMilliseconds() + REFRESH_SKEW_MILLIS }
    }

    private fun TokenSet.accessTokenExpiresAtEpochMillis(): Long? {
        if (expiresInSeconds <= 0) return null
        return Clock.System.now().toEpochMilliseconds() + expiresInSeconds.toLong() * 1_000L
    }

    private fun failLogin(
        provider: UserProvider,
        mode: AuthLoginMode,
        failure: AuthLoginFailure,
        response: LoginResponse.Failure
    ): LoginResponse.Failure {
        transitionTo(AuthLoginState.Failed(provider, mode, failure))
        return response
    }

    private fun transitionTo(state: AuthLoginState) {
        _loginState.value = state
        Logger.i { "Auth login state: ${state.debugName}" }
    }

    private companion object {
        const val REFRESH_SKEW_MILLIS = 60_000L
    }
}

var Feature.Auth by CreateSlot<AuthAdapter<*>>()
