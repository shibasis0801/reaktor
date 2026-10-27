package dev.shibasis.reaktor.auth.graph

import dev.shibasis.reaktor.auth.fga.FgaStore
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.fga.RoleDefinition
import dev.shibasis.reaktor.auth.fga.Subject
import dev.shibasis.reaktor.auth.kernel.Capability

data class RoleIssue(val role: String, val text: String)

class RoleCatalog(val roles: List<RoleDefinition>) {
    private val byName = roles.associateBy { it.name }

    val names: List<String> get() = roles.map { it.name }

    operator fun get(name: String): RoleDefinition? = byName[name]

    operator fun contains(name: String): Boolean = name in byName

    val tuples: List<RelationTuple> = roles.flatMap { role ->
        val assignees = Subject.Userset(CodeAccess.role(role.name), CodeAccess.Assignee)
        role.permissions.distinct().map { RelationTuple(CodeAccess.permission(it), CodeAccess.Holder, assignees) } +
            role.includes.distinct().map { RelationTuple(CodeAccess.role(it), CodeAccess.Assignee, assignees) }
    }.distinct()

    fun included(name: String): List<String> {
        val seen = linkedSetOf<String>()
        val queue = ArrayDeque(byName[name]?.includes.orEmpty())
        while (queue.isNotEmpty()) {
            val next = queue.removeFirst()
            if (next == name || !seen.add(next)) continue
            queue.addAll(byName[next]?.includes.orEmpty())
        }
        return seen.toList()
    }

    fun includers(name: String): List<String> = roles.filter { name in included(it.name) }.map { it.name }

    fun permissions(name: String): List<String> =
        (byName[name]?.permissions.orEmpty() + included(name).flatMap { byName[it]?.permissions.orEmpty() }).distinct().sorted()

    fun inherited(name: String): Map<String, String> {
        val own = byName[name]?.permissions.orEmpty().toSet()
        return included(name).flatMap { from -> byName[from]?.permissions.orEmpty().map { it to from } }
            .filter { it.first !in own }.distinctBy { it.first }.toMap()
    }

    val issues: List<RoleIssue>
        get() = roles.flatMap { role -> issues(role) } + roles.groupBy { it.name.trim().lowercase() }.filter { it.key.isNotEmpty() && it.value.size > 1 }
            .map { (_, same) -> RoleIssue(same.first().name, "${same.size} roles are named ${same.first().name}.") }

    fun issues(role: RoleDefinition): List<RoleIssue> {
        val name = role.name.trim()
        fun issue(text: String) = RoleIssue(role.name, text)
        return listOfNotNull(
            issue("Name the role.").takeIf { name.isEmpty() },
            issue("$name is longer than the $NameLimit characters heimdall keeps for a role name.").takeIf { name.length > NameLimit },
            issue("$name can only use letters, digits and - _ . :").takeIf { name.isNotEmpty() && !RoleName.matches(name) },
            issue("$name is built in: every call already brings it, so it cannot be defined.").takeIf { name in BuiltIn },
            cycle(role.name)?.let { issue("${role.name} includes itself through ${it.joinToString(" → ")}.") },
        ) + role.includes.filter { it !in byName }.map { issue("${role.name} includes $it, which this catalog does not define.") } +
            role.permissions.mapNotNull { permission -> permissionIssue(permission)?.let(::issue) }
    }

    private fun cycle(name: String): List<String>? {
        fun walk(current: String, path: List<String>): List<String>? {
            byName[current]?.includes.orEmpty().forEach { next ->
                if (next == name) return path + next
                if (next !in path) walk(next, path + next)?.let { return it }
            }
            return null
        }
        return walk(name, listOf(name))
    }

    companion object {
        const val NameLimit = 50
        const val PermissionLimit = 100
        val RoleName = Regex("[A-Za-z0-9_.:-]+")
        val BuiltIn = setOf(CodeAccess.Everyone, CodeAccess.Authenticated)

        fun of(store: FgaStore?): RoleCatalog = RoleCatalog(store?.roles.orEmpty())

        fun permissionIssue(permission: String): String? = when {
            Capability.parse(permission) == null -> "$permission is not a permission name; write group:action, such as messages:moderate."
            permission.length > PermissionLimit -> "$permission is longer than the $PermissionLimit characters heimdall keeps for a permission."
            else -> null
        }
    }
}

fun CodeAccess.tuples(store: FgaStore): List<RelationTuple> = store.relationTuples() + RoleCatalog.of(store).tuples
