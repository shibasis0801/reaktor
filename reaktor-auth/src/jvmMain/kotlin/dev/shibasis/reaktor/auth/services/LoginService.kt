package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.api.SignInRequest
import dev.shibasis.reaktor.auth.api.SignInResponse
import dev.shibasis.reaktor.auth.jwt.TokenVerifierService
import dev.shibasis.reaktor.auth.repositories.AppRepository
import dev.shibasis.reaktor.auth.repositories.UserRepository

// todo probably need koin after all
class LoginService(
    private val userRepository: UserRepository,
    private val appRepository: AppRepository,
    private val verifierService: TokenVerifierService
) {
    fun login(request: SignInRequest): SignInResponse {
        val payload = verifierService.verify(request.googleIdToken)
            ?: return SignInResponse.Failure.InvalidGoogleIdToken

        val app = appRepository.getApp(request.appId)
        app.getOrNull() ?: return SignInResponse.Failure.InvalidAppId

        val userResult = userRepository.getUser(request.appId, request.googleIdToken)

        val user = userResult.getOrNull() ?: return SignInResponse.Failure.RequiresSignUp

        return SignInResponse.Success("success")
    }
}