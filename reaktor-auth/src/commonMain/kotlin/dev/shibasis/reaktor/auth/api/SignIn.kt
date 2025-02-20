package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.User
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
    data class Success(val socialId: String, val user: User): SignInResponse()

    @Serializable
    sealed class Failure: SignInResponse() {
        @Serializable
        data object InvalidGoogleIdToken: Failure()

        @Serializable
        data object InvalidAppId: Failure()

        @Serializable
        data class RequiresSignUp(val socialId: String, val user: User): Failure()

        @Serializable
        class ServerError(val message: String): Failure()
    }
}