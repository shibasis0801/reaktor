package dev.shibasis.reaktor.auth

class WebGoogleLogin(
    webAuthAdapter: WebAuthAdapter,
    audience: String
): GoogleAuthProvider<WebAuthAdapter>(webAuthAdapter) {
    override suspend fun logout(): Result<Unit> {
        return super.logout()
    }

    override suspend fun getUser(): Result<GoogleUser> {
        TODO("Not yet implemented")
    }

    override suspend fun login(): Result<GoogleUser> {
        TODO("Not yet implemented")
    }
}