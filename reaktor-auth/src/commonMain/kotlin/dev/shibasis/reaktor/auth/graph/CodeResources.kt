package dev.shibasis.reaktor.auth.graph

import dev.shibasis.reaktor.auth.fga.ObjectRef
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.fga.Subject
import dev.shibasis.reaktor.graph.core.GraphShape
import dev.shibasis.reaktor.graph.core.IslandShape
import dev.shibasis.reaktor.graph.core.NodeKind
import dev.shibasis.reaktor.graph.core.NodeShape
import dev.shibasis.reaktor.graph.core.ScopeShape
import dev.shibasis.reaktor.service.Callers
import dev.shibasis.reaktor.service.OperationDescriptor
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.contract.descriptors
import dev.shibasis.reaktor.service.ServiceAccess
import dev.shibasis.reaktor.service.contract.Service

fun CodeAccess.resources(shape: GraphShape, rootLabel: String? = null): CodeResources {
    val scopes = shape.scopes.associateBy { it.id }
    val children = shape.scopes.groupBy { it.parent }
    val serviceNodes = shape.nodes.filter { it.kind == NodeKind.Service && contractOf(it) != null }
    val holding = mutableSetOf<String>()
    serviceNodes.forEach { node ->
        var at: ScopeShape? = scopes[node.scope]
        while (at != null && holding.add(at.id)) at = at.parent?.let(scopes::get)
    }
    val refs = mutableMapOf<String, ObjectRef>()
    val graphs = mutableListOf<CodeGraph>()
    fun visit(scope: ScopeShape, parent: CodeGraph?) {
        if (scope.id !in holding) return
        val label = if (parent == null) rootLabel ?: scope.label else scope.label
        val base = listOfNotNull(parent?.ref?.id, slug(label)).joinToString("/")
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base-$it" }.first { candidate -> graphs.none { it.ref.id == candidate } }
        val graph = CodeGraph(ObjectRef(Graph, id), label, parent?.ref, listOf(scope.id))
        graphs += graph
        refs[scope.id] = graph.ref
        children[scope.id].orEmpty().forEach { visit(it, graph) }
    }
    shape.scopes.filter { it.parent == null || it.parent !in scopes }.forEach { visit(it, null) }
    val services = serviceNodes.groupBy { requireNotNull(contractOf(it)) }.map { (contract, nodes) ->
        serviceOf(contract, nodes, nodes.mapNotNull { refs[it.scope] }.distinct())
    }.sortedBy { it.ref.id }
    return CodeResources(graphs, services)
}

fun CodeAccess.app(label: String): CodeGraph = CodeGraph(ObjectRef(Graph, slug(label)), label, null, emptyList())

fun CodeAccess.island(app: String, island: String, contracts: List<Service>): CodeResources =
    app(app).let { root -> CodeResources(listOf(root), emptyList()) + served(within(root.ref, island, emptyList()), contracts) }

fun CodeAccess.island(app: String, shape: IslandShape): CodeResources =
    app(app).let { root -> CodeResources(listOf(root), emptyList()) + island(root.ref, shape) }

fun CodeAccess.islands(app: String, shapes: List<IslandShape>): CodeResources =
    app(app).let { root -> shapes.fold(CodeResources(listOf(root), emptyList())) { all, shape -> all + nested(root.ref, shape) } }

private fun CodeAccess.within(parent: ObjectRef, island: String, scopes: List<String>): CodeGraph =
    CodeGraph(ObjectRef(Graph, "${parent.id}/${slug(island)}"), island, parent, scopes)

private fun CodeAccess.island(parent: ObjectRef, shape: IslandShape): CodeResources {
    val graph = within(parent, shape.island, shape.graph.scopes.filter { it.parent == null }.map { it.id })
    val servers = shape.graph.nodes.filter { it.kind == NodeKind.Service && it.attributes["role"] == ServerRole && contractOf(it) != null }
    return CodeResources(listOf(graph), servers.groupBy { requireNotNull(contractOf(it)) }.map { (contract, nodes) -> serviceOf(contract, nodes, listOf(graph.ref)) }
        .sortedBy { it.ref.id })
}

private fun CodeAccess.nested(parent: ObjectRef, shape: IslandShape): CodeResources =
    island(parent, shape).let { here -> shape.objects.fold(here) { all, inner -> all + nested(here.graphs.first().ref, inner) } }

private fun CodeAccess.serviceOf(contract: String, nodes: List<NodeShape>, graphs: List<ObjectRef>): CodeService {
    val ref = service(contract)
    val operations = nodes.flatMap { it.operations }.distinctBy { it.operation }.map { operationOf(ref, contract, it) }.distinctBy { it.ref }
    return CodeService(ref, nodes.first().attributes["service"] ?: nodes.first().label, contract, nodes.firstNotNullOfOrNull { it.attributes["version"] },
        nodes.firstNotNullOfOrNull { it.attributes["baseUrl"] }, nodes.mapNotNull { it.attributes["role"] }.toSet(),
        graphs, nodes.map { it.id }, operations.sortedBy { it.name },
        nodes.firstNotNullOfOrNull { it.access }?.let { declared(contract, it, operations.map(CodeOperation::name)) }.orEmpty())
}

fun CodeAccess.served(graph: CodeGraph, contracts: List<Service>): CodeResources =
    CodeResources(listOf(graph), contracts.map { served ->
        val ref = service(served.id)
        val operations = served.descriptors(ServiceExecutionPhase.SERVER).distinctBy { it.operation }.map { operationOf(ref, served.id, it) }.distinctBy { it.ref }.sortedBy { it.name }
        CodeService(ref, served.id, served.id, served.version.toString(), null, emptySet(), listOf(graph.ref), emptyList(), operations,
            declared(served.id, served.access, operations.map(CodeOperation::name)))
    }.sortedBy { it.ref.id })

fun CodeAccess.callers(callers: Callers): Subject = when (callers) {
    Callers.Everyone -> Subject.Userset(role(Everyone), Assignee)
    Callers.SignedIn -> Subject.Userset(role(Authenticated), Assignee)
    is Callers.Holding -> Subject.Userset(permission(callers.permission), Holder)
}

fun CodeAccess.declared(contract: String, access: ServiceAccess, operations: Collection<String>): List<RelationTuple> =
    if (access.operations.isEmpty()) serviceWide(contract, access)
    else (operations + access.operations.keys).distinct().sorted().mapNotNull { admitted(contract, access, it) }

fun CodeAccess.declared(contract: String, access: ServiceAccess, operation: String): List<RelationTuple> =
    if (access.operations.isEmpty()) serviceWide(contract, access) else listOfNotNull(admitted(contract, access, operation))

private fun CodeAccess.serviceWide(contract: String, access: ServiceAccess): List<RelationTuple> =
    listOfNotNull(access.callers?.let { RelationTuple(service(contract), Caller, callers(it)) })

private fun CodeAccess.admitted(contract: String, access: ServiceAccess, operation: String): RelationTuple? =
    (access.operations[operation] ?: access.callers)?.let { RelationTuple(operation(contract, operation), Invoker, callers(it)) }

fun CodeResources.at(island: ObjectRef): CodeResources =
    CodeResources(generateSequence(graph(island)) { it.parent?.let(::graph) }.toList().asReversed(),
        services.filter { island in it.graphs }.map { it.copy(graphs = listOf(island)) })

operator fun CodeResources.plus(other: CodeResources): CodeResources =
    CodeResources((graphs + other.graphs).distinctBy { it.ref }, (services + other.services).groupBy { it.ref }.map { (_, same) -> same.reduce(CodeService::with) })

private fun CodeService.with(more: CodeService): CodeService =
    copy(roles = roles + more.roles, graphs = (graphs + more.graphs).distinct(), nodes = (nodes + more.nodes).distinct(),
        operations = (operations + more.operations).distinctBy { it.ref }.sortedBy { it.name }, declared = (declared + more.declared).distinct())

private const val ServerRole = "server"

private fun contractOf(node: NodeShape): String? =
    (node.attributes["contract"] ?: node.attributes["service"] ?: node.label.takeIf { node.operations.isNotEmpty() })?.takeIf { it.isNotBlank() }

private fun operationOf(service: ObjectRef, contract: String, descriptor: OperationDescriptor): CodeOperation {
    val name = CodeAccess.operationName(descriptor.operation, descriptor.request.name)
    return CodeOperation(CodeAccess.operation(contract, name), service, name, descriptor.http?.method?.name, descriptor.http?.route,
        descriptor.request.name.substringAfterLast('.'), descriptor.response.name.substringAfterLast('.'))
}

private fun slug(label: String): String = label.lowercase().map { if (it.isLetterOrDigit() || it == '.' || it == '_' || it == '-') it else '-' }
    .joinToString("").split('-').filter(String::isNotEmpty).joinToString("-").ifEmpty { "graph" }
