package dev.shibasis.reaktor.auth.graph

import dev.shibasis.reaktor.auth.fga.AccessDecision
import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.fga.AuthorizationModel
import dev.shibasis.reaktor.auth.fga.Fga
import dev.shibasis.reaktor.auth.fga.FgaDigest
import dev.shibasis.reaktor.auth.fga.FgaDsl
import dev.shibasis.reaktor.auth.fga.FgaStore
import dev.shibasis.reaktor.auth.fga.ModelRules
import dev.shibasis.reaktor.auth.fga.ObjectRef
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.fga.Subject
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.AuthDecision
import dev.shibasis.reaktor.auth.kernel.AuthDenyReason
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.PrincipalRef
import kotlin.time.Clock

data class CodeGraph(val ref: ObjectRef, val label: String, val parent: ObjectRef?, val scopes: List<String>)

data class CodeOperation(
    val ref: ObjectRef,
    val service: ObjectRef,
    val name: String,
    val method: String?,
    val route: String?,
    val request: String,
    val response: String,
    val summary: String? = null,
    val effect: String? = null,
)

data class CodeService(
    val ref: ObjectRef,
    val label: String,
    val contract: String,
    val version: String?,
    val baseUrl: String?,
    val roles: Set<String>,
    val graphs: List<ObjectRef>,
    val nodes: List<String>,
    val operations: List<CodeOperation>,
    val declared: List<RelationTuple> = emptyList(),
)

data class CodeResources(val graphs: List<CodeGraph>, val services: List<CodeService>) {
    val operations: List<CodeOperation> get() = services.flatMap { it.operations }

    val objects: List<ObjectRef> get() = graphs.map { it.ref } + services.map { it.ref } + operations.map { it.ref }

    val structure: List<RelationTuple>
        get() = graphs.mapNotNull { graph -> graph.parent?.let { RelationTuple(graph.ref, CodeAccess.Parent, Subject.Object(it)) } } +
            services.flatMap { service -> service.graphs.map { RelationTuple(service.ref, CodeAccess.GraphRelation, Subject.Object(it)) } } +
            operations.map { RelationTuple(it.ref, CodeAccess.ServiceRelation, Subject.Object(it.service)) }

    val declared: List<RelationTuple> get() = services.flatMap { it.declared }.distinct()

    val tuples: List<RelationTuple> get() = structure + declared

    fun graph(ref: ObjectRef): CodeGraph? = graphs.firstOrNull { it.ref == ref }

    fun service(ref: ObjectRef): CodeService? = services.firstOrNull { it.ref == ref }

    fun operation(ref: ObjectRef): CodeOperation? = operations.firstOrNull { it.ref == ref }

    fun serviceOfNode(node: String): CodeService? = services.firstOrNull { node in it.nodes }

    companion object {
        val None = CodeResources(emptyList(), emptyList())
    }
}

object CodeAccess {
    const val User = "user"
    const val ServiceAccount = "service_account"
    const val Agent = "agent"
    const val Role = "role"
    const val Permission = "permission"
    const val Graph = "graph"
    const val Service = "service"
    const val Operation = "operation"
    const val Parent = "parent"
    const val GraphRelation = "graph"
    const val ServiceRelation = "service"
    const val Assignee = "assignee"
    const val Holder = "holder"
    const val Invoke = "can_invoke"
    const val Caller = "caller"
    const val Invoker = "invoker"
    const val Blocked = "blocked"
    const val Tenant = "tenant"
    const val Member = "member"
    const val Everyone = "everyone"
    const val Authenticated = "authenticated"
    const val Anonymous = "anonymous"

    val starter: String = """
        model
          schema 1.1

        type user

        type service_account

        type agent

        type role
          relations
            define assignee: [user, service_account, agent, role#assignee]

        type permission
          relations
            define holder: [user, service_account, agent, role#assignee]

        type graph
          relations
            define parent: [graph]
            define owner: [user, role#assignee] or owner from parent
            define operator: [user, service_account, agent, role#assignee] or owner or operator from parent
            define viewer: [user, service_account, agent, role#assignee] or operator or viewer from parent

        type service
          relations
            define graph: [graph]
            define owner: [user, role#assignee] or owner from graph
            define caller: [user, user:*, service_account, service_account:*, agent, agent:*, role#assignee, permission#holder] or owner or operator from graph
            define viewer: [user, service_account, agent, role#assignee] or caller or viewer from graph

        type operation
          relations
            define service: [service]
            define invoker: [user, user:*, service_account, service_account:*, agent, agent:*, role#assignee, permission#holder]
            define blocked: [user, service_account, agent, role#assignee]
            define can_invoke: (invoker or caller from service) but not blocked
    """.trimIndent() + "\n"

    fun key(name: String): String = name.trim().map { if (it.isWhitespace() || it == '#' || it == '@') '-' else it }.joinToString("")
        .let { if (it.isEmpty() || it == "*") "all" else it }

    fun role(name: String): ObjectRef = ObjectRef(Role, key(name))

    fun permission(name: String): ObjectRef = ObjectRef(Permission, key(name))

    fun service(contract: String): ObjectRef = ObjectRef(Service, key(contract))

    fun operation(contract: String, operation: String): ObjectRef = ObjectRef(Operation, "${key(contract)}.${key(operation)}")

    fun operationName(operation: String, request: String): String = operation.removePrefix("$request.")

    val builtInRoles: List<ObjectRef> get() = listOf(role(Everyone), role(Authenticated))

    fun subject(principal: PrincipalRef): Subject = Subject.of(when (principal.kind) {
        PrincipalKind.SERVICE -> ServiceAccount
        PrincipalKind.AGENT -> Agent
        else -> User
    }, key(principal.id))

    fun subject(context: AuthContext?): Subject = context?.let { subject(it.principal) } ?: Subject.of(User, Anonymous)

    fun contextual(context: AuthContext?): List<RelationTuple> =
        if (context == null) contextual(subject(null), authenticated = false)
        else contextual(subject(context), true, context.tenantId, context.roles.mapNotNull { it.name ?: it.id }, (context.permissions + context.scopes).map { it.name })

    fun contextual(subject: Subject, authenticated: Boolean = true, tenant: String? = null, roles: Collection<String> = emptyList(),
                   permissions: Collection<String> = emptyList()): List<RelationTuple> =
        listOf(RelationTuple(role(Everyone), Assignee, subject)) +
            listOfNotNull(RelationTuple(role(Authenticated), Assignee, subject).takeIf { authenticated }) +
            listOfNotNull(tenant?.takeIf(String::isNotBlank)?.let { RelationTuple(ObjectRef(Tenant, key(it)), Member, subject) }) +
            roles.map { RelationTuple(role(it), Assignee, subject) } +
            permissions.distinct().map { RelationTuple(permission(it), Holder, subject) }

}

class CodeAccessPolicy(
    val model: AuthorizationModel,
    tuples: List<RelationTuple>,
    val resources: CodeResources = CodeResources.None,
    val modes: Map<String, AccessMode> = emptyMap(),
    val fallback: AccessMode = AccessMode.Enforce,
) {
    val fga = Fga(model, tuples + resources.tuples, resources.objects)
    val digest: String = FgaDigest.of(model, fga.index.tuples)

    fun mode(contract: String): AccessMode = modes[CodeAccess.service(contract).toString()] ?: modes[FgaStore.EveryObject] ?: fallback

    fun check(context: AuthContext?, contract: String, operation: String, declared: List<RelationTuple> = emptyList(),
              atMillis: Long = Clock.System.now().toEpochMilliseconds()): AccessDecision =
        check(CodeAccess.subject(context), CodeAccess.contextual(context) + declared, contract, operation, context?.tenantId, atMillis)

    fun check(subject: Subject, contextual: List<RelationTuple>, contract: String, operation: String, tenant: String? = null,
              atMillis: Long = Clock.System.now().toEpochMilliseconds()): AccessDecision {
        val target = CodeAccess.operation(contract, operation)
        val structural = RelationTuple(target, CodeAccess.ServiceRelation, Subject.Object(CodeAccess.service(contract)))
        val result = fga.check(subject, CodeAccess.Invoke, target, contextual + structural)
        val facts = result.explanation?.facts.orEmpty().filter { it != structural }
        val blocked = if (result.allowed || result.error != null || model.rewrite(CodeAccess.Operation, CodeAccess.Blocked) == null) null
            else fga.check(subject, CodeAccess.Blocked, target, contextual + structural).takeIf { it.allowed && it.error == null }
        val rule = result.error ?: when {
            result.allowed -> (facts.firstOrNull { it !in contextual } ?: facts.firstOrNull { it.subject != subject } ?: facts.firstOrNull())?.toString()
                ?: result.explanation?.rule.orEmpty()
            blocked != null -> "Blocked by ${blocked.explanation?.facts.orEmpty().filter { it != structural }.let { list -> list.firstOrNull { it !in contextual } ?: list.firstOrNull() } ?: "$target#${CodeAccess.Blocked}"}"
            subject == CodeAccess.subject(null) -> "Nothing grants ${CodeAccess.Invoke} on $target to callers who are not signed in"
            else -> "Nothing grants ${CodeAccess.Invoke} on $target"
        }
        return AccessDecision(subject.toString(), CodeAccess.Invoke, target.toString(), result.allowed, mode(contract), rule,
            facts.map(RelationTuple::toString), digest, tenant, atMillis = atMillis, contextual = contextual.map(RelationTuple::toString))
    }

    fun holds(subject: Subject, contextual: List<RelationTuple>, permission: String, contract: String, tenant: String? = null,
              atMillis: Long = Clock.System.now().toEpochMilliseconds()): AccessDecision =
        relates(subject, contextual, CodeAccess.Holder, CodeAccess.permission(permission), contract, tenant, atMillis)

    fun relates(subject: Subject, contextual: List<RelationTuple>, relation: String, target: ObjectRef, contract: String, tenant: String? = null,
                atMillis: Long = Clock.System.now().toEpochMilliseconds()): AccessDecision {
        val result = fga.check(subject, relation, target, contextual)
        val facts = result.explanation?.facts.orEmpty()
        val rule = result.error ?: if (result.allowed) (facts.firstOrNull { it !in contextual } ?: facts.firstOrNull { it.subject != subject } ?: facts.firstOrNull())?.toString()
            ?: result.explanation?.rule.orEmpty() else "Nothing grants $relation on $target"
        return AccessDecision(subject.toString(), relation, target.toString(), result.allowed, mode(contract), rule,
            facts.map(RelationTuple::toString), digest, tenant, atMillis = atMillis, contextual = contextual.map(RelationTuple::toString))
    }

    companion object {
        fun load(model: String, store: String?, resources: CodeResources = CodeResources.None, fallback: AccessMode = AccessMode.Shadow): Result<CodeAccessPolicy> = runCatching {
            val parsed = FgaDsl.parse(model)
            val definition = parsed.model?.takeIf { parsed.ok } ?: error("The access model does not parse: ${parsed.issues.joinToString("; ")}")
            ModelRules.issues(definition).takeIf { it.isNotEmpty() }?.let { error("The access model has problems: ${it.joinToString("; ")}") }
            val grants = store?.takeIf(String::isNotBlank)?.let(FgaStore::decode) ?: FgaStore()
            CodeAccessPolicy(definition, grants.relationTuples(), resources, grants.modes, fallback)
        }

        fun denial(decision: AccessDecision, context: AuthContext?): AuthDecision.Deny =
            if (context == null) AuthDecision.Deny(AuthDenyReason.MISSING_AUTH_CONTEXT, 401, "Sign in to call ${decision.target}")
            else AuthDecision.Deny(AuthDenyReason.RESOURCE_DENIED, 403, decision.rule)
    }
}
