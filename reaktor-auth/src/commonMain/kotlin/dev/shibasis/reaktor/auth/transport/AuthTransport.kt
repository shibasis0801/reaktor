package dev.shibasis.reaktor.auth.transport

import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.service.InterceptorContext
import dev.shibasis.reaktor.service.InterceptorStage
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.ServiceChain
import dev.shibasis.reaktor.service.ServiceInterceptor
import kotlin.js.JsExport
import kotlin.js.JsName

const val AUTHORIZATION_HEADER = "Authorization"
const val BEARER_PREFIX = "Bearer "
const val AUTH_CONTEXT_ATTRIBUTE = "reaktor.auth.context"

@JsName("AUTHORIZATION_HEADER")
@JsExport
val authorizationHeaderName: String
    get() = AUTHORIZATION_HEADER

@JsName("bearerAuthorization")
@JsExport
fun bearerAuthorization(token: String): String =
    BEARER_PREFIX + token.trim()

@JsName("bearerTokenFromHeader")
@JsExport
fun bearerTokenFromHeader(value: String?): String? {
    val raw = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (!raw.startsWith(BEARER_PREFIX, ignoreCase = true)) return null
    return raw.drop(BEARER_PREFIX.length).trim().takeIf { it.isNotEmpty() }
}

@JsName("headerValue")
fun Map<String, String>.headerValue(name: String): String? =
    entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

@JsName("bearerTokenFromHeaders")
fun bearerTokenFromHeaders(headers: Map<String, String>): String? =
    bearerTokenFromHeader(headers.headerValue(AUTHORIZATION_HEADER))

fun MutableMap<String, String>.putBearerAuthorization(token: String) {
    this[AUTHORIZATION_HEADER] = bearerAuthorization(token)
}

fun Request.authContextOrNull(): AuthContext? =
    attributes[AUTH_CONTEXT_ATTRIBUTE] as? AuthContext

fun Request.setAuthContext(context: AuthContext?) {
    if (context == null) {
        attributes.remove(AUTH_CONTEXT_ATTRIBUTE)
    } else {
        attributes[AUTH_CONTEXT_ATTRIBUTE] = context
    }
}

class BearerAuthClientInterceptor(
    private val tokenProvider: suspend (InterceptorContext<*, *>) -> String?,
    private val replaceExisting: Boolean = false,
) : ServiceInterceptor {
    override val stages: Set<InterceptorStage> =
        setOf(InterceptorStage.CLIENT_APPLICATION, InterceptorStage.CLIENT_TRANSPORT)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        if (replaceExisting || bearerTokenFromHeaders(chain.request.headers) == null) {
            tokenProvider(chain.context)?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
                chain.request.headers.putBearerAuthorization(token)
            }
        }
        return chain.proceed()
    }
}

class BearerAuthServerInterceptor(
    private val contextProvider: suspend (String, InterceptorContext<*, *>) -> AuthContext?,
) : ServiceInterceptor {
    override val stages: Set<InterceptorStage> =
        setOf(InterceptorStage.SERVER_TRANSPORT, InterceptorStage.SERVER_APPLICATION, InterceptorStage.CONNECTION_SETUP)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        val token = bearerTokenFromHeaders(chain.request.headers)
        if (token != null && chain.request.authContextOrNull() == null) {
            chain.request.setAuthContext(contextProvider(token, chain.context))
        }
        return chain.proceed()
    }
}

class AuthRequirementInterceptor(
    private val requirementProvider: (InterceptorContext<*, *>) -> AuthRequirement?,
) : ServiceInterceptor {
    override val stages: Set<InterceptorStage> =
        setOf(InterceptorStage.SERVER_APPLICATION, InterceptorStage.CONNECTION_SETUP)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        val requirement = requirementProvider(chain.context) ?: return chain.proceed()
        when (val decision = LocalAuthorizer.authorize(chain.request.authContextOrNull(), requirement)) {
            is AuthDecision.Allow -> return chain.proceed()
            is AuthDecision.Deny -> throw AuthRejectedException(decision)
        }
    }
}

class ServiceAccountInterceptor(
    private val tokenProvider: suspend (InterceptorContext<*, *>) -> String,
) : ServiceInterceptor {
    override val stages: Set<InterceptorStage> =
        setOf(InterceptorStage.CLIENT_APPLICATION, InterceptorStage.CLIENT_TRANSPORT)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        chain.request.headers[AUTHORIZATION_HEADER] = tokenProvider(chain.context)
        return chain.proceed()
    }
}

class DevAuthInterceptor(
    private val headerName: String,
    private val valueProvider: suspend (InterceptorContext<*, *>) -> String?,
) : ServiceInterceptor {
    override val stages: Set<InterceptorStage> =
        setOf(InterceptorStage.CLIENT_APPLICATION, InterceptorStage.CLIENT_TRANSPORT)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        valueProvider(chain.context)?.takeIf { it.isNotBlank() }?.let { value ->
            chain.request.headers[headerName] = value
        }
        return chain.proceed()
    }
}

class DevAuthServerInterceptor(
    private val headerName: String,
    private val contextProvider: suspend (String, InterceptorContext<*, *>) -> AuthContext?,
) : ServiceInterceptor {
    override val stages: Set<InterceptorStage> =
        setOf(InterceptorStage.SERVER_TRANSPORT, InterceptorStage.SERVER_APPLICATION, InterceptorStage.CONNECTION_SETUP)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        if (chain.request.authContextOrNull() == null) {
            chain.request.headers.headerValue(headerName)
                ?.takeIf { it.isNotBlank() }
                ?.let { value -> chain.request.setAuthContext(contextProvider(value, chain.context)) }
        }
        return chain.proceed()
    }
}

class AuthRejectedException(
    val decision: AuthDecision.Deny,
) : RuntimeException(decision.safeMessage)
