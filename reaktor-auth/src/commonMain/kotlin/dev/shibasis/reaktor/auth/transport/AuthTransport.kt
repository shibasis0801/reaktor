package dev.shibasis.reaktor.auth.transport

import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.auth.kernel.BearerHeaders
import dev.shibasis.reaktor.service.InterceptorContext
import dev.shibasis.reaktor.service.InterceptorStage
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.ServiceChain
import dev.shibasis.reaktor.service.ServiceInterceptor
import kotlin.js.JsExport
import kotlin.js.JsName
import dev.shibasis.reaktor.service.ServiceStatusException

const val AUTHORIZATION_HEADER = BearerHeaders.AUTHORIZATION
const val BEARER_PREFIX = BearerHeaders.PREFIX
const val AUTH_CONTEXT_ATTRIBUTE = "reaktor.auth.context"

@JsName("AUTHORIZATION_HEADER")
@JsExport
val authorizationHeaderName: String
    get() = AUTHORIZATION_HEADER

@JsName("bearerAuthorization")
@JsExport
fun bearerAuthorization(token: String): String =
    BearerHeaders.authorization(token)

@JsName("bearerTokenFromHeader")
@JsExport
fun bearerTokenFromHeader(value: String?): String? = BearerHeaders.tokenFromHeader(value)

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
    private val renewal: (suspend (InterceptorContext<*, *>) -> String?)? = null,
) : ServiceInterceptor {
    override val stages: Set<InterceptorStage> =
        setOf(InterceptorStage.CLIENT_APPLICATION, InterceptorStage.CLIENT_TRANSPORT)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        if (replaceExisting || bearerTokenFromHeaders(chain.request.headers) == null) {
            tokenProvider(chain.context)?.trim()?.takeIf { it.isNotEmpty() }?.let { token ->
                chain.request.headers.putBearerAuthorization(token)
            }
        }
        val sent = bearerTokenFromHeaders(chain.request.headers)
        val first = runCatching { chain.proceed() }
        val unauthorized = first.fold(
            onSuccess = { it.statusCode == StatusCode.UNAUTHORIZED },
            onFailure = { (it as? ServiceStatusException)?.status == StatusCode.UNAUTHORIZED.code },
        )
        val renew = renewal
        if (!unauthorized || renew == null) return first.getOrThrow()
        val renewed = renew(chain.context)?.trim()?.takeIf { it.isNotEmpty() && it != sent } ?: return first.getOrThrow()
        chain.request.headers.putBearerAuthorization(renewed)
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
