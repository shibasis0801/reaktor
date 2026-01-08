package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AuthService

class DesktopAuthAdapter(
    authClient: AuthService
): AuthAdapter<Unit>(Unit, authClient) {
    override suspend fun logout(): Result<Unit> {
        return Result.success(Unit)
    }
}
