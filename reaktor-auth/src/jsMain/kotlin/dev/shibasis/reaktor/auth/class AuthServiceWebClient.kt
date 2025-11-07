package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AuthServiceClient
import dev.shibasis.reaktor.auth.api.LoginRequest
import dev.shibasis.reaktor.graph.service.promised


class AuthServiceJs(baseUrl: String): AuthServiceClient(baseUrl) {
    fun login(request: LoginRequest) = promised(login, request)
}