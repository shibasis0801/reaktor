package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.jwt.TokenVerifierService
import dev.shibasis.reaktor.auth.repositories.AppRepository
import dev.shibasis.reaktor.auth.repositories.UserRepository
import dev.shibasis.reaktor.auth.services.LoginService
import org.jetbrains.exposed.sql.Database
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.awaitBody
import org.springframework.web.reactive.function.server.bodyValueAndAwait
import org.springframework.web.reactive.function.server.coRouter

fun signInApi(
    database: Database,
    clientId: String
) = coRouter {
    val verifierService = TokenVerifierService(clientId)
    val userRepository = UserRepository(database)
    val appRepository = AppRepository(database)
    val loginService = LoginService(userRepository, appRepository, verifierService)



//    GET("/sign-in") { request ->
//        val request = request.awaitBody<SignInRequest>()
//
//        ServerResponse.ok().bodyValueAndAwait()
//    }
}