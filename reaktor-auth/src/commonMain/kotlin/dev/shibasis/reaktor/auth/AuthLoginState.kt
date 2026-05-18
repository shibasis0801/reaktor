package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.LoginResponse

sealed class AuthLoginMode {
    data object Interactive : AuthLoginMode()
    data object ExistingSession : AuthLoginMode()
}

sealed class AuthLoginFailure {
    data object UnsupportedProvider : AuthLoginFailure()
    data class ProviderFailed(val message: String) : AuthLoginFailure()
    data class ReaktorRejected(val response: LoginResponse.Failure) : AuthLoginFailure()
}

sealed class AuthLoginState {
    data object Idle : AuthLoginState()

    data class LoadingProvider(
        val provider: UserProvider,
        val mode: AuthLoginMode
    ) : AuthLoginState()

    data class WaitingForProvider(
        val provider: UserProvider,
        val mode: AuthLoginMode
    ) : AuthLoginState()

    data class SigningIntoReaktor(
        val provider: UserProvider,
        val email: String
    ) : AuthLoginState()

    data class CachingSession(
        val userId: String
    ) : AuthLoginState()

    data class Authenticated(
        val user: User
    ) : AuthLoginState()

    data class Failed(
        val provider: UserProvider,
        val mode: AuthLoginMode,
        val failure: AuthLoginFailure
    ) : AuthLoginState()
}

val AuthLoginState.debugName: String
    get() = when (this) {
        AuthLoginState.Idle -> "idle"
        is AuthLoginState.LoadingProvider -> "provider.lookup"
        is AuthLoginState.WaitingForProvider -> when (mode) {
            AuthLoginMode.Interactive -> "provider.login"
            AuthLoginMode.ExistingSession -> "provider.restore"
        }
        is AuthLoginState.SigningIntoReaktor -> "reaktor.login"
        is AuthLoginState.CachingSession -> "session.cache"
        is AuthLoginState.Authenticated -> "authenticated"
        is AuthLoginState.Failed -> when (failure) {
            AuthLoginFailure.UnsupportedProvider -> "failure.unsupported_provider"
            is AuthLoginFailure.ProviderFailed -> "failure.provider"
            is AuthLoginFailure.ReaktorRejected -> "failure.reaktor"
        }
    }
