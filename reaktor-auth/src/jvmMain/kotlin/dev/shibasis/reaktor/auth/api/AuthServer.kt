package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.services.LoginInteractor
import dev.shibasis.reaktor.auth.services.TokenInteractor
import dev.shibasis.reaktor.graph.service.PostHandler
import org.springframework.stereotype.Component

@Component
class AuthServer(
    private val loginInteractor: LoginInteractor,
    private val tokenInteractor: TokenInteractor
): AuthService() {
    override val login = PostHandler("/sign-in") {
        loginInteractor.login(it)
    }

    override val mintPat = PostHandler<MintPatRequest, MintPatResponse>("/test-mint-pat") { req ->
        val name = req.name
        val scopes = listOf("test:read", "test:write")
        val (pat, rawToken) = tokenInteractor.createPersonalAccessToken(null, name, scopes)
        MintPatResponse(rawToken) // Return the raw rak_... token to the client
    }

    override val verifyPat = PostHandler<VerifyPatRequest, VerifyPatResponse>("/test-verify-pat") { req ->
        val rawToken = req.rawToken
        val pat = tokenInteractor.verifyPersonalAccessToken(rawToken)
        VerifyPatResponse(pat != null) 
    }
}
