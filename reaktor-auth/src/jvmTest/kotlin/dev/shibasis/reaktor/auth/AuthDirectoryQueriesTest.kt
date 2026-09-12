package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.tooling.auth.*
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import kotlin.test.*

class AuthDirectoryQueriesTest {
    @Test fun everyDirectoryExecutesAgainstTheRuntimeSchemaAndOmitsCredentialMaterial() {
        AuthDbFixture.ensure()
        val (app, principal) = AuthDbFixture.seedUser()
        DriverManager.getConnection("jdbc:h2:mem:reaktor_auth_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE").use { connection ->
            AuthDirectory.entries.forEach { directory ->
                // Exposed's test dialect uses the current schema; production explicitly uses heimdall.
                val query = AuthDirectoryQueries.read(directory, app, principal).replace("heimdall.", "")
                connection.createStatement().use { statement -> statement.executeQuery(query).use { result ->
                    val names = (1..result.metaData.columnCount).map { result.metaData.getColumnLabel(it).lowercase() }
                    assertFalse(names.any { it in setOf("token_hash", "secret_hash", "private_key_ref", "data", "profile", "public_jwks") }, directory.name)
                    if (directory == AuthDirectory.Principals) {
                        assertTrue(result.next())
                        assertEquals(principal, result.getString("principal_id"))
                        assertFalse(result.next())
                    }
                } }
            }
        }
    }

    @Test fun filtersCannotBecomeSqlAndPaginationIsBounded() {
        assertFails { AuthDirectoryQueries.read(AuthDirectory.Principals, appId = "x' OR 1=1 --") }
        assertFails { AuthDirectoryQueries.read(AuthDirectory.Sessions, page = -1) }
        assertFails { AuthDirectoryQueries.read(AuthDirectory.PersonalTokens, page = Int.MAX_VALUE) }
        assertTrue(AuthDirectoryQueries.read(AuthDirectory.Sessions, page = 2).endsWith("LIMIT 200 OFFSET 400"))
    }
}
