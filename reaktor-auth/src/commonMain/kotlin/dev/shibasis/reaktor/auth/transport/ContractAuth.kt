package dev.shibasis.reaktor.auth.transport

import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessDecisionSink
import dev.shibasis.reaktor.auth.fga.AccessDecisions
import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.graph.CodeAccess
import dev.shibasis.reaktor.auth.graph.CodeAccessPolicy
import dev.shibasis.reaktor.auth.graph.declared
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.contract.Exchange
import dev.shibasis.reaktor.service.contract.Interceptor
import dev.shibasis.reaktor.service.contract.Next
import dev.shibasis.reaktor.service.contract.Position
import dev.shibasis.reaktor.service.contract.Rejected
import dev.shibasis.reaktor.service.contract.Side
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

var Exchange<*, *>.authContext: AuthContext?
    get() = attributes[AUTH_CONTEXT_ATTRIBUTE] as? AuthContext
    set(value) {
        if (value == null) attributes.remove(AUTH_CONTEXT_ATTRIBUTE) else attributes[AUTH_CONTEXT_ATTRIBUTE] = value
    }

private val Clients = setOf(Side.Client)
private val Servers = setOf(Side.Server)

class BearerAuth(
    private val token: suspend (Exchange<*, *>) -> String?,
    private val replaceExisting: Boolean = false,
    private val renewal: (suspend (Exchange<*, *>) -> String?)? = null,
) : Interceptor {
    override val position: Int get() = Position.Security
    override val sides: Set<Side> get() = Clients

    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        if (replaceExisting || bearerTokenFromHeader(exchange.metadata[AUTHORIZATION_HEADER]) == null) {
            token(exchange)?.trim()?.takeIf { it.isNotEmpty() }?.let { exchange.metadata[AUTHORIZATION_HEADER] = bearerAuthorization(it) }
        }
        val renew = renewal ?: return next(exchange)
        val sent = bearerTokenFromHeader(exchange.metadata[AUTHORIZATION_HEADER])
        return try {
            next(exchange)
        } catch (refused: Rejected) {
            if (refused.status != 401) throw refused
            val renewed = renew(exchange)?.trim()?.takeIf { it.isNotEmpty() && it != sent } ?: throw refused
            exchange.metadata[AUTHORIZATION_HEADER] = bearerAuthorization(renewed)
            next(exchange)
        }
    }
}

class ServiceAccount(private val token: suspend (Exchange<*, *>) -> String) : Interceptor {
    override val position: Int get() = Position.Security
    override val sides: Set<Side> get() = Clients

    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        exchange.metadata[AUTHORIZATION_HEADER] = token(exchange)
        return next(exchange)
    }
}

class DevAuth(private val header: String, private val value: suspend (Exchange<*, *>) -> String?) : Interceptor {
    override val position: Int get() = Position.Security
    override val sides: Set<Side> get() = Clients

    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        value(exchange)?.takeIf { it.isNotBlank() }?.let { exchange.metadata[header] = it }
        return next(exchange)
    }
}

class BearerAuthentication(private val context: suspend (String, Exchange<*, *>) -> AuthContext?) : Interceptor {
    override val position: Int get() = Position.Security
    override val sides: Set<Side> get() = Servers

    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        val token = bearerTokenFromHeader(exchange.metadata[AUTHORIZATION_HEADER])
        if (token != null && exchange.authContext == null) exchange.authContext = context(token, exchange)
        return next(exchange)
    }
}

class DevAuthentication(private val header: String, private val context: suspend (String, Exchange<*, *>) -> AuthContext?) : Interceptor {
    override val position: Int get() = Position.Security
    override val sides: Set<Side> get() = Servers

    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        if (exchange.authContext == null) {
            exchange.metadata[header]?.takeIf { it.isNotBlank() }?.let { exchange.authContext = context(it, exchange) }
        }
        return next(exchange)
    }
}

class AuthRequirements(
    private val requirement: (Exchange<*, *>) -> AuthRequirement?,
    private val decisions: AccessDecisionSink = AccessDecisions,
) : Interceptor {
    override val position: Int get() = Position.Security + 1
    override val sides: Set<Side> get() = Servers

    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        val required = requirement(exchange) ?: return next(exchange)
        val context = exchange.authContext
        val decision = LocalAuthorizer.authorize(context, required)
        runCatching {
            val recorded = AccessDecision(CodeAccess.subject(context).toString(), Requirement,
                CodeAccess.operation(exchange.contract, exchange.name).toString(), decision is AuthDecision.Allow, AccessMode.Enforce,
                (decision as? AuthDecision.Deny)?.safeMessage ?: required.toString(), tenant = context?.tenantId, atMillis = Clock.System.now().toEpochMilliseconds())
            exchange.attributes.getOrPut(ServiceCall.AccessAttribute) { recorded.encodeForSpan() }
            decisions.record(recorded)
        }
        return when (decision) {
            is AuthDecision.Allow -> next(exchange)
            is AuthDecision.Deny -> throw AuthRejectedException(decision)
        }
    }

    companion object {
        const val Requirement = "requirement"
    }
}

class RelationAccess(
    private val policy: () -> CodeAccessPolicy?,
    private val authenticate: suspend (Exchange<*, *>) -> AuthContext? = { it.authContext },
    private val decisions: AccessDecisionSink = AccessDecisions,
) : Interceptor {
    override val position: Int get() = Position.Security + 2
    override val sides: Set<Side> get() = Servers

    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        val contract = exchange.contract.takeIf(String::isNotBlank) ?: return next(exchange)
        val operation = exchange.name
        var context: AuthContext? = null
        var current: CodeAccessPolicy? = null
        val decision = try {
            context = authenticate(exchange)
            current = policy() ?: return next(exchange)
            current.check(context, contract, operation, CodeAccess.declared(contract, exchange.operation?.service?.access ?: ServiceAccess.Undeclared, operation))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            val mode = runCatching { policy()?.mode(contract) }.getOrNull() ?: AccessMode.Enforce
            AccessDecision(CodeAccess.subject(context).toString(), CodeAccess.Invoke, CodeAccess.operation(contract, operation).toString(), false, mode,
                "The access model could not answer: ${failure.message ?: failure::class.simpleName}", atMillis = Clock.System.now().toEpochMilliseconds())
        }
        exchange.attributes[ServiceCall.AccessAttribute] = decision.encodeForSpan()
        runCatching { decisions.record(decision.copy(trace = exchange.call.trace.traceId, span = exchange.call.trace.spanId)) }
        if (decision.blocks) throw AuthRejectedException(CodeAccessPolicy.denial(decision, context))
        val objects = current ?: return next(exchange)
        return withContext(ObjectAccess(objects, CodeAccess.subject(context), CodeAccess.contextual(context), contract, context?.tenantId, exchange.attributes, decisions)) {
            next(exchange)
        }
    }
}
