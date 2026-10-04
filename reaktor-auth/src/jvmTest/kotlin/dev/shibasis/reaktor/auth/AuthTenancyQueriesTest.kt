package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.db.roleScope
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import dev.shibasis.reaktor.tooling.auth.AuthTenancyQueries
import dev.shibasis.reaktor.tooling.auth.GrantScope
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.SqlExpressionBuilder.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class AuthTenancyQueriesTest {
    private val url = "jdbc:h2:mem:reaktor_auth_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"

    private class Tenancy(val app: String, val home: String, val away: String, val context: String, val person: String, val stranger: String)

    private fun grant(principal: String, app: String, role: String, permission: String, tenant: String? = null, context: String? = null) = transaction {
        val roleId = UUID.randomUUID()
        val permissionId = UUID.randomUUID()
        Roles.insert { it[Roles.id] = Roles.entityId(roleId); it.fields(Role(roleId.toString(), role, app, EMPTY_JSON)) }
        Permissions.insert { it[Permissions.id] = Permissions.entityId(permissionId); it.fields(Permission(permissionId.toString(), permission, app, EMPTY_JSON)) }
        RolePermissions.insert {
            val id = UUID.randomUUID()
            it[RolePermissions.id] = RolePermissions.entityId(id)
            it.fields(RolePermission(id.toString(), roleId.toString(), permissionId.toString(), EMPTY_JSON))
        }
        PrincipalRoles.insert {
            val id = UUID.randomUUID()
            it[PrincipalRoles.id] = PrincipalRoles.entityId(id)
            it.fields(PrincipalRole(id.toString(), principal, roleId.toString(), contextId = context, tenantId = tenant, data = EMPTY_JSON))
        }
    }

    private fun seed(): Tenancy {
        AuthDbFixture.ensure()
        val app = AuthDbFixture.seedApp()
        val home = AuthDbFixture.seedTenant(app, "home")
        val away = AuthDbFixture.seedTenant(app, "away")
        val context = UUID.randomUUID().toString()
        transaction { Contexts.insert { it[Contexts.id] = Contexts.entityId(UUID.fromString(context)); it.fields(Context(context, "studio", app, EMPTY_JSON)) } }
        val person = AuthDbFixture.seedUserPrincipalForIdentity(AuthDbFixture.seedIdentity("person-${UUID.randomUUID()}@example.test"), app, home)
        val stranger = AuthDbFixture.seedUserPrincipalForIdentity(AuthDbFixture.seedIdentity("stranger-${UUID.randomUUID()}@example.test"), AuthDbFixture.seedApp())
        grant(person, app, "reader", "notes.read")
        grant(person, app, "writer", "notes.write", tenant = home)
        grant(person, app, "auditor", "notes.audit", tenant = away)
        grant(person, app, "superadmin", "tenant.admin")
        grant(person, app, "editor", "notes.edit", tenant = home, context = context)
        grant(stranger, app, "reader", "notes.peek")
        return Tenancy(app, home, away, context, person, stranger)
    }

    private fun rows(sql: String): List<Map<String, String?>> = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql.replace("heimdall.", "")).use { result ->
                val names = (1..result.metaData.columnCount).map { result.metaData.getColumnLabel(it).lowercase() }
                buildList { while (result.next()) add(names.associateWith { result.getString(it) }) }
            }
        }
    }

    private fun resolved(principal: String, app: String, tenant: String?, context: String?): Set<String> = transaction {
        (PrincipalRoles innerJoin Roles innerJoin RolePermissions innerJoin Permissions).selectAll().where {
            (PrincipalRoles.principalId eq UUID.fromString(principal)) and (Roles.appId eq UUID.fromString(app)) and
                roleScope(tenant, context) and (Permissions.appId eq UUID.fromString(app))
        }.map { it[Permissions.name] }.toSet()
    }

    @Test fun effectivePermissionsAgreeWithTheResolverInEveryScope() {
        val seeded = seed()
        listOf(null to null, seeded.home to null, seeded.away to null, seeded.home to seeded.context).forEach { (tenant, context) ->
            val read = rows(AuthTenancyQueries.effectivePermissions(seeded.person, seeded.app, GrantScope(tenant, context)))
            val applied = read.filter { it["excluded_because"].isNullOrEmpty() }.mapNotNull { it["permission"] }.toSet()
            assertEquals(resolved(seeded.person, seeded.app, tenant, context), applied, "tenant=$tenant context=$context")
        }
        val home = rows(AuthTenancyQueries.effectivePermissions(seeded.person, seeded.app, GrantScope(seeded.home)))
        assertEquals("granted in another tenant", home.single { it["role"] == "auditor" }["excluded_because"])
        assertEquals("superadmin applies only where it is granted in this tenant", home.single { it["role"] == "superadmin" }["excluded_because"])
        assertEquals("granted in another context", home.single { it["role"] == "editor" }["excluded_because"])
    }

    @Test fun grantsNameWhatMakesThemDead() {
        val seeded = seed()
        val grants = rows(AuthTenancyQueries.grants(seeded.app)).groupBy { it["principal_id"] }
        val person = grants.getValue(seeded.person).associateBy { it["role"] }
        assertEquals("", person.getValue("reader")["problem"].orEmpty())
        assertEquals("", person.getValue("writer")["problem"].orEmpty())
        assertEquals("holder is not a member of this tenant", person.getValue("auditor")["problem"])
        assertEquals("superadmin only applies where granted in a tenant", person.getValue("superadmin")["problem"])
        assertEquals("holder is not a member of this context", person.getValue("editor")["problem"])
        assertEquals("holder is not a member of this app", grants.getValue(seeded.stranger).single()["problem"])
        assertEquals(setOf("app", "tenant", "context"), person.values.map { it["scope"] }.toSet())
        val home = rows(AuthTenancyQueries.grants(seeded.app, tenantId = seeded.home)).filter { it["principal_id"] == seeded.person }.map { it["role"] }.toSet()
        assertEquals(setOf("reader", "writer", "superadmin", "editor"), home)
    }

    @Test fun membershipsCarryOnlyTheRolesThatApplyInTheirTenant() {
        val seeded = seed()
        val membership = rows(AuthTenancyQueries.memberships(seeded.person)).single()
        assertEquals("home", membership["tenant"])
        assertEquals("reader, writer", membership["roles"])
        assertEquals("notes.read, notes.write", membership["permissions"])
        assertEquals("", membership["problem"].orEmpty())
    }

    @Test fun tenantsContextsRolesAndAppsCountTheirMembersAndGrants() {
        val seeded = seed()
        val tenants = rows(AuthTenancyQueries.tenants(seeded.app)).associateBy { it["tenant"] }
        assertEquals("1", tenants.getValue("home")["members"])
        assertEquals("2", tenants.getValue("home")["grants"])
        assertEquals("0", tenants.getValue("away")["members"])
        assertEquals("1", rows(AuthTenancyQueries.contexts(seeded.app)).single()["grants"])
        val roles = rows(AuthTenancyQueries.roles(seeded.app)).groupBy { it["role"] }
        assertEquals(2, roles.getValue("reader").size)
        assertEquals("1", roles.getValue("writer").single()["tenant_grants"])
        val app = rows(AuthTenancyQueries.apps()).single { it["app_id"] == seeded.app }
        assertEquals("2", app["tenants"])
        assertEquals("1", app["contexts"])
        assertEquals("6", app["roles"])
        assertEquals(6, rows(AuthTenancyQueries.matrix(seeded.app)).size)
    }

    @Test fun peopleFilterByAppTenantKindAndRole() {
        val seeded = seed()
        assertEquals(listOf(seeded.person), rows(AuthTenancyQueries.people(seeded.app, seeded.home)).map { it["principal_id"] })
        assertTrue(rows(AuthTenancyQueries.people(seeded.app, seeded.away)).isEmpty())
        assertTrue(rows(AuthTenancyQueries.people(seeded.app, kind = "service")).isEmpty())
        val found = rows(AuthTenancyQueries.people(search = "auditor")).map { it["principal_id"] }
        assertTrue(seeded.person in found)
        val person = rows(AuthTenancyQueries.people(seeded.app)).single { it["principal_id"] == seeded.person }
        assertEquals("home", person["tenants"])
        assertEquals("5", person["role_grants"])
    }

    @Test fun servicesShowTheirScopesAndUse() {
        val seeded = seed()
        val client = "svc-${UUID.randomUUID().toString().take(8)}"
        AuthDbFixture.seedServiceAccount(client, seeded.app, "SECRET", secretHash = "hash", scopes = "worker:server")
        val service = rows(AuthTenancyQueries.services()).single { it["client_id"] == client }
        assertEquals("SECRET", service["auth_method"])
        assertEquals("worker:server", service["scopes"])
        assertEquals("0", service["tokens_7d"])
        assertFalse("secret_hash" in service.keys)
    }

    @Test fun sharedSearchCombinesPeopleFieldsNegationAndRegex() {
        val seeded = seed()
        val people = rows(AuthTenancyQueries.people(seeded.app,
            search = "email:~^person- kind:user role:auditor -email:stranger age:1d"))
        assertEquals(listOf(seeded.person), people.map { it["principal_id"] })
        assertTrue(rows(AuthTenancyQueries.people(seeded.app, search = "role:auditor -role:writer")).isEmpty())
        assertTrue(rows(AuthTenancyQueries.people(seeded.app, search = "email:%")).isEmpty())
        assertTrue(rows(AuthTenancyQueries.people(seeded.app, search = "email:x'OR'1'='1")).isEmpty())
    }

    @Test fun scopesRefuseAnythingThatIsNotAnIdentifier() {
        assertFails { AuthTenancyQueries.tenants("x' OR 1=1 --") }
        assertFails { AuthTenancyQueries.effectivePermissions(UUID.randomUUID().toString(), UUID.randomUUID().toString(), GrantScope("nope")) }
        assertFails { AuthTenancyQueries.people(kind = "USER'; --") }
        assertFails { AuthTenancyQueries.people(page = -1) }
    }
}
