package dev.shibasis.reaktor.auth

class AndroidAppleLogin(
    adapter: AndroidAuthAdapter
): AppleAuthProvider<AndroidAuthAdapter>(adapter) {
    override suspend fun login(): Result<AppleUser> {
        // needs web login
        TODO("Not yet implemented")
    }

    override suspend fun getUser(): Result<AppleUser> {
        // needs web login
        TODO("Not yet implemented")
    }
}