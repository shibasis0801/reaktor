package dev.shibasis.reaktor.auth.services

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.UserStatus
import dev.shibasis.reaktor.auth.db.Apps
import dev.shibasis.reaktor.auth.api.SignInRequest
import dev.shibasis.reaktor.auth.api.SignInResponse
import dev.shibasis.reaktor.auth.jwt.TokenVerifierService
import dev.shibasis.reaktor.auth.db.apps.AppRepository
import dev.shibasis.reaktor.auth.db.users.UserRepository
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.springframework.stereotype.Component
import java.util.UUID

/*
todo:
    if a user exists for another app, copy their data here.
 */
@Component
class LoginService(
    private val verifierService: TokenVerifierService,
    private val userRepository: UserRepository,
    private val appRepository: AppRepository,
    private val profileService: ProfileService
) {
    // wrap in transactional, etc
    suspend fun login(request: SignInRequest): SignInResponse {
        val (idToken) = request
        val appId = UUID.fromString(request.appId)

        val appResult = appRepository.find(appId)
        if (appResult.isFailure)
            return SignInResponse.Failure.InvalidAppId

        Logger.i { appResult.getOrNull()!!.toString() }

        val payload = verifierService.verify(idToken)
            ?: return SignInResponse.Failure.InvalidGoogleIdToken

        Logger.i { payload.toPrettyString() }
        payload.forEach { key, value ->
            Logger.i { "$key: $value" }
        }

        val socialId = payload.subject

        var user = userRepository.getUser(appId, socialId)
        if (user.isFailure) {
            user = userRepository.create {
                it.name = payload["name"].toString()
                it.socialId = payload.subject
                it.appId = EntityID(appId, Apps)
                it.data = JsonObject(mapOf())
                it.status = UserStatus.ONBOARDING
            }
            profileService.createProfile(user.getOrThrow().id.value, JsonObject(mapOf()))
        }

        var profile = profileService.fetchProfile(user.getOrThrow().id.value)

        return user.fold(
            { SignInResponse.Success(it.toDto(), profile) },
            { SignInResponse.Failure.ServerError("Could not store user. ") }
        )
    }
}