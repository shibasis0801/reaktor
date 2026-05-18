package dev.shibasis.reaktor.auth

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.core.framework.Adapter
import dev.shibasis.reaktor.core.framework.CreateSlot
import dev.shibasis.reaktor.core.framework.Feature
import dev.shibasis.reaktor.auth.db.AuthObjectStore
import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.db.Database
import dev.shibasis.reaktor.graph.service.Environment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        environment: Environment = Environment.STAGE,
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
                    environment = environment
                )
            )
        }.getOrElse { error ->
            LoginResponse.Failure.ServerError(error.message ?: "Auth service login failed")
        }

        when (response) {
            is LoginResponse.Success -> {
                cache(response)
                transitionTo(AuthLoginState.Authenticated(response.user))
            }
            is LoginResponse.Failure -> {
                transitionTo(AuthLoginState.Failed(userProvider, mode, AuthLoginFailure.ReaktorRejected(response)))
            }
        }

        Logger.i { "Reaktor login response: ${response::class.simpleName}" }
        return response
    }

    abstract suspend fun logout(): Result<Unit>

    protected fun resetLoginState() {
        transitionTo(AuthLoginState.Idle)
    }

    private suspend fun cache(response: LoginResponse.Success) {
        val db = Feature.Database ?: return
        transitionTo(AuthLoginState.CachingSession(response.user.id))
        try {
            val authStore = AuthObjectStore(db)
            authStore.setUser(response.user)
            authStore.setAccessToken(response.accessToken)
            authStore.setRefreshToken(response.refreshToken)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to cache auth tokens to ObjectDatabase" }
        }
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
}

var Feature.Auth by CreateSlot<AuthAdapter<*>>()
