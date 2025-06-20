package dev.shibasis.reaktor.auth.db

import dev.shibasis.reaktor.navigation.Reaktor

//@ReadingConverter
//class PostgresJsonToJsonElement : Converter<PostgresJson, JsonElement> {
//    override fun convert(source: PostgresJson): JsonElement =
//        parseToJsonElement(source.asString())
//}
//
//@WritingConverter
//class JsonElementToPostgresJson : Converter<JsonElement, PostgresJson> {
//    override fun convert(source: JsonElement): PostgresJson =
//        PostgresJson.of(source.toString())
//}
//
//@Configuration(proxyBeanMethods = false)
//open class DbConfig {
//    @Bean
//    open fun r2dbcCustomConversions(): R2dbcCustomConversions =
//        R2dbcCustomConversions.of(
//            PostgresDialect.INSTANCE,
//            listOf(JsonElementToPostgresJson(), PostgresJsonToJsonElement())
//        )
//}

val Reaktor.EnvironmentHeader: String
    get() = "X-Environment"
val Reaktor.EnvironmentProd: String
    get() = "prod"
val Reaktor.EnvironmentStage: String
    get() = "stage"

//@Component
//class EnvironmentFilter: WebFilter {
//    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void?> {
//        val environment = when(exchange.request.headers[Reaktor.EnvironmentHeader]?.firstOrNull()?.lowercase()) {
//            "prod" -> "prod"
//            else -> "stage"
//        }
//        Logger.i { "Environment: $environment" }
//        return chain.filter(exchange)
//            .contextWrite { it.put(Reaktor.EnvironmentHeader, environment) }
//    }
//}
