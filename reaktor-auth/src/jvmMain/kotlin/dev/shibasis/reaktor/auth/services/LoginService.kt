package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.api.SignInRequest
import dev.shibasis.reaktor.auth.api.SignInResponse
import dev.shibasis.reaktor.auth.apps.jsonResponse
import dev.shibasis.reaktor.auth.jwt.TokenVerifierService
import dev.shibasis.reaktor.auth.repositories.AppRepository
import dev.shibasis.reaktor.auth.repositories.UserRepository
import org.jetbrains.exposed.sql.Database
import org.springframework.http.HttpStatus

// todo probably need koin after all
class LoginService(
    database: Database,
    clientId: String
) {
    private val verifierService = TokenVerifierService(clientId)
    private val userRepository = UserRepository(database)
    private val appRepository = AppRepository(database)

    fun login(request: SignInRequest): SignInResponse {
        val (idToken, appId) = request

        val appResult = appRepository.getApp(appId)
        if (appResult.isFailure)
            return SignInResponse.Failure.InvalidAppId
        else
            println(appResult.getOrNull()!!)

        val payload = verifierService.verify(idToken)
        if (payload == null)
            return SignInResponse.Failure.InvalidGoogleIdToken
        else
            println(payload)

        val user = userRepository.getUser(appId, payload.subject).getOrNull()
        if (user == null)
            return SignInResponse.Failure.RequiresSignUp
        else
            println(user)

        return SignInResponse.Success(idToken)
    }
}