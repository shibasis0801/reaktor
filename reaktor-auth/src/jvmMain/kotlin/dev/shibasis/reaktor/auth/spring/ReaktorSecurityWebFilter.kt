package dev.shibasis.reaktor.auth.spring

import dev.shibasis.reaktor.auth.jwt.JwtVerifier
import dev.shibasis.reaktor.auth.jwt.toAuthContext
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.transport.AUTH_CONTEXT_ATTRIBUTE
import dev.shibasis.reaktor.auth.transport.AUTHORIZATION_HEADER
import dev.shibasis.reaktor.auth.transport.bearerTokenFromHeader
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * Verifies bearer tokens locally, converts claims to [AuthContext], and exposes that context to Spring.
 */
class ReaktorSecurityWebFilter(
    private val jwtVerifier: JwtVerifier,
) : WebFilter {

    companion object {
        /** Read the authenticated principal from a handler via `exchange.attributes[AUTH_CONTEXT_ATTR]`. */
        const val AUTH_CONTEXT_ATTR: String = AUTH_CONTEXT_ATTRIBUTE
    }

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val token = bearerTokenFromHeader(exchange.request.headers.getFirst(AUTHORIZATION_HEADER))
            ?: return chain.filter(exchange)

        return Mono.fromCallable { jwtVerifier.verifyReaktorSignature(token) }
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap { result ->
                val claims = result.getOrNull()
                    ?: return@flatMap chain.filter(exchange)
                val context = claims.toAuthContext()
                exchange.attributes[AUTH_CONTEXT_ATTR] = context
                val authorities = buildSet {
                    context.scopes.forEach { add("SCOPE_${it.value}") }
                    context.roles.forEach { role ->
                        val value = role.name ?: role.id
                        if (value != null) add("ROLE_$value")
                    }
                    context.permissions.forEach { add("PERMISSION_${it.name}") }
                }.map(::SimpleGrantedAuthority)
                val authentication = UsernamePasswordAuthenticationToken(context, token, authorities)
                chain.filter(exchange)
                    .contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
            }
    }
}
