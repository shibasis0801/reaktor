package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AuthServiceClient
import dev.shibasis.reaktor.core.utils.succeed

class WebAuthAdapter(
    authService: String
): AuthAdapter<Unit>(Unit, authClient = AuthServiceClient(authService)) {
    override suspend fun logout(): Result<Unit> {
        return succeed(Unit)
    }

    fun registerGoogleLogin(audience: String) {
        register(UserProvider.GOOGLE, WebGoogleLogin(this, audience))
    }

    fun registerAppleLogin() {
        register(UserProvider.APPLE, WebAppleLogin(this))
    }
}