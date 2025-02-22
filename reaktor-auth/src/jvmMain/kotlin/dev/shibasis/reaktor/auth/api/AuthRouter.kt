package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.framework.Router
import dev.shibasis.reaktor.auth.framework.jsonResponse
import dev.shibasis.reaktor.auth.services.LoginService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.coRouter

@Component
class AuthRouter(private val loginService: LoginService): Router() {
    override fun router() = coRouter {
        POST("/sign-in") { request ->
            val body = request.awaitBody<SignInRequest>()

            val response = loginService.login(body)
            val status = if (response is SignInResponse.Failure)
                HttpStatus.BAD_REQUEST
            else HttpStatus.OK

            jsonResponse(response, status)
        }
    }
}