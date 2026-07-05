package dev.shibasis.reaktor.auth.runtime

import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.api.AppService
import dev.shibasis.reaktor.auth.api.AnonymousAuthRequest
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.auth.api.LoginResponse
import dev.shibasis.reaktor.auth.api.LogoutAllRequest
import dev.shibasis.reaktor.auth.api.LogoutAllResponse
import dev.shibasis.reaktor.auth.api.LogoutRequest
import dev.shibasis.reaktor.auth.api.LogoutResponse
import dev.shibasis.reaktor.auth.api.MeRequest
import dev.shibasis.reaktor.auth.api.MeResponse
import dev.shibasis.reaktor.auth.api.MintPatRequest
import dev.shibasis.reaktor.auth.api.MintPatResponse
import dev.shibasis.reaktor.auth.api.RefreshRequest
import dev.shibasis.reaktor.auth.api.RefreshResponse
import dev.shibasis.reaktor.auth.api.TokenRequest
import dev.shibasis.reaktor.auth.api.TokenResponse
import dev.shibasis.reaktor.auth.api.VerifyPatRequest
import dev.shibasis.reaktor.auth.api.VerifyPatResponse

interface AuthLogin {
    suspend fun anonymous(request: AnonymousAuthRequest): LoginResponse
    suspend fun login(request: LoginRequest): LoginResponse
}

interface AuthPat {
    suspend fun mint(request: MintPatRequest): MintPatResponse
    suspend fun verify(request: VerifyPatRequest): VerifyPatResponse
}

interface AuthTokenGrants {
    suspend fun issue(request: TokenRequest): TokenResponse
}

interface AuthSessions {
    suspend fun refresh(request: RefreshRequest): RefreshResponse
    suspend fun logout(request: LogoutRequest): LogoutResponse
    suspend fun me(request: MeRequest): MeResponse
    suspend fun logoutAll(request: LogoutAllRequest): LogoutAllResponse
}

interface AuthHttpService {
    val service: AuthService
}

interface AuthAppService {
    val service: AppService
}
