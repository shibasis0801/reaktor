package dev.shibasis.reaktor.auth.services

import dev.shibasis.reaktor.auth.api.SignInRequest
import dev.shibasis.reaktor.auth.api.SignInResponse
import dev.shibasis.reaktor.auth.db.AppRepository
import dev.shibasis.reaktor.auth.db.UserRepository
import dev.shibasis.reaktor.auth.jwt.TokenVerifierService
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
    // wrap in transactional, etc
    suspend fun login(request: SignInRequest): SignInResponse {
        return SignInResponse.Failure.InvalidAppId
//        val (idToken) = request
//        val appId = UUID.fromString(request.appId)
//
//        val appResult = appRepository { findById(appId) }
//        if (appResult.isFailure)
//            return SignInResponse.Failure.InvalidAppId
//
//        Logger.i { appResult.getOrNull()!!.toString() }
//
//        val payload = verifierService.verify(idToken)
//            ?: return SignInResponse.Failure.InvalidGoogleIdToken
//
//        Logger.i { payload.toPrettyString() }
//        payload.forEach { (key, value) ->
//            Logger.i { "$key: $value" }
//        }
//
//        val socialId = payload.subject
//
//        var user = userRepository { findBySocialIdAndAppId(socialId, appId) }
//        if (user.isFailure) {
//            user = userRepository {
//                save(
//                    UserEntity(
//                        UUID.randomUUID(),
//                        payload["name"].toString(),
//                        payload.subject,
//                        appId,
//                        UserProvider.GOOGLE,
//                        UserStatus.ONBOARDING
//                    )
//                )
//            }
//            profileService.createProfile(user.getOrThrow().id, JsonObject(mapOf()))
//        }
//
//        var profile = profileService.fetchProfile(user.getOrNull()!!.id)
//
//        return user.fold(
//            {
//                SignInResponse.Failure.ServerError("profile transaction needed")
//                SignInResponse.Success(it.toDto(), profile)
//            },
//            { SignInResponse.Failure.ServerError("Could not store user. ") }
//        )
    }
}
