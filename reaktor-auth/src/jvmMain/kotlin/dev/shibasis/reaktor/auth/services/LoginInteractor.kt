package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.User
import dev.shibasis.reaktor.auth.UserStatus
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.auth.db.UserRepository
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
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
    private val jwtVerifier: JwtVerifier,
    private val jwtMinter: dev.shibasis.reaktor.auth.jwt.JwtMinter
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


        val existingUser = userRepository
            .findByAppIdAndProvider(request, appId,subject, provider)
            .getOrNull()

        val user = if (existingUser == null) {
            // Apple does not send name in JWT, so server needs to be very reliable. todo add queue here
            if (request.givenName == null || request.familyName == null) {
                return LoginResponse.Failure.RequiresUserName
            }
            val userName = request.givenName + " " + request.familyName
            userRepository.upsert(request, User(
                id = UUID.randomUUID().toString(),
                name = userName,
                socialId = subject,
                appId = appId.toString(),
                provider = provider,
                status = UserStatus.ONBOARDING,
                data = request.newUserProfile ?: EMPTY_JSON
            )).getOrElse {
                return LoginResponse.Failure.ServerError(it.message ?: "Unable to create user")
            }

            userRepository
                .findByAppIdAndProvider(request, appId, subject, provider)
                .getOrElse {
                    return LoginResponse.Failure.ServerError(it.message ?: "Unable to read/create user")
                }
                ?: return LoginResponse.Failure.ServerError("Unable to read/create user")
        } else {
            existingUser
        }

        logger { user }

        val permissions = userRepository.getUserPermissions(request, user.id.uuid(), appId).getOrElse { listOf("user") }
        val accessToken = jwtMinter.mintAccessToken(
            userId = user.id,
            appId = user.appId,
            scopes = permissions
        )

        val refreshToken = UUID.randomUUID().toString()
        
        return LoginResponse.Success(user, user.data, accessToken, refreshToken)
    }
}
