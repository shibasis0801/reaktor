package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.auth.transport.bearerAuthorization
import dev.shibasis.reaktor.core.framework.json
import kotlinx.coroutines.await
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.js.JsExport
import kotlin.js.JsName
import kotlin.js.Promise

class ServiceAccountAuthBinding internal constructor(
    internal val clientId: SecretBinding,
    internal val clientSecret: SecretBinding,
    internal val audience: String,
    internal val scopes: List<String>,
    internal val tokenEndpoint: String,
    internal val ttlSeconds: Int,
    internal val environment: String,
)

@JsName("serviceAccountAuth")
fun serviceAccountAuth(
    clientId: SecretBinding,
    clientSecret: SecretBinding,
    audience: String,
    scopes: List<String>,
    tokenEndpoint: String,
    ttlSeconds: Int = 5 * 60,
    environment: String = "STAGE",
): ServiceAccountAuthBinding =
    ServiceAccountAuthBinding(
        clientId = clientId,
        clientSecret = clientSecret,
        audience = audience,
        scopes = scopes,
        tokenEndpoint = tokenEndpoint,
        ttlSeconds = ttlSeconds,
        environment = environment,
    )

@Serializable
private data class ServiceAccountTokenResponse(
    val accessToken: String,
    val expiresInSeconds: Int = 0,
)

private data class CachedServiceToken(
    val bearer: String,
    val expiresAtMillis: Double,
)

@Serializable
data class DelegatedServiceToken(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Int = 0,
    val audience: String? = null,
    val contextId: String? = null,
    val scopes: List<String> = emptyList(),
)

private val serviceTokenCache = mutableMapOf<String, CachedServiceToken>()

@JsName("serviceAccountBearer")
suspend fun CloudflareContext.serviceAccountBearer(binding: ServiceAccountAuthBinding): String {
    val clientId = requireSecret(binding.clientId.name)
    val cacheKey = listOf(
        binding.tokenEndpoint,
        clientId,
        binding.audience,
        binding.scopes.joinToString(" "),
        binding.environment,
    ).joinToString("|")

    val now = nowMillis()
    serviceTokenCache[cacheKey]?.takeIf { now < it.expiresAtMillis }?.let { return it.bearer }

    val body = buildJsonObject {
        put("grant_type", "client_credentials")
        put("clientId", clientId)
        put("clientSecret", requireSecret(binding.clientSecret.name))
        put("audience", binding.audience)
        put("scopes", JsonArray(binding.scopes.map(::JsonPrimitive)))
        put("ttlSeconds", binding.ttlSeconds)
        put("environment", binding.environment)
    }.toString()

    val response = fetchServiceToken(
        url = binding.tokenEndpoint,
        body = body,
        environment = binding.environment,
    )
    val text = response.text()
    check(response.ok) { "Reaktor service-account token HTTP ${response.status}: ${text.take(ERROR_PREVIEW)}" }

    val token = json.decodeFromString<ServiceAccountTokenResponse>(text)
    val bearer = bearerAuthorization(token.accessToken)
    val cacheTtlSeconds = (token.expiresInSeconds.takeIf { it > 0 } ?: binding.ttlSeconds)
        .coerceAtLeast(MIN_CACHE_TTL_SECONDS)
    serviceTokenCache[cacheKey] = CachedServiceToken(
        bearer = bearer,
        expiresAtMillis = now + ((cacheTtlSeconds - CACHE_SKEW_SECONDS).coerceAtLeast(1) * 1000.0),
    )
    return bearer
}

@JsName("cloudflareServiceAccountBearer")
@JsExport
fun cloudflareServiceAccountBearer(
    env: Any,
    clientIdSecretName: String,
    clientSecretName: String,
    audience: String,
    scopes: Array<String>,
    tokenEndpoint: String,
    ttlSeconds: Int = 5 * 60,
    environment: String = "STAGE",
    executionContext: Any? = null,
): Promise<String> =
    promiseOf {
        cloudflareContext(env, executionContext)
            .serviceAccountBearer(
                serviceAccountAuth(
                    clientId = secret(clientIdSecretName),
                    clientSecret = secret(clientSecretName),
                    audience = audience,
                    scopes = scopes.toList(),
                    tokenEndpoint = tokenEndpoint,
                    ttlSeconds = ttlSeconds,
                    environment = environment,
                ),
            )
    }

suspend fun CloudflareContext.exchangeDelegatedServiceToken(
    binding: ServiceAccountAuthBinding,
    subjectToken: String,
    audience: String,
    scopes: List<String>,
    contextId: String? = null,
    ttlSeconds: Int,
): DelegatedServiceToken {
    val body = buildJsonObject {
        put("grant_type", TOKEN_EXCHANGE_GRANT)
        put("clientId", requireSecret(binding.clientId.name))
        put("clientSecret", requireSecret(binding.clientSecret.name))
        put("subjectToken", subjectToken)
        put("audience", audience)
        put("scopes", JsonArray(scopes.map(::JsonPrimitive)))
        put("ttlSeconds", ttlSeconds)
        put("environment", binding.environment)
        contextId?.let { put("contextId", it) }
    }.toString()

    val response = fetchServiceToken(
        url = binding.tokenEndpoint,
        body = body,
        environment = binding.environment,
    )
    val text = response.text()
    check(response.ok) { "Reaktor delegated token HTTP ${response.status}: ${text.take(ERROR_PREVIEW)}" }

    return json.decodeFromString<DelegatedServiceToken>(text)
}

private suspend fun fetchServiceToken(
    url: String,
    body: String,
    environment: String,
): CloudflareResponse {
    // The tier MUST travel as a header. Request.environment is @Transient, so the "environment"
    // field in the JSON body is dropped during deserialization and the server falls back to PROD —
    // which made a dev worker authenticate against the prod service_account row (same client_id,
    // different secret) and get a 401.
    val init = requestInit(
        method = "POST",
        body = body,
        headers = mapOf(
            "Content-Type" to "application/json; charset=utf-8",
            "X-Environment" to environment,
        ),
    )
    val raw = js("fetch(url, init)").unsafeCast<Promise<RawWorkerResponse>>().await()
    return CloudflareResponse(raw)
}

private fun nowMillis(): Double = js("Date.now()") as Double

private const val ERROR_PREVIEW = 500
private const val CACHE_SKEW_SECONDS = 30
private const val MIN_CACHE_TTL_SECONDS = 31
private const val TOKEN_EXCHANGE_GRANT = "urn:ietf:params:oauth:grant-type:token-exchange"
