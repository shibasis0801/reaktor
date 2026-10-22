package dev.shibasis.reaktor.auth.kernel

import kotlin.test.*

class CapabilityTest {
    private fun holding(vararg permissions: String) = AuthContext(principal = PrincipalRef("operator", PrincipalKind.USER), appId = "app-a",
        audience = "workbench", method = AuthMethod.ACCESS_TOKEN, permissions = permissions.map { PermissionRef(name = it) }.toSet())

    private fun allows(context: AuthContext, permission: String) = LocalAuthorizer.authorize(context, permits(permission)) is AuthDecision.Allow

    @Test fun higherLevelsIncludeLowerOnesInTheirGroupOnly() {
        assertTrue(allows(holding("data:write"), "data:read"))
        assertFalse(allows(holding("data:read"), "data:write"))
        assertFalse(allows(holding("data:write"), "deploy:read"))
        assertFalse(allows(holding("messages:write"), "messages:moderate"))
        assertTrue(allows(holding("messages:admin"), "messages:moderate"))
        assertTrue(allows(holding("auth:*"), "auth:pat:mint"))
        assertTrue(allows(holding("*"), "deploy:write"))
    }

    @Test fun aGrantAtAPathCoversItsSubtreeAndNothingElse() {
        val postgres = holding("data:write@env:production/store:postgres")
        assertTrue(allows(postgres, "data:write@env:production/store:postgres/table:heimdall.role"))
        assertTrue(allows(postgres, "data:read@env:production/store:postgres"))
        assertFalse(allows(postgres, "data:write@env:production/store:clickhouse"))
        assertFalse(allows(postgres, "data:write@env:production"))
        assertFalse(allows(postgres, "data:write"))
        assertTrue(allows(holding("data:write"), "data:write@env:dev/store:d1"))
    }

    @Test fun capabilitiesRoundTripTheirText() {
        listOf("data:write", "auth:pat:mint", "*", "deploy:admin@env:production/worker:chat-service").forEach {
            assertEquals(it, Capability.parse(it).toString())
        }
        assertNull(Capability.parse("superadmin"))
        assertNull(Capability.parse("data:"))
    }
}
