package dev.shibasis.reaktor.auth.services

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.db.Apps
import dev.shibasis.reaktor.auth.api.SignInRequest
import dev.shibasis.reaktor.auth.api.SignInResponse
import dev.shibasis.reaktor.auth.jwt.TokenVerifierService
import dev.shibasis.reaktor.auth.db.apps.AppRepository
import dev.shibasis.reaktor.auth.db.users.UserRepository
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.Database
import org.springframework.stereotype.Component

/*
todo:
    if a user exists for another app, copy their data here.
 */
@Component
class LoginService(
    private val verifierService: TokenVerifierService,
    private val userRepository: UserRepository,
    private val appRepository: AppRepository
) {


    fun login(request: SignInRequest): SignInResponse {
        val (idToken, appId) = request

        val appResult = appRepository.find(appId)
        if (appResult.isFailure)
            return SignInResponse.Failure.InvalidAppId

        Logger.i { appResult.getOrNull()!!.toString() }

        val payload = verifierService.verify(idToken)
        if (payload == null)
            return SignInResponse.Failure.InvalidGoogleIdToken

        Logger.i { payload.toPrettyString() }
        payload.forEach { key, value ->
            Logger.i { "$key: $value" }
        }

        val socialId = payload.subject

        val user = userRepository.getUser(appId, socialId).getOrNull()
        if (user == null) {
            val user = userRepository.create {
                it.name = payload["name"].toString()
                it.socialId = payload.subject
                it.appId = EntityID(appId, Apps)
                it.data = JsonObject(mapOf())
            }

            if (user.isSuccess)
                return SignInResponse.Failure.RequiresSignUp(socialId, user.getOrThrow().toDto())
            else
                return SignInResponse.Failure.ServerError("Could not store user. ")
        }


        Logger.i { user.toString() }

        return SignInResponse.Success(socialId, user.toDto())
    }
}