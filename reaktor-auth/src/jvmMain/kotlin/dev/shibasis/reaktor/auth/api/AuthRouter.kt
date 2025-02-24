package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.framework.Router
import dev.shibasis.reaktor.auth.framework.jsonResponse
import dev.shibasis.reaktor.auth.framework.toHttpStatus
import dev.shibasis.reaktor.auth.services.LoginService
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.coRouter

@Component
class AuthServer(private val loginService: LoginService): AuthService() {
    override val signIn = PostHandler("/sign-in") {
        loginService.login(it)
    }
}

@Component
class AuthRouter(private val authService: AuthService): Router() {
    private val signIn = authService.signIn

    override fun router() = coRouter {
        POST(signIn.route) { request ->
            signIn(request.awaitBody<SignInRequest>())
                .run {
                    jsonResponse(this, statusCode.toHttpStatus())
                }
        }
    }
}