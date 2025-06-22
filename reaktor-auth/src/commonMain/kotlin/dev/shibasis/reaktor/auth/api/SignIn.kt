package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.User
import dev.shibasis.reaktor.auth.UserProvider
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.Post
import dev.shibasis.reaktor.io.network.http
import dev.shibasis.reaktor.io.service.BaseRequest
import dev.shibasis.reaktor.io.service.BaseResponse
import dev.shibasis.reaktor.io.service.RequestHandler
import dev.shibasis.reaktor.io.service.Service
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement


@Serializable
data class LoginRequest(
    val idToken: String,
    val appId: String,
    val provider: UserProvider = UserProvider.GOOGLE,
    val userName: String, // apple does not send it in JWT, and will only send it only once. (wtf)
    val newUserProfile: JsonElement? = null,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf()
): BaseRequest

@Serializable
sealed class LoginResponse(
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): BaseResponse {
    @Serializable
    data class Success(val user: User, val profile: JsonElement): LoginResponse(StatusCode.OK)

    @Serializable
    sealed class Failure(private val hack: StatusCode): LoginResponse(hack) {
        @Serializable
        data object InvalidIdToken: Failure(StatusCode.BAD_REQUEST)

        @Serializable
        data object InvalidAppId: Failure(StatusCode.BAD_REQUEST)

        @Serializable
        data object RequiresUserProfile: Failure(StatusCode.NOT_FOUND)

        @Serializable
        class ServerError(val message: String): Failure(StatusCode.INTERNAL_SERVER_ERROR)
    }
}


abstract class AuthService: Service() {
    abstract val signIn: RequestHandler<LoginRequest, LoginResponse>
}

class AuthServiceClient(baseUrl: String): AuthService() {
    override val signIn = PostHandler<LoginRequest, LoginResponse>("${baseUrl}/auth/sign-in") {
        http.Post(route) { setBody(it) }
            .fold(
                { it.body() },
                { LoginResponse.Failure.ServerError("Unknown Error") }
            )
    }
}
