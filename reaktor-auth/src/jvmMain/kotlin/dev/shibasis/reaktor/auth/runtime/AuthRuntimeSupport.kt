package dev.shibasis.reaktor.auth.runtime

import dev.shibasis.reaktor.auth.PersonalAccessToken
import dev.shibasis.reaktor.auth.api.TokenRequest
import dev.shibasis.reaktor.auth.kernel.CredentialCeiling
import dev.shibasis.reaktor.auth.parseTokenPolicyList
import dev.shibasis.reaktor.auth.transport.bearerTokenFromHeaders
import dev.shibasis.reaktor.graph.di.DependencyAdapter
import dev.shibasis.reaktor.graph.di.DependencyScopeCapability
import dev.shibasis.reaktor.service.Request
import kotlin.reflect.KClass
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

internal const val DEFAULT_TOKEN_AUDIENCE = "manna-mcp"
internal const val MIN_ACCESS_TOKEN_TTL_SECONDS = 60
internal const val MAX_ACCESS_TOKEN_TTL_SECONDS = 60 * 60
internal const val TOKEN_EXCHANGE_GRANT = "urn:ietf:params:oauth:grant-type:token-exchange"
internal const val AUTH_EXPOSED_ADAPTER_DEPENDENCY = "reaktorAuth.exposedAdapter"
internal const val AUTH_RUNTIME_CONFIG_DEPENDENCY = "reaktorAuth.config"

internal fun Request.bearerToken(): String? {
    return bearerTokenFromHeaders(headers)
}

internal fun TokenRequest.bearerOrRawToken(): String? =
    bearerToken() ?: rawToken.trim().takeIf { it.isNotEmpty() }

internal fun String?.normalizedAudience(): String =
    this?.trim()?.ifBlank { DEFAULT_TOKEN_AUDIENCE } ?: DEFAULT_TOKEN_AUDIENCE

internal fun Int.clampedAccessTokenTtl(): Int =
    coerceIn(MIN_ACCESS_TOKEN_TTL_SECONDS, MAX_ACCESS_TOKEN_TTL_SECONDS)

internal fun List<String>.normalizedScopes(default: List<String> = emptyList()): List<String> =
    map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { default }

internal fun List<String>.normalizedAuthNames(): List<String> =
    map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()

internal fun PersonalAccessToken.scopeList(): List<String> =
    parseTokenPolicyList(scopes)

internal fun PersonalAccessToken.audienceAllowed(audience: String): Boolean {
    val raw = allowedAudiences ?: return false
    val allowed = parseTokenPolicyList(raw)
    return "*" in allowed || audience in allowed
}

internal fun PersonalAccessToken.canMintPat(): Boolean =
    scopeList().any { it == "*" || it == "auth:*" || it == "auth:pat:mint" }

object PatCeiling {
    const val Field = "ceiling"
    val Marker: JsonObject = buildJsonObject { put(Field, CredentialCeiling.Version) }
}

internal val PersonalAccessToken.ceiling: CredentialCeiling?
    get() = (data as? JsonObject)?.get(PatCeiling.Field)?.let { marker ->
        (marker as? JsonPrimitive)?.intOrNull?.takeIf { it == CredentialCeiling.Version }?.let { CredentialCeiling(it, scopeList().toSet()) } ?: CredentialCeiling.Closed
    }

internal val PersonalAccessToken.reach: CredentialCeiling
    get() = ceiling ?: CredentialCeiling(scopes = scopeList().toSet())

internal class AuthRuntimeDependencyAdapter : DependencyAdapter<Unit>(Unit) {
    override fun createScope(
        id: String,
        parent: DependencyScopeCapability?,
        configure: (ScopeBuilder.() -> Unit),
    ): DependencyScopeCapability {
        val scope = AuthRuntimeScope(id, parent as? AuthRuntimeScope)
        AuthRuntimeScopeBuilder(scope).configure()
        return scope
    }

    override fun closeScope(scope: DependencyScopeCapability) {
        scope.close()
    }

    override fun <T : Any> get(
        scope: DependencyScopeCapability,
        type: KClass<T>,
        qualifier: String?,
        parameters: Map<String, Any?>,
    ): T = (scope as AuthRuntimeScope).get(type, qualifier, parameters)

    override fun <T : Any> register(
        scope: DependencyScopeCapability,
        instance: T,
        type: KClass<T>,
        qualifier: String?,
    ) {
        (scope as AuthRuntimeScope).register(type, qualifier, instance)
    }
}

private class AuthRuntimeScopeBuilder(
    private val scope: AuthRuntimeScope,
) : DependencyAdapter.ScopeBuilder {
    override fun <T : Any> factory(
        type: KClass<T>,
        qualifier: String?,
        definition: DependencyScopeCapability.() -> T,
    ) {
        scope.register(type, qualifier, definition(scope))
    }

    override fun <T : Any> singleton(
        type: KClass<T>,
        qualifier: String?,
        definition: DependencyScopeCapability.() -> T,
    ) {
        scope.register(type, qualifier, definition(scope))
    }
}

private class AuthRuntimeScope(
    override val id: String,
    private val parent: AuthRuntimeScope? = null,
) : DependencyScopeCapability {
    private val values = linkedMapOf<Pair<KClass<*>, String?>, Any>()

    override fun <T : Any> get(type: KClass<T>, qualifier: String?, parameters: Map<String, Any?>): T {
        @Suppress("UNCHECKED_CAST")
        return values[type to qualifier] as? T
            ?: parent?.get(type, qualifier, parameters)
            ?: error("Missing dependency type=$type qualifier=$qualifier")
    }

    fun <T : Any> register(type: KClass<T>, qualifier: String?, instance: T) {
        values[type to qualifier] = instance
    }

    override fun close() {
        values.clear()
    }
}
