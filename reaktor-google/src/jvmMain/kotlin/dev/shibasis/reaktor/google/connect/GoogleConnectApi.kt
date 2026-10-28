package dev.shibasis.reaktor.google.connect

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.service.contract.Service
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class GoogleSubject(val service: String, val subject: String) {
    fun key() = GrantKey(service, subject)
}

@Serializable
data class GoogleAuthorization(val subject: GoogleSubject, val scopes: List<String>, val returnUrl: String, val loginHint: String? = null)

@Serializable
data class GoogleSettlement(val subject: GoogleSubject, val handle: String)

@Serializable
data class GoogleCallback(val state: String?, val code: String?, val error: String?)

@Serializable
data class GoogleInvocation(val subject: GoogleSubject, val api: String, val operation: String, val parameters: JsonObject)

open class GoogleConnectApi : Service("reaktor.google-connect") {
    open val authorize by post<GoogleAuthorization, String>("/authorize", effect = Effect.ProductionReversibleWrite)
    open val settle by post<GoogleSettlement, GoogleOutcome>("/settle", effect = Effect.ProductionReversibleWrite)
    open val grant by post<GoogleSubject, GoogleGrantView?>("/grant", effect = Effect.LiveRead)
    open val revoke by post<GoogleSubject, Boolean?>("/revoke", effect = Effect.Destructive)
    open val invoke by post<GoogleInvocation, GoogleCall>("/invoke", effect = Effect.ProductionReversibleWrite)
    open val complete by post<GoogleCallback, GoogleCompletion>("/complete", effect = Effect.ProductionReversibleWrite)
}

class GoogleConnectService(private val connector: GoogleConnector?) : GoogleConnectApi() {
    private fun ready() = checkNotNull(connector) { "Google is not configured" }
    override val authorize = super.authorize.handle { ready().begin(it.subject.key(), it.scopes, it.returnUrl, it.loginHint) }
    override val settle = super.settle.handle { ready().settle(it.subject.key(), it.handle) }
    override val grant = super.grant.handle { ready().grant(it.key()) }
    override val revoke = super.revoke.handle { ready().revoke(it.key()) }
    override val invoke = super.invoke.handle { ready().call(it.subject.key(), it.api, it.operation, it.parameters) }
    override val complete = super.complete.handle { ready().complete(it.state, it.code, it.error) }
}
