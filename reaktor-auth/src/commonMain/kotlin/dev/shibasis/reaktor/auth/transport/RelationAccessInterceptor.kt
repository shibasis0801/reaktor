package dev.shibasis.reaktor.auth.transport

import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessDecisionSink
import dev.shibasis.reaktor.auth.fga.AccessDecisions
import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.graph.CodeAccess
import dev.shibasis.reaktor.auth.graph.CodeAccessPolicy
import dev.shibasis.reaktor.auth.graph.declared
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.service.InterceptorStage
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import dev.shibasis.reaktor.service.Service
import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.ServiceCall
import dev.shibasis.reaktor.service.ServiceChain
import dev.shibasis.reaktor.service.ServiceInterceptor
import dev.shibasis.reaktor.service.call
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock

class RelationAccessInterceptor(
    private val policy: () -> CodeAccessPolicy?,
    private val contract: String? = null,
    private val authenticate: suspend (Request) -> AuthContext? = { it.authContextOrNull() },
    private val decisions: AccessDecisionSink = AccessDecisions,
) : ServiceInterceptor {
    constructor(policy: CodeAccessPolicy, contract: String? = null) : this({ policy }, contract)

    override val stages: Set<InterceptorStage> = setOf(InterceptorStage.SERVER_APPLICATION, InterceptorStage.CONNECTION_SETUP)

    override suspend fun <In : Request, Out : Response> intercept(chain: ServiceChain<In, Out>): Out {
        val handler = chain.handler
        val contract = contract ?: handler.owner?.contract?.id?.takeIf(String::isNotBlank) ?: return chain.proceed()
        val operation = CodeAccess.operationName(handler.endpoint.operation, handler.requestSerializer.descriptor.serialName)
        var context: AuthContext? = null
        var current: CodeAccessPolicy? = null
        val decision = try {
            context = authenticate(chain.request)
            current = policy() ?: return chain.proceed()
            current.check(context, contract, operation, CodeAccess.declared(contract, handler.owner?.access ?: ServiceAccess.Undeclared, operation))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            unanswered(contract, operation, context, failure)
        }
        val call = chain.request.call[ServiceCall] ?: currentCoroutineContext()[ServiceCall]
        chain.request.attributes[ServiceCall.AccessAttribute] = decision.encodeForSpan()
        runCatching { decisions.record(decision.copy(trace = call?.trace?.traceId, span = call?.trace?.spanId)) }
        if (decision.blocks) throw AuthRejectedException(CodeAccessPolicy.denial(decision, context))
        val objects = current ?: return chain.proceed()
        return withContext(ObjectAccess(objects, CodeAccess.subject(context), CodeAccess.contextual(context), contract, context?.tenantId, chain.request, decisions)) {
            chain.proceed()
        }
    }

    private fun unanswered(contract: String, operation: String, context: AuthContext?, failure: Throwable): AccessDecision {
        val mode = runCatching { policy()?.mode(contract) }.getOrNull() ?: AccessMode.Enforce
        return AccessDecision(CodeAccess.subject(context).toString(), CodeAccess.Invoke, CodeAccess.operation(contract, operation).toString(), false, mode,
            "The access model could not answer: ${failure.message ?: failure::class.simpleName}", atMillis = Clock.System.now().toEpochMilliseconds())
    }
}

fun Service.guardRelations(policy: CodeAccessPolicy): Service =
    use(RelationAccessInterceptor(policy, contract.id.ifBlank { this::class.simpleName.orEmpty() }))

fun Service.guardRelations(
    policy: () -> CodeAccessPolicy?,
    authenticate: suspend (Request) -> AuthContext? = { it.authContextOrNull() },
    decisions: AccessDecisionSink = AccessDecisions,
): Service = use(RelationAccessInterceptor(policy, contract.id.ifBlank { this::class.simpleName.orEmpty() }, authenticate, decisions))
