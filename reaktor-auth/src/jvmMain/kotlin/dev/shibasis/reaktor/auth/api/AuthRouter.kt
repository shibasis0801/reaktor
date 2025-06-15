package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.framework.Router
import dev.shibasis.reaktor.auth.framework.jsonResponse
import dev.shibasis.reaktor.auth.framework.toHttpStatus
import dev.shibasis.reaktor.auth.services.LoginService
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.io.service.BaseRequest
import dev.shibasis.reaktor.io.service.BaseResponse
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.ServerRequest
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

fun ServerRequest.toBaseRequest() = BaseRequest.new(
    headers = headers().asHttpHeaders().asSingleValueMap(),
    queryParams = queryParams().asSingleValueMap(),
    pathParams = pathVariables()
)

suspend inline fun<reified Response: BaseResponse> toServerResponse(response: Response) = ServerResponse
    .status(response.statusCode.toHttpStatus())
    .contentType(MediaType.APPLICATION_JSON)
    .headers { response.headers.forEach { (k, v) -> it[k] = v } }
    .bodyValueAndAwait(json.encodeToString<Response>(response))



@Component
class AuthServer(private val loginService: LoginService): AuthService() {
    override val signIn = PostHandler("/sign-in") {
        loginService.login(it)
    }
}


@Component
class AuthRouter(private val authServer: AuthServer): Router() {
    override fun router() = coRouter {
        attach(authServer.signIn)
    }
}
