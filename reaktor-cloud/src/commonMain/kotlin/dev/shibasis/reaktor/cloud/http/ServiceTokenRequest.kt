package dev.shibasis.reaktor.cloud.http

interface ServiceTokenRequest {
    val tokenEndpoint: String
    val audience: String
    val authEnvironment: String
    val credentialFile: String?
    val clientIdKey: String
    val clientSecretKey: String
    val scopes: List<String>
}
