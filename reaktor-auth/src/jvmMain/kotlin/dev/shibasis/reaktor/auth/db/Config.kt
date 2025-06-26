package dev.shibasis.reaktor.auth.db

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.navigation.Reaktor
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

val Reaktor.EnvironmentHeader: String
    get() = "X-Environment"
val Reaktor.EnvironmentProd: String
    get() = "prod"
val Reaktor.EnvironmentStage: String
    get() = "stage"

@Component
class EnvironmentFilter: WebFilter {
    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void?> {
        val environment = when(exchange.request.headers[Reaktor.EnvironmentHeader]?.firstOrNull()?.lowercase()) {
            "prod" -> "prod"
            else -> "stage"
        }
        Logger.i { "Environment: $environment" }
        return chain.filter(exchange)
            .contextWrite { it.put(Reaktor.EnvironmentHeader, environment) }
    }
}
