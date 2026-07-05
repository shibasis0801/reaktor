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
                // Public: health/status must be open for infrastructure probes; credential
                // exchange happens via the grant itself, and JWKS must be open.
                authorize("/", permitAll)
                authorize("/status", permitAll)
                authorize("/auth/anonymous", permitAll)
                authorize("/auth/sign-in", permitAll)
                authorize("/auth/login/**", permitAll)
                authorize("/auth/magic-link/**", permitAll)
                authorize("/auth/session/refresh", permitAll)
                authorize("/auth/session/logout", permitAll)
                authorize("/auth/token", permitAll)
                authorize("/auth/pat/mint", permitAll)
                authorize("/auth/pat/verify", permitAll)
                // Framework-public, route-guarded: reaktorServer's graph gateway enforces
                // its own Worker service bearer because it is not a Reaktor user/session JWT.
                authorize("/_graph/**", permitAll)
                authorize("/.well-known/jwks.json", permitAll)
                authorize("/actuator/health/**", permitAll)
                // Everything else requires a valid Reaktor token (then per-route AuthRequirement applies).
                authorize(anyExchange, authenticated)
            }
            csrf { disable() }
            securityContextRepository = NoOpServerSecurityContextRepository.getInstance()
        }
    }
}
