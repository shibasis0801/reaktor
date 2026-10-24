package dev.shibasis.reaktor.auth.graph

import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessDecisionSink
import dev.shibasis.reaktor.auth.fga.AccessDecisions
import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.Capability
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.graph.ContractNode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.portgraph.attach.attachment
import dev.shibasis.reaktor.portgraph.port.Port
import dev.shibasis.reaktor.portgraph.port.PortEvent
import dev.shibasis.reaktor.portgraph.port.PortInterceptor
import dev.shibasis.reaktor.portgraph.port.PortInterceptors
import dev.shibasis.reaktor.portgraph.port.PortInvocation
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlin.time.Clock

val Graph.accessPath: List<String>
    get() = parentGraph?.accessPath.orEmpty() + listOfNotNull(label.takeIf(String::isNotBlank))

val Node.accessPath: List<String>
    get() = graph.accessPath + label.ifBlank { this::class.simpleName.orEmpty() }

val Port<*>.accessPath: List<String>
    get() = (owner as? Node)?.accessPath.orEmpty() + key.key.ifBlank { type.type }

class AccessGuard(
    private val context: AuthContextProvider,
    private val policy: CodeAccessPolicy? = null,
    private val decisions: AccessDecisionSink = AccessDecisions,
) : PortInterceptor {
    override fun intercept(invocation: PortInvocation, proceed: () -> Any?): Any? {
        admit(invocation)
        return proceed()
    }

    override suspend fun interceptSuspend(invocation: PortInvocation, proceed: suspend () -> Any?): Any? {
        admit(invocation)
        return proceed()
    }

    private fun admit(invocation: PortInvocation) {
        val provider = invocation.edge?.provider ?: invocation.port
        val path = provider.accessPath
        val current = context.current
        listOfNotNull(invocation.port.requirement, invocation.edge?.provider?.requirement).forEach { requirement ->
            val decision = LocalAuthorizer.authorize(current, requirement.at(path))
            record(AccessDecision(CodeAccess.subject(current).toString(), Requirement, "port:" + path.joinToString("/") { CodeAccess.key(it) },
                decision is AuthDecision.Allow, AccessMode.Enforce, (decision as? AuthDecision.Deny)?.safeMessage ?: requirement.toString(),
                tenant = current?.tenantId, atMillis = Clock.System.now().toEpochMilliseconds()))
            if (decision is AuthDecision.Deny) throw UnauthorizedException("Unauthorized at ${path.joinToString("/")}: ${decision.safeMessage}")
        }
        val relations = policy ?: return
        val node = provider.owner as? ContractNode<*> ?: return
        val operation = provider.operation ?: return
        val decision = relations.check(current, node.api.id, operation)
        record(decision)
        if (decision.blocks) throw UnauthorizedException("Unauthorized at ${path.joinToString("/")}: ${CodeAccessPolicy.denial(decision, current).safeMessage}")
    }

    private fun record(decision: AccessDecision) {
        runCatching { decisions.record(decision) }
    }

    internal fun watch(port: Port<*>) {
        if (port.attachment(PortInterceptors)?.contains(this) != true) port.addInterceptor(this)
    }

    companion object {
        const val Requirement = "requirement"
    }
}

fun Graph.guard(context: AuthContextProvider, policy: CodeAccessPolicy? = null, decisions: AccessDecisionSink = AccessDecisions): AccessGuard =
    AccessGuard(context, policy, decisions).also { guard ->
        fun guarded(port: Port<*>) = port.requirement != null || policy != null && port.operation != null
        nodes.forEach { node ->
            node.providerPorts.flattenedValues().filter(::guarded).forEach { provider ->
                guard.watch(provider)
                provider.edges.keys.forEach(guard::watch)
            }
            node.consumerPorts.flattenedValues().filter { guarded(it) || it.edge?.provider?.let(::guarded) == true }.forEach(guard::watch)
            node.addPortEventListener { event ->
                when (event) {
                    is PortEvent.Created -> if (guarded(event.port)) guard.watch(event.port)
                    is PortEvent.Connected -> if (guarded(event.port) || guarded(event.other)) {
                        guard.watch(event.port)
                        guard.watch(event.other)
                    }
                    is PortEvent.Disconnected -> Unit
                }
            }
        }
    }

private val Port<*>.operation: String?
    get() = (owner as? ContractNode<*>)?.api?.operation(key.key)?.name

private val Port<*>.requirement: AuthRequirement?
    get() = (this as? SecuredProviderPort<*>)?.requirement ?: (this as? SecuredConsumerPort<*>)?.requirement

private fun AuthRequirement.at(path: List<String>): AuthRequirement = copy(
    permissions = permissions.mapTo(mutableSetOf()) { it.at(path) },
    scopes = scopes.mapTo(mutableSetOf()) { it.at(path) },
    anyOf = anyOf.map { it.at(path) },
    allOf = allOf.map { it.at(path) },
)

private fun PermissionRef.at(path: List<String>): PermissionRef =
    Capability.parse(name)?.takeIf { it.path.isEmpty() }?.let { copy(name = it.at(path).toString()) } ?: this
