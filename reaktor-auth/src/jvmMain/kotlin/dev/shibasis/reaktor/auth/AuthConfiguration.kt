package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AppRouter
import org.jetbrains.exposed.sql.Database
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

data class GoogleOAuthConfig(
    val clientId: String
)


//@Configuration
//@ConditionalOnBean(value = [GoogleOAuthConfig::class, Database::class])
//class AuthConfiguration {
//
//}