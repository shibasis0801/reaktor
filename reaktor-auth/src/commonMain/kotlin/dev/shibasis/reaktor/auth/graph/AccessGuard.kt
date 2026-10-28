package dev.shibasis.reaktor.auth.graph

import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessDecisionSink
import dev.shibasis.reaktor.auth.fga.AccessDecisions
import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.fga.ObjectRef
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthRequirement
import dev.shibasis.reaktor.auth.kernel.Capability
import dev.shibasis.reaktor.auth.kernel.LocalAuthorizer
import dev.shibasis.reaktor.auth.kernel.PermissionRef
import dev.shibasis.reaktor.core.structs.ConcurrentHashMap
import dev.shibasis.reaktor.graph.ContractNode
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.core.GraphChange
import dev.shibasis.reaktor.graph.core.node.ContainerNode
import dev.shibasis.reaktor.graph.core.node.Node
import dev.shibasis.reaktor.portgraph.attach.AttachmentKey
import dev.shibasis.reaktor.portgraph.attach.attach
import dev.shibasis.reaktor.portgraph.attach.attachment
import dev.shibasis.reaktor.portgraph.port.Key
import dev.shibasis.reaktor.portgraph.port.Port
import dev.shibasis.reaktor.portgraph.port.PortCapability
import dev.shibasis.reaktor.portgraph.port.PortDelegate
import dev.shibasis.reaktor.portgraph.port.PortEvent
import dev.shibasis.reaktor.portgraph.port.PortEventListener
import dev.shibasis.reaktor.portgraph.port.PortInterceptor
import dev.shibasis.reaktor.portgraph.port.PortInterceptors
import dev.shibasis.reaktor.portgraph.port.PortInvocation
import dev.shibasis.reaktor.portgraph.port.ProviderPort
import dev.shibasis.reaktor.portgraph.port.Type
import dev.shibasis.reaktor.portgraph.port.Type.Companion.Type
import dev.shibasis.reaktor.portgraph.port.addInterceptor
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import dev.shibasis.reaktor.portgraph.port.removeInterceptor
import kotlin.properties.PropertyDelegateProvider
import kotlin.properties.ReadOnlyProperty
import kotlin.time.Clock

val Graph.accessPath: List<String>
    get() = parentGraph?.accessPath.orEmpty() + listOfNotNull(label.takeIf(String::isNotBlank))

val Node.accessPath: List<String>
    get() = graph.accessPath + label.ifBlank { this::class.simpleName.orEmpty() }

val Port<*>.accessPath: List<String>
    get() = (owner as? Node)?.accessPath.orEmpty() + key.key.ifBlank { type.type }

val ProtectedPort = AttachmentKey<Boolean>("reaktor.auth.protected")

val Port<*>.isProtected: Boolean get() = hasAttachments && attachment(ProtectedPort) == true

@Suppress("UNCHECKED_CAST")
fun <Functionality : Any> PortCapability.registerProtectedProvider(key: Key, type: Type, impl: Functionality): ProviderPort<Functionality> {
    val registration = providerPorts.putIfAbsent(type, key) { ProviderPort(this, key, type, impl).also { it.attach(ProtectedPort, true) } as ProviderPort<Any> }
    val port = registration.value as ProviderPort<Functionality>
    require(port.impl === impl && port.isProtected) { "Provider already registered for key='${key.key}' type='${type.type}' without the same protected implementation." }
    if (registration.created) emit(PortEvent.Created(port))
    return port
}

inline fun <reified Functionality : Any> PortCapability.providesProtected(impl: Functionality, name: String? = null) =
    PropertyDelegateProvider<PortCapability, PortDelegate<ProviderPort<Functionality>>> { thisRef, property ->
        val port = thisRef.registerProtectedProvider(Key(name ?: property.name), Type<Functionality>(), impl)
        ReadOnlyProperty { _, _ -> port }
    }

class AccessGuard(
    private val context: AuthContextProvider,
    private val policy: CodeAccessPolicy? = null,
    private val decisions: AccessDecisionSink = AccessDecisions,
) : PortInterceptor, AutoCloseable {
    private class Covered(val ref: ObjectRef, val release: () -> Unit)

    private val graphs = ConcurrentHashMap<Graph, Covered>()
    private val nodes = ConcurrentHashMap<Node, () -> Unit>()
    private val ports = ConcurrentHashMap<Port<*>, Boolean>()

    internal val watching: Int get() = ports.keys().size

    internal val covering: Int get() = graphs.keys().size

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
        val contract = (provider.owner as? ContractNode<*>)?.api?.id
        val operation = provider.operation
        val decision = when {
            contract != null && operation != null -> relations.check(current, contract, operation)
            provider.isProtected -> target(provider)?.let { relations.check(current, it) } ?: return
            else -> return
        }
        record(decision)
        if (decision.blocks) throw UnauthorizedException("Unauthorized at ${path.joinToString("/")}: ${CodeAccessPolicy.denial(decision, current).safeMessage}")
    }

    private fun target(port: Port<*>): PortTarget? {
        val node = port.owner as? Node ?: return null
        val graph = graphs[node.graph]?.ref ?: CodeAccess.graph(node.graph.accessPath)
        return CodeAccess.target(graph, node.label.ifBlank { node::class.simpleName.orEmpty() }, port.key.key.ifBlank { port.type.type })
    }

    private fun guarded(port: Port<*>) = port.requirement != null || policy != null && (port.operation != null || port.isProtected)

    private fun record(decision: AccessDecision) {
        runCatching { decisions.record(decision) }
    }

    internal fun watch(port: Port<*>) {
        if (port.attachment(PortInterceptors)?.contains(this) != true) port.addInterceptor(this)
        ports.putIfAbsent(port, true)
    }

    private fun unwatch(port: Port<*>) {
        port.removeInterceptor(this)
        ports.remove(port)
    }

    internal fun cover(graph: Graph, ref: ObjectRef) {
        if (graphs.containsKey(graph)) return
        val release = graph.onStructure { change ->
            when (change) {
                is GraphChange.Attached -> cover(change.node)
                is GraphChange.Detached -> forget(change.node)
                is GraphChange.Owned -> cover(change.graph, CodeAccess.within(ref, change.graph.label))
                GraphChange.Closed -> forget(graph)
            }
        }
        if (graphs.putIfAbsent(graph, Covered(ref, release)) != null) return release()
        graph.nodes.forEach(::cover)
        graph.owned.forEach { cover(it, CodeAccess.within(ref, it.label)) }
    }

    private fun cover(node: Node) {
        val listener: PortEventListener = { event ->
            when (event) {
                is PortEvent.Created -> if (guarded(event.port)) watch(event.port)
                is PortEvent.Connected -> if (guarded(event.port) || guarded(event.other)) {
                    watch(event.port)
                    watch(event.other)
                }
                is PortEvent.Disconnected -> Unit
            }
        }
        val release: () -> Unit = { node.removePortEventListener(listener) }
        if (nodes.putIfAbsent(node, release) != null) return
        node.addPortEventListener(listener)
        node.providerPorts.flattenedValues().filter(::guarded).forEach { provider ->
            watch(provider)
            provider.edges.keys.forEach(::watch)
        }
        node.consumerPorts.flattenedValues().filter { guarded(it) || it.edge?.provider?.let(::guarded) == true }.forEach(::watch)
        val at = graphs[node.graph]?.ref ?: CodeAccess.graph(node.graph.accessPath)
        (node as? ContainerNode)?.graphs?.forEach { cover(it, CodeAccess.within(at, it.label)) }
    }

    private fun forget(node: Node) {
        val release = nodes.remove(node) ?: return
        release()
        (node.providerPorts.flattenedValues() + node.consumerPorts.flattenedValues()).forEach(::unwatch)
        (node as? ContainerNode)?.graphs?.forEach(::forget)
    }

    private fun forget(graph: Graph) {
        val covered = graphs.remove(graph) ?: return
        covered.release()
        graph.nodes.forEach(::forget)
    }

    override fun close() {
        graphs.keys().forEach(::forget)
        nodes.keys().forEach(::forget)
        ports.keys().forEach(::unwatch)
    }

    companion object {
        const val Requirement = "requirement"
    }
}

fun Graph.guard(context: AuthContextProvider, policy: CodeAccessPolicy? = null, decisions: AccessDecisionSink = AccessDecisions,
                at: ObjectRef = CodeAccess.graph(accessPath)): AccessGuard =
    AccessGuard(context, policy, decisions).also { it.cover(this, at) }

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
