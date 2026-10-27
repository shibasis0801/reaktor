package dev.shibasis.reaktor.auth.graph

import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.fga.FgaStore
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.fga.RoleDefinition
import dev.shibasis.reaktor.auth.fga.Subject
import dev.shibasis.reaktor.auth.fga.TupleKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoleCatalogTest {
    private val member = RoleDefinition("member", "Member", "Everyone in the app", listOf("profile:read"))
    private val moderator = RoleDefinition("moderator", "Moderator", "Reviews reports", listOf("messages:moderate"), listOf("member"))
    private val admin = RoleDefinition("admin", "Administrator", "", listOf("app:admin", "messages:moderate"), listOf("moderator"))
    private val catalog = RoleCatalog(listOf(member, moderator, admin))

    @Test fun rolesBecomeHolderAndIncludeTuples() {
        assertEquals(setOf(
            "permission:profile:read#holder@role:member#assignee",
            "permission:messages:moderate#holder@role:moderator#assignee",
            "role:member#assignee@role:moderator#assignee",
            "permission:app:admin#holder@role:admin#assignee",
            "permission:messages:moderate#holder@role:admin#assignee",
            "role:moderator#assignee@role:admin#assignee",
        ), catalog.tuples.map { it.toString() }.toSet())
    }

    @Test fun includedRolesLendTheirPermissions() {
        assertEquals(listOf("moderator", "member"), catalog.included("admin"))
        assertEquals(listOf("app:admin", "messages:moderate", "profile:read"), catalog.permissions("admin"))
        assertEquals(mapOf("profile:read" to "member"), catalog.inherited("admin"))
        assertEquals(listOf("moderator", "admin"), catalog.includers("member"))
        assertEquals(emptyList(), catalog.issues)
    }

    @Test fun issuesNameTheRoleAndTheProblem() {
        val broken = RoleCatalog(listOf(
            RoleDefinition("loop-a", includes = listOf("loop-b")), RoleDefinition("loop-b", includes = listOf("loop-a")),
            RoleDefinition("everyone"), RoleDefinition("has space"), RoleDefinition("orphan", permissions = listOf("nogroup"), includes = listOf("ghost")),
            RoleDefinition("Twin"), RoleDefinition("twin"), RoleDefinition("x".repeat(51)),
        ))
        val texts = broken.issues.map { it.text }
        assertTrue("loop-a includes itself through loop-a → loop-b → loop-a." in texts, texts.toString())
        assertTrue("everyone is built in: every call already brings it, so it cannot be defined." in texts)
        assertTrue("has space can only use letters, digits and - _ . :" in texts)
        assertTrue("orphan includes ghost, which this catalog does not define." in texts)
        assertTrue("nogroup is not a permission name; write group:action, such as messages:moderate." in texts)
        assertTrue("2 roles are named Twin." in texts)
        assertTrue(texts.any { it.endsWith("is longer than the 50 characters heimdall keeps for a role name.") })
    }

    @Test fun theStoreKeepsRolesAndRenamesFollowIncludes() {
        val store = FgaStore("app", "app.fga", roles = listOf(member, moderator))
        assertEquals(store, FgaStore.decode(store.encode()))
        val renamed = store.withRole(member.copy(name = "people"), replacing = "member")
        assertEquals(listOf("people", "moderator"), renamed.roles.map { it.name })
        assertEquals(listOf("people"), renamed.role("moderator")?.includes)
        val removed = renamed.withoutRole("people")
        assertEquals(listOf("moderator"), removed.roles.map { it.name })
        assertEquals(emptyList(), removed.role("moderator")?.includes)
        assertEquals(listOf("member", "moderator", "admin"), store.withRole(admin).roles.map { it.name })
    }

    @Test fun aRoleKeepsItsGrantsThroughARenameAndReachesOnlyThem() {
        val reports = CodeAccess.service("app.reports")
        val read = CodeOperation(CodeAccess.operation("app.reports", "read"), reports, "read", "GET", "/reports", "", "")
        val write = CodeOperation(CodeAccess.operation("app.reports", "write"), reports, "write", "PUT", "/reports", "", "")
        val service = CodeService(reports, "Reports", "app.reports", null, null, emptySet(), emptyList(), emptyList(), listOf(read, write),
            listOf(RelationTuple(reports, CodeAccess.Caller, Subject.Userset(CodeAccess.permission("worker:server"), CodeAccess.Holder))))
        val viewer = RoleDefinition("viewer", includes = listOf("member"))
        val store = FgaStore("app", roles = listOf(member)).withRole(viewer, listOf(RelationTuple(read.ref, CodeAccess.Invoker, CodeAccess.holders("someone-else"))))
        assertEquals(listOf("operation:app.reports.read#invoker@role:viewer#assignee"), store.grants("viewer").map { it.toString() })
        val renamed = store.withRole(viewer.copy(name = "reader"), store.grants("viewer"), replacing = "viewer")
        assertEquals(emptyList(), renamed.grants("viewer"))
        assertEquals(listOf("operation:app.reports.read#invoker@role:reader#assignee"), renamed.grants("reader").map { it.toString() })
        assertEquals(listOf("member", "reader"), renamed.roles.map { it.name })
        val policy = CodeAccessPolicy.load(CodeAccess.starter, renamed.encode()).getOrThrow()
        val ada = Subject.of(CodeAccess.User, "ada")
        val reader = policy.reach(ada, CodeAccess.contextual(ada, roles = listOf("reader")), listOf(service))
        assertEquals(listOf(true, false), listOf(read, write).map { reader.getValue(it.ref).allowed })
        assertEquals("operation:app.reports.read#invoker@role:reader#assignee", reader.getValue(read.ref).rule)
        assertEquals(listOf(false, false), listOf(read, write).map { policy.reach(ada, CodeAccess.contextual(ada), listOf(service)).getValue(it.ref).allowed })
        assertTrue(policy.reach(ada, CodeAccess.contextual(ada, permissions = listOf("worker:server")), listOf(service)).values.all { it.allowed })
        assertEquals(emptyList(), renamed.withoutRole("reader").without(renamed.grants("reader")).tuples)
    }

    @Test fun thePolicyDecidesWithTheCatalog() {
        val store = FgaStore("app", roles = listOf(member, moderator, admin),
            tuples = listOf(TupleKey("permission:messages:moderate#holder", "caller", "service:app.moderation")),
            modes = mapOf(FgaStore.EveryObject to AccessMode.Enforce))
        val policy = CodeAccessPolicy.load(CodeAccess.starter, store.encode()).getOrThrow()
        fun call(vararg roles: String) = policy.check(Subject.of(CodeAccess.User, "ada"),
            CodeAccess.contextual(Subject.of(CodeAccess.User, "ada"), roles = roles.toList()), "app.moderation", "review")
        assertTrue(call("admin").allowed)
        assertTrue(call("moderator").allowed)
        assertFalse(call("member").allowed)
        assertFalse(call().allowed)
    }
}
