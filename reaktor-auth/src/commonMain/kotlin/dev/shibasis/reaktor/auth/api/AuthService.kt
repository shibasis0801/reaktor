package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.User
import dev.shibasis.reaktor.auth.UserProvider
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.Post
import dev.shibasis.reaktor.io.network.http
import dev.shibasis.reaktor.io.service.BaseRequest
import dev.shibasis.reaktor.io.service.BaseResponse
import dev.shibasis.reaktor.io.service.Environment
import dev.shibasis.reaktor.io.service.RequestHandler
import dev.shibasis.reaktor.io.service.Service
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive


@Serializable
data class LoginRequest(
    val idToken: String,
    val appId: String,
    val provider: UserProvider = UserProvider.GOOGLE,
    val givenName: String? = null, // apple does not send it in JWT, and will only send it only once. (wtf)
    val familyName: String? = null, // apple does not send it in JWT, and will only send it only once. (wtf)
    val newUserProfile: JsonElement = JsonObject(mapOf(
        "gender" to JsonPrimitive("Male"),
        "location" to JsonPrimitive("0101000020E6100000E78C28ED0D6653405396218E75F12940")
    )),
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment
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
        data object UnsupportedUserProvider: Failure(StatusCode.BAD_REQUEST)

        @Serializable
        data object RequiresUserName: Failure(StatusCode.BAD_REQUEST)

        @Serializable
        data object RequiresUserProfile: Failure(StatusCode.NOT_FOUND)

        @Serializable
        data class AppLoginFailure(val userProvider: UserProvider): Failure(StatusCode.BAD_REQUEST)

        @Serializable
        class ServerError(val message: String): Failure(StatusCode.INTERNAL_SERVER_ERROR)
    }
}


abstract class AuthService: Service() {
    abstract val login: RequestHandler<LoginRequest, LoginResponse>
}

class AuthServiceClient(baseUrl: String): AuthService() {
    override val login = PostHandler<LoginRequest, LoginResponse>("${baseUrl}/auth/sign-in") {
        http.Post(route) { setBody(it) }
            .fold(
                { it.body() },
                { LoginResponse.Failure.ServerError("Unknown Error") }
            )
    }
}
