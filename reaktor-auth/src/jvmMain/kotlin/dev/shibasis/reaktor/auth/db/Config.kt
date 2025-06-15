package dev.shibasis.reaktor.auth.db

import co.touchlab.kermit.Logger
import dev.shibasis.reaktor.auth.GoogleOAuthConfig
import dev.shibasis.reaktor.navigation.Reaktor
import kotlinx.serialization.json.Json.Default.parseToJsonElement
import kotlinx.serialization.json.JsonElement
import io.r2dbc.postgresql.codec.Json as PostgresJson
import kotlinx.serialization.json.Json as KotlinJson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.core.env.Environment
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration
import org.springframework.data.r2dbc.convert.R2dbcCustomConversions
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.PostgresDialect
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.server.RouterFunction
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@ReadingConverter
class PostgresJsonToJsonElement : Converter<PostgresJson, JsonElement> {
    override fun convert(source: PostgresJson): JsonElement =
        parseToJsonElement(source.asString())
}

@WritingConverter
class JsonElementToPostgresJson : Converter<JsonElement, PostgresJson> {
    override fun convert(source: JsonElement): PostgresJson =
        PostgresJson.of(source.toString())
}

@Configuration(proxyBeanMethods = false)
open class DbConfig {
    @Bean
    open fun r2dbcCustomConversions(): R2dbcCustomConversions =
        R2dbcCustomConversions.of(
            PostgresDialect.INSTANCE,
            listOf(JsonElementToPostgresJson(), PostgresJsonToJsonElement())
        )
}

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
