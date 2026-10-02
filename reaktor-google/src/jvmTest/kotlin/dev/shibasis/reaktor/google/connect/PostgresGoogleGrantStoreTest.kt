package dev.shibasis.reaktor.google.connect

import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresGoogleGrantStoreTest {
    @Test
    fun `the schema hides grants from every role but its owner, checked inside a rolled-back transaction`() {
        val url = System.getenv(URL_VARIABLE)
        assumeTrue("$URL_VARIABLE is not set", !url.isNullOrBlank())
        DriverManager.getConnection(url).use { connection ->
            val existed = schemaExists(connection)
            connection.autoCommit = false
            try {
                val store = PostgresGoogleGrantStore(shared(connection))
                val key = GrantKey("verification-service", "verification-subject")
                runBlocking {
                    store.write(key, "v1.sealed-one")
                    store.write(key, "v1.sealed-two")
                    assertEquals("v1.sealed-two", store.read(key))
                    val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)
                    store.hold(HeldConsent("state-hash", key, "v1.consent", now), now.minusSeconds(600))
                    assertEquals(HeldConsent("state-hash", key, "v1.consent", now), store.take("state-hash"))
                    assertNull(store.take("state-hash"))
                }
                val roles = listOf("anon", "authenticated", "service_role", "authenticator").filter { role(connection, it) }
                val schema = PostgresGoogleGrantStore.SCHEMA_NAME
                val report = buildMap {
                    put("rowLevelSecurity", single(connection, "SELECT bool_and(relrowsecurity) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '$schema' AND c.relkind = 'r'"))
                    put("tables", single(connection, "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = '$schema' AND c.relkind = 'r'"))
                    put("policies", single(connection, "SELECT count(*) FROM pg_policies WHERE schemaname = '$schema'"))
                    roles.forEach { role ->
                        put("$role.schemaUsage", single(connection, "SELECT has_schema_privilege('$role', '$schema', 'USAGE')"))
                        put("$role.grantsSelect", single(connection, "SELECT has_table_privilege('$role', '$schema.grants', 'SELECT')"))
                        put("$role.grantsWrite", single(connection, "SELECT has_table_privilege('$role', '$schema.grants', 'INSERT,UPDATE,DELETE')"))
                        put("$role.consentsSelect", single(connection, "SELECT has_table_privilege('$role', '$schema.consents', 'SELECT')"))
                    }
                    roles.filter { it == "anon" || it == "authenticated" }.forEach { role -> put("$role.readDenied", deniedAs(connection, role, "SELECT count(*) FROM $schema.grants")) }
                    put("postgrestSchemas", single(connection, "SELECT coalesce(string_agg(setting, ';'), 'unset') FROM pg_roles, unnest(rolconfig) AS setting WHERE rolname = 'authenticator' AND setting LIKE 'pgrst.db_schemas=%'"))
                }
                println("google-connect verification: ${report.entries.joinToString { "${it.key}=${it.value}" }}")
                assertEquals("true", report["rowLevelSecurity"])
                assertEquals("2", report["tables"])
                assertEquals("0", report["policies"])
                roles.filter { it != "authenticator" }.forEach { role ->
                    assertEquals("false", report["$role.schemaUsage"], role)
                    assertEquals("false", report["$role.grantsSelect"], role)
                    assertEquals("false", report["$role.grantsWrite"], role)
                    assertEquals("false", report["$role.consentsSelect"], role)
                }
                roles.filter { it == "anon" || it == "authenticated" }.forEach { role -> assertTrue(report["$role.readDenied"] in setOf("true", "notMember"), role) }
                assertFalse(report.getValue("postgrestSchemas").split(Regex("[=,; ]+")).contains(schema))
            } finally {
                connection.rollback()
                connection.autoCommit = true
            }
            assertEquals(existed, schemaExists(connection))
            println("google-connect verification: rolledBack=true schemaExistsAfter=${schemaExists(connection)}")
        }
    }

    private fun schemaExists(connection: Connection): Boolean =
        single(connection, "SELECT count(*) FROM pg_namespace WHERE nspname = '${PostgresGoogleGrantStore.SCHEMA_NAME}'") != "0"

    private fun role(connection: Connection, name: String): Boolean = single(connection, "SELECT count(*) FROM pg_roles WHERE rolname = '$name'") != "0"

    private fun single(connection: Connection, query: String): String =
        connection.createStatement().use { statement ->
            statement.executeQuery(query).use { rows ->
                rows.next()
                if (rows.metaData.getColumnType(1) in setOf(Types.BOOLEAN, Types.BIT)) rows.getBoolean(1).toString() else rows.getString(1)
            }
        }

    private fun deniedAs(connection: Connection, role: String, query: String): String {
        if (single(connection, "SELECT pg_has_role(current_user, '$role', 'MEMBER')") != "true") return "notMember"
        val savepoint = connection.setSavepoint()
        return try {
            connection.createStatement().use { statement ->
                statement.execute("SET LOCAL ROLE $role")
                statement.executeQuery(query).use { }
            }
            "false"
        } catch (error: SQLException) {
            if (error.sqlState == "42501") "true" else "error:${error.sqlState}"
        } finally {
            connection.rollback(savepoint)
        }
    }

    private fun shared(connection: Connection): DataSource {
        val held = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
            when (method.name) {
                "commit", "rollback", "close", "setAutoCommit" -> null
                else -> try {
                    method.invoke(connection, *(args ?: emptyArray()))
                } catch (error: InvocationTargetException) {
                    throw error.targetException
                }
            }
        } as Connection
        return Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, _ ->
            if (method.name == "getConnection") held else throw UnsupportedOperationException(method.name)
        } as DataSource
    }

    private companion object {
        const val URL_VARIABLE = "GOOGLE_CONNECT_VERIFY_JDBC_URL"
    }
}
