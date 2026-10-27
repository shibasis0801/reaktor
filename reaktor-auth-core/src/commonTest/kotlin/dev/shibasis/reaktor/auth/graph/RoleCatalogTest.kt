package dev.shibasis.reaktor.auth.graph

import dev.shibasis.reaktor.auth.fga.AccessMode
import dev.shibasis.reaktor.auth.fga.FgaStore
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
