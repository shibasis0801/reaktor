package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.UserEntity
import dev.shibasis.reaktor.auth.UserStatus
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.auth.db.UserRepository
import dev.shibasis.reaktor.auth.db.invoke
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.utils.toDto
import dev.shibasis.reaktor.auth.utils.uuid
import dev.shibasis.reaktor.core.utils.info
import dev.shibasis.reaktor.core.utils.invoke
import dev.shibasis.reaktor.core.utils.logger
import dev.shibasis.reaktor.core.utils.onFailure
import dev.shibasis.reaktor.core.utils.read
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID


/*
todo:
    if a user exists for another app, copy their data here.
 */
@Component
open class LoginInteractor(
    private val userRepository: UserRepository,
    private val appRepository: AppRepository,
    private val profileInteractor: BaseProfileInteractor<out BaseProfileEntity>,
    private val jwtVerifier: JwtVerifier
) {
    private val logger = "Reaktor:LoginService".logger()

//    @Transactional
    open suspend fun login(request: LoginRequest): LoginResponse {
        logger { request }

        val appResult = appRepository {
            findById(request.appId.uuid())
        }

        val app = appResult.read()
            ?: return LoginResponse.Failure.InvalidAppId

        logger { app }

        val authenticated = jwtVerifier(request).read()
            ?: return LoginResponse.Failure.InvalidIdToken

        logger.info { authenticated }

        val subject = authenticated.subject
        val provider = request.provider
        val userName = request.userName

        val userResult = userRepository {
            findBySocialIdAndAppIdAndProvider(subject, app.id, provider)
        }


        userResult.onFailure {
            userRepository {
                save(
                    UserEntity(
                        UUID.randomUUID(),
                        userName,
                        subject,
                        app.id,
                        provider,
                        UserStatus.ONBOARDING
                    )
                )
            }
        }

        val user = userResult.read()
            ?: return LoginResponse.Failure.ServerError("Unable to read/create user")

        logger { user }

        val profileResult = profileInteractor.fetch(user.id)
            .onFailure {
                val profileData = request.newUserProfile ?: return LoginResponse.Failure.RequiresUserProfile
                profileInteractor.create(user.id, profileData)
            }

        val profile = profileResult.read()
            ?: return LoginResponse.Failure.ServerError("Unable to read/create profile")

        logger { profile }
        return LoginResponse.Success(user.toDto(), profile.toJson())
    }
}
