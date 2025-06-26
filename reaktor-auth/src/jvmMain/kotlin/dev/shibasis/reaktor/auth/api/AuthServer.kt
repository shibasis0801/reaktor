package dev.shibasis.reaktor.auth.api

import dev.shibasis.reaktor.auth.services.LoginInteractor
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.ReactiveUserDetailsService
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository
import org.springframework.stereotype.Component


@Component
class AuthServer(private val loginInteractor: LoginInteractor): AuthService() {
    override val signIn = PostHandler("/sign-in") {
        loginInteractor.login(it)
    }
}

@Configuration
@EnableWebFluxSecurity
open class WebfluxSecurityConfig {
    @Bean
    @ConditionalOnMissingBean
    open fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        return http {
            authorizeExchange {
                authorize(anyExchange, permitAll)
            }
            csrf { disable() }
            securityContextRepository = NoOpServerSecurityContextRepository.getInstance()
        }
    }
}
