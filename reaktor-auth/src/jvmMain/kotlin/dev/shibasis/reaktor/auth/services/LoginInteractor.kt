package dev.shibasis.reaktor.auth.services

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.UserEntity
import dev.shibasis.reaktor.auth.UserStatus
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.auth.db.UserRepository
import dev.shibasis.reaktor.auth.db.invoke
import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.utils.toDto
import dev.shibasis.reaktor.core.utils.combine
import dev.shibasis.reaktor.core.utils.onFailure
import dev.shibasis.reaktor.core.utils.read
import dev.shibasis.reaktor.core.utils.succeed
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.graalvm.compiler.lir.profiling.MoveProfiler.profile
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
    private val profileRepository: BaseProfileRepository<out BaseProfileEntity>,
    private val jwtVerifier: JwtVerifier
) {
    private val TAG = "Reaktor:LoginService"

    private suspend fun findApp(request: LoginRequest) = appRepository {
        val appId = UUID.fromString(request.appId)
        findById(appId)
    }.getOrNull()


    @Transactional
    open suspend fun login(request: LoginRequest): LoginResponse {
        val app = findApp(request)
            ?: return LoginResponse.Failure.InvalidAppId

        Logger.i(TAG) { app.toString() }

        val authenticated = jwtVerifier(request).read()
            ?: return LoginResponse.Failure.InvalidIdToken

        val subject = authenticated.subject
        val provider = request.provider
        val userName = request.userName

        val userResult = userRepository {
            findBySocialIdAndAppId(subject, app.id, provider)
        }.onFailure {
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

        val profileResult = profileRepository {
            fetch(user.id)
        }.onFailure {
            val profileData = request.newUserProfile ?: return LoginResponse.Failure.RequiresUserProfile
            profileRepository { create(user.id, profileData) }
        }

        val profile = profileResult.read()
            ?: return LoginResponse.Failure.ServerError("Unable to read/create profile")

        return LoginResponse.Success(user.toDto(), profile)
    }
}
