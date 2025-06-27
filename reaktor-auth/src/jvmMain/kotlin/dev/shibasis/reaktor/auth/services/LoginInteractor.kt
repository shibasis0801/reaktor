package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.User
import dev.shibasis.reaktor.auth.UserStatus
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.auth.db.UserRepository
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import dev.shibasis.reaktor.core.framework.json
import dev.shibasis.reaktor.core.utils.info
import dev.shibasis.reaktor.core.utils.invoke
import dev.shibasis.reaktor.core.utils.logger
import dev.shibasis.reaktor.core.utils.read
import org.springframework.stereotype.Component
import java.util.UUID

inline fun String.uuid(): UUID = UUID.fromString(this)

/* todo: if a user exists for another app, copy their data here */
@Component
open class LoginInteractor(
    private val userRepository: UserRepository,
    private val appRepository: AppRepository,
    private val profileRepository: BaseProfileRepository<out BaseProfile>,
    private val jwtVerifier: JwtVerifier
) {
    private val logger = "Reaktor:LoginService".logger()

    open suspend fun login(request: LoginRequest): LoginResponse {
        logger { request }

        val appId = request.appId.uuid()

        val appResult = appRepository.findById(request, appId)

        val app = appResult.read()
            ?: return LoginResponse.Failure.InvalidAppId

        logger { app }

        val authenticated = jwtVerifier(request).read()
            ?: return LoginResponse.Failure.InvalidIdToken

        logger.info { authenticated }

        val subject = authenticated.subject
        val provider = request.provider
        val userName = request.userName

        val userResult = userRepository
            .findByAppIdAndProvider(request, appId,subject, provider)
            .onFailure {
                userRepository.upsert(request, User(
                    id = UUID.randomUUID().toString(),
                    name = userName,
                    socialId = subject,
                    appId = appId.toString(),
                    provider = provider,
                    status = UserStatus.ONBOARDING,
                    data = request.newUserProfile ?: EMPTY_JSON
                ))
            }

        val user = userResult.read()
            ?: return LoginResponse.Failure.ServerError("Unable to read/create user")

        logger { user }

        var profileResult = profileRepository.fetch(request, user.id)

        if (profileResult.isFailure) {
            request.newUserProfile ?: return LoginResponse.Failure.RequiresUserProfile
            profileResult = profileRepository.create(request, user.id)
        }


        val profile = profileResult.read()
            ?: return LoginResponse.Failure.ServerError("Unable to read/create profile")

        logger { profile }
        return LoginResponse.Success(user, profile.toJson())
    }
}
