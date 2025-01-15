package dev.shibasis.reaktor.auth.services

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.api.SignInRequest
import dev.shibasis.reaktor.auth.api.SignInResponse
import dev.shibasis.reaktor.auth.apps.jsonResponse
import dev.shibasis.reaktor.auth.jwt.TokenVerifierService
import dev.shibasis.reaktor.auth.repositories.AppRepository
import dev.shibasis.reaktor.auth.repositories.UserRepository
import org.jetbrains.exposed.sql.Database
import org.springframework.http.HttpStatus

class LoginService(
    database: Database,
    clientId: String
) {
    private val verifierService = TokenVerifierService(clientId)
    private val userRepository = UserRepository(database)
    private val appRepository = AppRepository(database)

    fun login(request: SignInRequest): SignInResponse {
        val (googleIdToken, appId) = request

        val appResult = appRepository.getApp(appId)
        if (appResult.isFailure)
            return SignInResponse.Failure.InvalidAppId

        Logger.i { appResult.getOrNull()!!.toString() }

        val payload = verifierService.verify(googleIdToken)
        if (payload == null)
            return SignInResponse.Failure.InvalidGoogleIdToken

        Logger.i { payload.toPrettyString() }

        val socialId = payload.subject

        val user = userRepository.getUser(appId, socialId).getOrNull()
        if (user == null)
            return SignInResponse.Failure.RequiresSignUp(socialId)

        Logger.i { user.toString() }

        return SignInResponse.Success(socialId)
    }
}