package dev.shibasis.reaktor.auth.kernel

import kotlin.test.*

class TenantSuperadminTest {
    private val admin = AuthContext(principal = PrincipalRef("operator", PrincipalKind.USER), appId = "app-a",
        tenantId = "tenant-a", audience = "workbench", method = AuthMethod.ACCESS_TOKEN,
        roles = setOf(RoleRef(name = "superadmin")))

    @Test fun superadminAutomaticallySatisfiesNewPermissionsInItsTenant() {
        assertIs<AuthDecision.Allow>(LocalAuthorizer.authorize(admin,
            permits("future:capability:write").inApp("app-a").inTenant("tenant-a").audience("workbench")))
        assertIs<AuthDecision.Deny>(LocalAuthorizer.authorize(admin.copy(tenantId = null), permits("future:capability:write")))
        assertIs<AuthDecision.Deny>(LocalAuthorizer.authorize(admin.copy(roles = setOf(RoleRef(name = "admin"))), permits("future:capability:write")))
    }

    @Test fun superadminDoesNotBypassContextOrCredentialConstraints() {
        val requirements = listOf(permits("write").inApp("app-b"), permits("write").inTenant("tenant-b"),
            permits("write").audience("other"), permits("write").forServices(),
            permits("write").on(ResourceRef("record", contextId = "other-context")),
            AuthRequirement(roles = setOf(RoleRef(name = "separate-duty"))))
        requirements.forEach { assertIs<AuthDecision.Deny>(LocalAuthorizer.authorize(admin, it)) }
        assertIs<AuthDecision.Deny>(LocalAuthorizer.authorize(admin.copy(delegation = Delegation(admin.principal)), permits("write")))
    }
}
