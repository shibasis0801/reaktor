package dev.shibasis.reaktor.auth.api

import kotlinx.serialization.Serializable

@Serializable
data class SignInRequest(
    val googleIdToken: String,
    val appId: Long
)

@Serializable
sealed class SignInResponse {
    @Serializable
    data class Success(val token: String): SignInResponse()

    @Serializable
    sealed class Failure: SignInResponse() {
        @Serializable
        data object InvalidGoogleIdToken: Failure()

        @Serializable
        data object InvalidAppId: Failure()

        @Serializable
        data object RequiresSignUp: Failure()

        @Serializable
        data class ServerError(val code: Int, val message: String): Failure()
    }
}