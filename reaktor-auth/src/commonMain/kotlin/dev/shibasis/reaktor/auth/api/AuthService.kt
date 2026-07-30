package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.AuthContextSnapshot
import dev.shibasis.reaktor.auth.AuthGrantType
import dev.shibasis.reaktor.auth.UserProvider
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.PostHandler
import dev.shibasis.reaktor.service.Service
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.js.ExperimentalJsStatic
import kotlin.js.JsExport
import kotlin.js.JsStatic

@JsExport
@Serializable
@Suppress("NON_EXPORTABLE_TYPE")
data class AnonymousAuthRequest(
    val appId: String,
    val tenantHint: String? = null,
    val contextHint: String? = null,
    val profile: JsonElement = JsonObject(emptyMap()),
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment
): Request() {
    companion object {
        @OptIn(ExperimentalJsStatic::class)
        @JsStatic
        operator fun invoke(appId: String, environment: Environment) =
            AnonymousAuthRequest(appId = appId, environment = environment)
    }
}

@JsExport
@Serializable
@Suppress("NON_EXPORTABLE_TYPE")
data class LoginRequest(
    val idToken: String,
    val appId: String,
    val provider: UserProvider = UserProvider.GOOGLE,
    val nonce: String? = null,
    val state: String? = null,
    val tenantHint: String? = null,
    val contextHint: String? = null,
    val givenName: String? = null,
    val familyName: String? = null,
    val profile: JsonElement = JsonObject(emptyMap()),
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment
): Request() {
    companion object {
        @OptIn(ExperimentalJsStatic::class)
        @JsStatic
        operator fun invoke(idToken: String, appId: String, provider: UserProvider = UserProvider.GOOGLE, environment: Environment) =
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
    @Suppress("NON_EXPORTABLE_TYPE")
    data class Success(
        val context: AuthContextSnapshot,
        val profile: JsonElement,
        val tokenSet: TokenSet
    ): LoginResponse(StatusCode.OK)

    @Serializable
    sealed class Failure(private val failureStatus: StatusCode): LoginResponse(failureStatus) {
        @Serializable
        data object InvalidIdToken: Failure(StatusCode.BAD_REQUEST)

        @Serializable
        data object InvalidAppId: Failure(StatusCode.BAD_REQUEST)

        @Serializable
        data object UnsupportedUserProvider: Failure(StatusCode.BAD_REQUEST)

        @Serializable
        data object RequiresUserProfile: Failure(StatusCode.NOT_FOUND)

        @Serializable
        data object PrincipalUnavailable: Failure(StatusCode.FORBIDDEN)

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
    val principalId: String? = null,
    val appId: String? = null,
    val allowedAudiences: List<String> = listOf("manna-mcp"),
    val expiresInDays: Int? = null,
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
data class TokenRequest(
    @SerialName("grant_type")
    val grantType: String = AuthGrantType.PAT.wireName,
    val rawToken: String = "",
    val clientId: String? = null,
    val clientSecret: String? = null,
    val audience: String = "manna-mcp",
    val scopes: List<String> = emptyList(),
    // RFC 8693 token-exchange (delegation): subject = the user being acted for, actor = the caller.
    val subjectToken: String? = null,
    @SerialName("subject_token_type")
    val subjectTokenType: String? = null,
    @SerialName("actor_token")
    val actorToken: String? = null,
    @SerialName("actor_token_type")
    val actorTokenType: String? = null,
    @SerialName("requested_token_type")
    val requestedTokenType: String? = null,
    // RFC 7523 private_key_jwt client authentication (preferred over client_secret for service accounts).
    @SerialName("client_assertion")
    val clientAssertion: String? = null,
    @SerialName("client_assertion_type")
    val clientAssertionType: String? = null,
    val contextId: String? = null,
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
    val audience: String? = null,
    val contextId: String? = null,
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): Response()

@JsExport
@Serializable
data class RefreshRequest(
    val refreshToken: String = "",
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD
): Request() {
    companion object {
        @OptIn(ExperimentalJsStatic::class)
        @JsStatic
        fun Create(refreshToken: String, environment: Environment) =
            RefreshRequest(refreshToken, environment = environment)
    }
}

@JsExport
@Serializable
data class RefreshResponse(
    val tokenSet: TokenSet? = null,
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): Response()

@JsExport
@Serializable
data class LogoutRequest(
    val refreshToken: String = "",
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD
): Request() {
    companion object {
        @OptIn(ExperimentalJsStatic::class)
        @JsStatic
        fun Create(refreshToken: String, environment: Environment) =
            LogoutRequest(refreshToken, environment = environment)
    }
}

@JsExport
@Serializable
data class LogoutResponse(
    val success: Boolean = true,
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): Response()

@JsExport
@Serializable
data class MeRequest(
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD
): Request()

@JsExport
@Serializable
data class MeResponse(
    val principalId: String? = null,
    val principalKind: String? = null,
    val appId: String? = null,
    val audience: String? = null,
    val sessionId: String? = null,
    val scopes: List<String> = emptyList(),
    val roles: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
    val actorId: String? = null,
    val context: AuthContextSnapshot? = null,
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): Response()

@JsExport
@Serializable
data class LogoutAllRequest(
    val audience: String = "",
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD
): Request()

@JsExport
@Serializable
data class LogoutAllResponse(
    val revokedSessions: Int = 0,
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): Response()

@JsExport
@Serializable
data class DeactivateAccountRequest(
    val audience: String = "",
    override val headers: MutableMap<String, String> = mutableMapOf(),
    override val queryParams: MutableMap<String, String> = mutableMapOf(),
    override val pathParams: MutableMap<String, String> = mutableMapOf(),
    override var environment: Environment = Environment.PROD
): Request()

@JsExport
@Serializable
data class DeactivateAccountResponse(
    val deactivated: Boolean = false,
    override var statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf()
): Response()

@JsExport
abstract class AuthService(baseUrl: String = ""): Service(baseUrl) {
    abstract val anonymous: PostHandler<AnonymousAuthRequest, LoginResponse>
    abstract val login: PostHandler<LoginRequest, LoginResponse>
    abstract val token: PostHandler<TokenRequest, TokenResponse>
    abstract val mintPat: PostHandler<MintPatRequest, MintPatResponse>
    abstract val verifyPat: PostHandler<VerifyPatRequest, VerifyPatResponse>
    abstract val sessionRefresh: PostHandler<RefreshRequest, RefreshResponse>
    abstract val sessionLogout: PostHandler<LogoutRequest, LogoutResponse>
    abstract val sessionMe: PostHandler<MeRequest, MeResponse>
    abstract val sessionLogoutAll: PostHandler<LogoutAllRequest, LogoutAllResponse>
    abstract val accountDeactivate: PostHandler<DeactivateAccountRequest, DeactivateAccountResponse>
}

@JsExport
open class AuthServiceClient(baseUrl: String): AuthService(baseUrl) {
    override val anonymous = PostHandler<AnonymousAuthRequest, LoginResponse>("/auth/anonymous")
    override val login = PostHandler<LoginRequest, LoginResponse>("/auth/sign-in")
    override val mintPat = PostHandler<MintPatRequest, MintPatResponse>("/auth/pat/mint")
    override val token = PostHandler<TokenRequest, TokenResponse>("/auth/token")
    override val verifyPat = PostHandler<VerifyPatRequest, VerifyPatResponse>("/auth/pat/verify")
    override val sessionRefresh = PostHandler<RefreshRequest, RefreshResponse>("/auth/session/refresh")
    override val sessionLogout = PostHandler<LogoutRequest, LogoutResponse>("/auth/session/logout")
    override val sessionMe = PostHandler<MeRequest, MeResponse>("/auth/session/me")
    override val sessionLogoutAll = PostHandler<LogoutAllRequest, LogoutAllResponse>("/auth/session/logout-all")
    override val accountDeactivate = PostHandler<DeactivateAccountRequest, DeactivateAccountResponse>("/auth/account/deactivate")
}
