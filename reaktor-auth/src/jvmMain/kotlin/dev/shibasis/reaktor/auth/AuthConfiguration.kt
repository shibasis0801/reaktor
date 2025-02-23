package dev.shibasis.reaktor.auth

data class GoogleOAuthConfig(
    val clients: List<String>
)


//@Configuration
//@ConditionalOnBean(value = [GoogleOAuthConfig::class, Database::class])
//class AuthConfiguration {
//
//}