package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.User
import dev.shibasis.reaktor.auth.UserProvider
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.io.network.Post
import dev.shibasis.reaktor.io.network.http
import dev.shibasis.reaktor.graph.service.Request
import dev.shibasis.reaktor.graph.service.Response
import dev.shibasis.reaktor.graph.service.Environment
import dev.shibasis.reaktor.graph.service.PostHandler
import dev.shibasis.reaktor.graph.service.RequestHandler
import dev.shibasis.reaktor.graph.service.Service
import io.ktor.client.call.body
import io.ktor.client.request.setBody
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.js.ExperimentalJsStatic
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.js.JsStatic

@JsExport
@Serializable
data class LoginRequest(
    val idToken: String,
    val appId: String,
    val provider: UserProvider = UserProvider.GOOGLE,
    val nonce: String? = null,
    val state: String? = null,
    val tenantHint: String? = null,
    val contextHint: String? = null,
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
): Request() {
    companion object {
        @OptIn(ExperimentalJsStatic::class)
        @JsStatic
        fun Create(idToken: String, appId: String, provider: UserProvider = UserProvider.GOOGLE, environment: Environment) =
            LoginRequest(idToken, appId, provider, environment = environment)
    }
}



@JsExport
@Serializable
data class TokenSet(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Int = 0,
    val sessionId: String? = null,
    val audience: String? = null,
    val scopes: List<String> = emptyList()
)

@JsExport
@Serializable
sealed class LoginResponse(
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): Response() {
    @Serializable
    data class Success(
        val user: User, 
        val profile: JsonElement,
        val accessToken: String,
        val refreshToken: String,
        val tokenSet: TokenSet = TokenSet(
            accessToken = accessToken,
            refreshToken = refreshToken
        )
    ): LoginResponse(StatusCode.OK)

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

@JsExport
@Serializable
data class MintPatRequest(
    val name: String,
    val scopes: List<String> = listOf("mcp:read"),
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD
): Request() {
    companion object {
        @OptIn(ExperimentalJsStatic::class)
        @JsStatic
        fun Create(name: String, environment: Environment) = MintPatRequest(name, environment = environment)
    }
}

@JsExport
@Serializable
data class MintPatResponse(
    val rawToken: String,
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): Response()

@JsExport
@Serializable
data class VerifyPatRequest(
    val rawToken: String,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD
): Request() {
    companion object {
        @OptIn(ExperimentalJsStatic::class)
        @JsStatic
        fun Create(rawToken: String, environment: Environment) = VerifyPatRequest(rawToken, environment = environment)
    }
}

@JsExport
@Serializable
data class VerifyPatResponse(
    val isValid: Boolean,
    val tokenId: String? = null,
    val name: String? = null,
    val scopes: List<String> = emptyList(),
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): Response()

@JsExport
@Serializable
data class ExchangePatRequest(
    val rawToken: String = "",
    val audience: String = "manna-mcp",
    val ttlSeconds: Int = 15 * 60,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD
): Request() {
    companion object {
        @OptIn(ExperimentalJsStatic::class)
        @JsStatic
        fun Create(rawToken: String, environment: Environment) =
            ExchangePatRequest(rawToken, environment = environment)
    }
}

@JsExport
@Serializable
data class ExchangePatResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Int = 0,
    val tokenId: String? = null,
    val scopes: List<String> = emptyList(),
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): Response()

@JsExport
@Serializable
data class TokenRequest(
    @SerialName("grant_type")
    val grantType: String = "pat",
    val rawToken: String = "",
    val clientId: String? = null,
    val clientSecret: String? = null,
    val audience: String = "manna-mcp",
    val scopes: List<String> = emptyList(),
    val subjectToken: String? = null,
    val ttlSeconds: Int = 15 * 60,
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD
): Request()

@JsExport
@Serializable
data class TokenResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Int = 0,
    val tokenId: String? = null,
    val scopes: List<String> = emptyList(),
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): Response()

@JsExport
abstract class AuthService(baseUrl: String = ""): Service(baseUrl) {
    abstract val login: PostHandler<LoginRequest, LoginResponse>
    abstract val token: PostHandler<TokenRequest, TokenResponse>
    abstract val mintPat: PostHandler<MintPatRequest, MintPatResponse>
    abstract val verifyPat: PostHandler<VerifyPatRequest, VerifyPatResponse>
    abstract val exchangePat: PostHandler<ExchangePatRequest, ExchangePatResponse>
}

@JsExport
open class AuthServiceClient(baseUrl: String): AuthService(baseUrl) {
    override val login = PostHandler<LoginRequest, LoginResponse>("/auth/sign-in") {
        http.Post(route) { setBody(it) }
            .fold(
                { it.body() },
                { LoginResponse.Failure.ServerError("Unknown Error") }
            )
    }

    override val mintPat = PostHandler<MintPatRequest, MintPatResponse>("/auth/pat/mint") {
        http.Post(route) { setBody(it) }.fold({ it.body() }, { MintPatResponse("") })
    }

    override val token = PostHandler<TokenRequest, TokenResponse>("/auth/token") {
        http.Post(route) { setBody(it) }.fold({ it.body() }, { TokenResponse("") })
    }

    override val verifyPat = PostHandler<VerifyPatRequest, VerifyPatResponse>("/auth/pat/verify") {
        http.Post(route) { setBody(it) }.fold({ it.body() }, { VerifyPatResponse(false) })
    }

    override val exchangePat = PostHandler<ExchangePatRequest, ExchangePatResponse>("/auth/pat/exchange") {
        http.Post(route) { setBody(it) }.fold({ it.body() }, { ExchangePatResponse("") })
    }
}
