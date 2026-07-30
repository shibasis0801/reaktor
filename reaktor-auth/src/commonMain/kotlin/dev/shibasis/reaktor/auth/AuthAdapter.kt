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
import kotlin.time.Clock

abstract class AuthAdapter<Controller>(
    controller: Controller,
    private val authClient: AuthService
): Adapter<Controller>(controller) {
    private val _loginState = MutableStateFlow<AuthLoginState>(AuthLoginState.Idle)
    val loginState: StateFlow<AuthLoginState> = _loginState.asStateFlow()
    val currentLoginState: AuthLoginState
        get() = _loginState.value

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
        mode: AuthLoginMode = AuthLoginMode.Interactive
    ): LoginResponse {
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
                    givenName = providerUser.givenName,
                    familyName = providerUser.familyName,
                    profile = providerUser.json(),
                    environment = environment
                )
            )
        }.getOrElse { error ->
            LoginResponse.Failure.ServerError(error.message ?: "Auth service login failed")
        }

        when (response) {
            is LoginResponse.Success -> {
                val context = response.context.toAuthContext()
                cache(response)
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

    suspend fun accessToken(): String? =
        authStoreOrNull()?.getAccessToken()

    suspend fun refreshToken(): String? =
        authStoreOrNull()?.getRefreshToken()

    suspend fun sessionHeaders(
        environment: Environment = Environment.PROD,
        refreshIfMissing: Boolean = true,
    ): MutableMap<String, String> {
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

    suspend fun refreshSession(environment: Environment = Environment.PROD): TokenSet? {
        val store = authStoreOrNull() ?: return null
        val refreshToken = store.getRefreshToken() ?: return null
        val response = runCatching {
            authClient.sessionRefresh(RefreshRequest(refreshToken = refreshToken, environment = environment))
        }.getOrElse { error ->
            Logger.e(error) { "Failed to refresh Reaktor auth session" }
            return null
        }

        if (response.statusCode != StatusCode.OK) return null
        val tokenSet = response.tokenSet ?: return null
        cache(tokenSet)
        return tokenSet
    }

    protected fun resetLoginState() {
        transitionTo(AuthLoginState.Idle)
    }

    private suspend fun cache(response: LoginResponse.Success) {
        val db = Feature.Database ?: return
        transitionTo(AuthLoginState.CachingSession(response.context.principalId))
        try {
            val authStore = AuthObjectStore(db)
            authStore.setContext(response.context.toAuthContext())
            authStore.setTokenSet(response.tokenSet)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to cache auth tokens to ObjectDatabase" }
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
