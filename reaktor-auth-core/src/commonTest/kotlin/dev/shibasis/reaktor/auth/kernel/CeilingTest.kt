package dev.shibasis.reaktor.auth.kernel

import dev.shibasis.reaktor.auth.fga.FgaDsl
import dev.shibasis.reaktor.auth.fga.RelationTuple
import dev.shibasis.reaktor.auth.graph.CodeAccess
import dev.shibasis.reaktor.auth.graph.CodeAccessPolicy
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.test.*

class CeilingTest {
    private fun ceil(version: Int, vararg scopes: String) = buildJsonObject {
        put(CredentialCeiling.Claim, buildJsonObject {
            put("version", version)
            putJsonArray("scopes") { scopes.forEach { add(it) } }
        })
    }

    private val admin = AuthContext(principal = PrincipalRef("operator", PrincipalKind.USER), appId = "app-a", tenantId = "tenant-a",
        audience = "workbench", method = AuthMethod.ACCESS_TOKEN, roles = setOf(RoleRef(name = "superadmin")))

    private fun billing() = permits("billing:write").inApp("app-a").inTenant("tenant-a").audience("workbench")

    @Test fun theClaimDecidesTheCeilingAndAnythingUnreadableClosesIt() {
        assertNull(CredentialCeiling.of(JsonObject(emptyMap())))
        assertEquals(CredentialCeiling(scopes = setOf("mcp:read")), CredentialCeiling.of(ceil(1, "mcp:read")))
        assertEquals(CredentialCeiling.Closed, CredentialCeiling.of(ceil(2, "mcp:read")))
        assertEquals(CredentialCeiling.Closed, CredentialCeiling.of(JsonObject(mapOf(CredentialCeiling.Claim to JsonPrimitive("mcp:read")))))
    }

    @Test fun aCeilingCoversWhatItsScopesImply() {
        val ceiling = CredentialCeiling(scopes = setOf("mcp:write", "auth:*", "docs:read@guides"))
        assertTrue(ceiling.covers("mcp:read"))
        assertTrue(ceiling.covers("auth:pat:mint"))
        assertTrue(ceiling.covers("docs:read@guides/setup"))
        assertFalse(ceiling.covers("docs:read"))
        assertFalse(ceiling.covers("billing:read"))
        assertEquals(listOf("mcp:read", "auth:pat:mint"), ceiling.narrow(listOf("mcp:read", "billing:read", "auth:pat:mint")))
        assertTrue(CredentialCeiling(scopes = setOf("*")).covers("billing:admin"))
        assertFalse(CredentialCeiling.Closed.covers("mcp:read"))
    }

    @Test fun theCeilingBoundsEvenASuperadminsCredential() {
        assertIs<AuthDecision.Allow>(LocalAuthorizer.authorize(admin, billing()))
        val bounded = admin.copy(claims = ceil(1, "mcp:read"))
        assertIs<AuthDecision.Allow>(LocalAuthorizer.authorize(bounded, permits("mcp:read").inApp("app-a").inTenant("tenant-a").audience("workbench")))
        assertEquals(AuthDenyReason.CREDENTIAL_CEILING, assertIs<AuthDecision.Deny>(LocalAuthorizer.authorize(bounded, billing())).reason)
        val unreadable = admin.copy(claims = JsonObject(mapOf(CredentialCeiling.Claim to JsonPrimitive(1))))
        assertEquals(AuthDenyReason.CREDENTIAL_CEILING, assertIs<AuthDecision.Deny>(LocalAuthorizer.authorize(unreadable, billing())).reason)
        assertIs<AuthDecision.Allow>(LocalAuthorizer.authorize(admin.copy(claims = ceil(1, "*")), billing()))
    }

    @Test fun aRelationGrantOnlyCountsWhenTheCeilingReachesIt() {
        val vault = CodeAccess.target(CodeAccess.graph(listOf("Lab")), "Vault", "vault")
        val policy = CodeAccessPolicy(FgaDsl.parse(CodeAccess.starter).model!!, listOf(
            RelationTuple.parse("node:lab/Vault#caller@role:keeper#assignee")!!,
            RelationTuple.parse("node:lab/Vault#caller@${CodeAccess.permission("vault:open")}#holder")!!))
        val keeper = admin.copy(roles = setOf(RoleRef(name = "keeper")))

        assertTrue(policy.check(keeper, vault).allowed)
        val capped = policy.check(keeper.copy(claims = ceil(1, "mcp:read")), vault)
        assertFalse(capped.allowed)
        assertEquals("The credential's ceiling [mcp:read] does not reach port:lab/Vault/vault", capped.rule)
        assertTrue(policy.check(keeper.copy(claims = ceil(1, "*")), vault).allowed)

        val opener = admin.copy(roles = emptySet(), permissions = setOf(PermissionRef(name = "vault:open")), claims = ceil(1, "vault:open"))
        assertTrue(policy.check(opener, vault).allowed)
    }
}
