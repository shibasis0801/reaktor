package dev.shibasis.reaktor.auth.spring

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.config.web.server.invoke
import org.springframework.security.config.web.server.SecurityWebFiltersOrder
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository

/**
 * Route-aware auth boundary. Previously this permitted ALL exchanges (no boundary). Now:
 *   - a small public allowlist (login + token-exchange + JWKS) is open,
 *   - everything else requires authentication,
 *   - [ReaktorSecurityWebFilter] (installed at the AUTHENTICATION position) verifies the Reaktor
 *     ES256 token locally and populates the reactive security context.
 *
 * Fine-grained authorization (audience / scope / resource / delegation) is enforced per-handler by
 * the kernel `LocalAuthorizer` against each route's `AuthRequirement`. See the auth-analysis
 * "HTTP Surface" and "Spring Boundary" sections.
 */
@Configuration
@EnableWebFluxSecurity
open class DefaultSecurityConfig(
    private val reaktorSecurityWebFilter: ReaktorSecurityWebFilter,
) {
    @Bean
    @ConditionalOnMissingBean
    open fun springSecurityFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain {
        http.addFilterAt(reaktorSecurityWebFilter, SecurityWebFiltersOrder.AUTHENTICATION)
        return http {
            authorizeExchange {
                PublicPaths.forEach { authorize(it, permitAll) }
                authorize(anyExchange, authenticated)
            }
            csrf { disable() }
            securityContextRepository = NoOpServerSecurityContextRepository.getInstance()
        }
    }

    companion object {
        val PublicPaths = listOf(
            "/", "/status", "/auth/anonymous", "/auth/sign-in", "/auth/login/**", "/auth/magic-link/**", "/auth/session/refresh",
            "/auth/session/logout", "/auth/token", "/auth/pat/mint", "/auth/pat/verify", "/_graph/**", "/_reaktor/**",
            "/connect/*/callback", "/.well-known/jwks.json", "/actuator/health/**",
        )
    }
}
