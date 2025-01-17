package dev.shibasis.reaktor.auth.api

import kotlinx.serialization.Serializable


@Serializable
data class SignInRequest(
    val idToken: String,
    val appId: Long,
    val providerId: String = "Google"
)

@Serializable
sealed class SignInResponse {
    @Serializable
    data class Success(val socialId: String): SignInResponse()

    @Serializable
    sealed class Failure: SignInResponse() {
        @Serializable
        data object InvalidGoogleIdToken: Failure()

        @Serializable
        data object InvalidAppId: Failure()

        @Serializable
        data class RequiresSignUp(val socialId: String): Failure()

        @Serializable
        class ServerError(val message: String): Failure()
    }
}