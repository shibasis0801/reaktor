package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.GoogleOAuthConfig
import dev.shibasis.reaktor.auth.framework.Router
import dev.shibasis.reaktor.auth.services.LoginService
import org.jetbrains.exposed.sql.Database
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.coRouter

@Component
class AuthRouter(private val loginService: LoginService): Router() {
    override fun router() = coRouter {
        POST("/sign-in") { request ->
            val body = request.awaitBody<SignInRequest>()

            val response = loginService.login(body)
            val status = when(response) {
                is SignInResponse.Failure.RequiresSignUp -> HttpStatus.FOUND
                is SignInResponse.Failure -> HttpStatus.BAD_REQUEST
                else -> HttpStatus.OK
            }

            dev.shibasis.reaktor.auth.api.jsonResponse(response, status)
        }
    }
}