package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AuthServiceClient
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.io.network.promised
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise


class AuthServiceJs(baseUrl: String): AuthServiceClient(baseUrl) {
    fun login(request: LoginRequest) = promised(login, request)
}