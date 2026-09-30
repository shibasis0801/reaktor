package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AuthService

class DesktopAuthAdapter(
    authClient: AuthService,
    sessionStore: AuthSessionStore? = null,
): AuthAdapter<Unit>(Unit, authClient, sessionStore) {
    override suspend fun logout(): Result<Unit> = logoutSession()

    fun registerGoogleLogin(configuration: suspend () -> DesktopGoogleConfiguration) {
        register(UserProvider.GOOGLE, DesktopGoogleLogin(this, configuration))
    }
}
