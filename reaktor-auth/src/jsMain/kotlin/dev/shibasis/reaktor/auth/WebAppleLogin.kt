package dev.shibasis.reaktor.auth

class WebAppleLogin(webAuthAdapter: WebAuthAdapter): AppleAuthProvider<WebAuthAdapter>(webAuthAdapter) {
    override suspend fun login(): Result<AppleUser> {
        TODO("Not yet implemented")
    }

    override suspend fun getUser(): Result<AppleUser> {
        TODO("Not yet implemented")
    }

    override suspend fun logout(): Result<Unit> {
        return super.logout()
    }
}