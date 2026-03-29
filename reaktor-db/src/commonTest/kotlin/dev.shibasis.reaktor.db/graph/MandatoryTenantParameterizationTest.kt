package dev.shibasis.reaktor.db.graph

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MandatoryTenantParameterizationTest {
    private val policy = MandatoryTenantParameterization()

    @Test
    fun bindsTenantIdIntoParameters() {
        val query = policy.bind(
            tenantId = "manna",
            query = TenantGraphQuery(
                cypher = "CREATE (d:Domain {tenant_id: \$tenant_id, code: \$code}) RETURN d",
                parameters = mapOf("code" to "m")
            )
        )

        assertEquals("manna", query.parameters[TenantParameterName])
        assertEquals("m", query.parameters["code"])
    }

    @Test
    fun rejectsQueriesWithoutTenantParameter() {
        assertFailsWith<InvalidTenantGraphQuery> {
            policy.bind(
                tenantId = "manna",
                query = TenantGraphQuery("RETURN 1")
            )
        }
    }

    @Test
    fun rejectsMatchWithoutTenantPropertyOnEveryNodePattern() {
        assertFailsWith<InvalidTenantGraphQuery> {
            policy.bind(
                tenantId = "manna",
                query = TenantGraphQuery(
                    "MATCH (a:Resource {tenant_id: \$tenant_id, key: \$from})-[:LINKS_TO]->(b:Resource {key: \$to}) RETURN a, b"
                )
            )
        }
    }

    @Test
    fun acceptsTenantScopedMatchClauses() {
        policy.bind(
            tenantId = "manna",
            query = TenantGraphQuery(
                cypher = "MATCH (a:Resource {tenant_id: \$tenant_id, key: \$from}), (b:Resource {tenant_id: \$tenant_id, key: \$to}) RETURN a, b",
                parameters = mapOf("from" to "spivak", "to" to "rudin")
            )
        )
    }

    @Test
    fun acceptsOptionalMatchWhenTenantScoped() {
        policy.bind(
            tenantId = "manna",
            query = TenantGraphQuery(
                cypher = "OPTIONAL MATCH (a:Resource {tenant_id: \$tenant_id, key: \$key}) RETURN a",
                parameters = mapOf("key" to "spivak")
            )
        )
    }
}
