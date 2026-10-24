package dev.shibasis.reaktor.auth.transport

import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.BearerHeaders
import dev.shibasis.reaktor.service.HttpFailure
import dev.shibasis.reaktor.service.Request
import kotlin.js.JsExport
import kotlin.js.JsName

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

class AuthRejectedException(
    val decision: AuthDecision.Deny,
) : HttpFailure(StatusCode(decision.statusCode), decision.safeMessage)
