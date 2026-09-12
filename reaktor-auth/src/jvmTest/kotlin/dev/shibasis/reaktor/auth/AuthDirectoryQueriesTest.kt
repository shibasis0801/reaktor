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

    /**
     * Search is interpolated, so the guard is the character set rather than escaping. Anything that
     * could close a literal or start a statement has to be refused before a query is built, and the
     * refusal has to be loud — a search that silently matched nothing would look like an empty
     * directory.
     */
    @Test fun searchRefusesAnythingThatCouldLeaveItsLiteral() {
        listOf(
            "alice' OR 1=1 --",
            "bob\\'; DROP TABLE heimdall.principal; --",
            "x%",
            "back\\\\slash",
            "semi;colon",
            "\"quoted\"",
        ).forEach { hostile ->
            assertFails(hostile) { AuthDirectoryQueries.read(AuthDirectory.Principals, search = hostile) }
        }
        assertFails { AuthDirectoryQueries.read(AuthDirectory.Principals, search = "a".repeat(121)) }
    }

    /**
     * Underscore is allowed because real searches contain it — `TOKEN_MINT`, `primary_email` — so it
     * has to be escaped instead. An unescaped one is a live single-character wildcard, which would
     * quietly return more rows than were asked for.
     */
    @Test fun underscoreIsEscapedRatherThanRefused() {
        val query = AuthDirectoryQueries.read(AuthDirectory.Audit, search = "TOKEN_MINT")
        assertTrue(query.contains("""ILIKE '%TOKEN\_MINT%' ESCAPE '\'"""), query.takeLast(300))
    }

    @Test fun searchReachesTheDatabaseForEveryDirectoryThatCanBeSearched() {
        val searchable = AuthDirectory.entries - setOf(
            AuthDirectory.Composition, AuthDirectory.UnusedAccess, AuthDirectory.StaleRefresh,
        )
        searchable.forEach { directory ->
            val query = AuthDirectoryQueries.read(directory, search = "alice@example.com")
            assertTrue(query.contains("ILIKE '%alice@example.com%' ESCAPE"), "$directory does not search")
        }
        // A blank term must not add a clause at all, or an unsearched read pays for a scan.
        assertFalse(AuthDirectoryQueries.read(AuthDirectory.Principals).contains("ILIKE"))
    }

    /**
     * The audit tab opens on failures, so the failure projection has to actually narrow — and it has
     * to narrow on what a success looks like rather than on an outcome value this code does not own,
     * since the column is a free varchar.
     */
    @Test fun theFailureProjectionNarrowsWithoutOwningTheOutcomeVocabulary() {
        val failures = AuthDirectoryQueries.read(AuthDirectory.AuthFailures)
        assertTrue(failures.contains("e.outcome NOT ILIKE 'succ%'"), failures.takeLast(400))
        // The unfiltered trail must not inherit the narrowing, or the full view would be a lie.
        assertFalse(AuthDirectoryQueries.read(AuthDirectory.Audit).contains("NOT ILIKE 'succ%'"))
        // Access issuance keeps its own filter and does not gain the failure one.
        val issuance = AuthDirectoryQueries.read(AuthDirectory.AccessIssuance)
        assertTrue(issuance.contains("TOKEN_MINT"))
        assertFalse(issuance.contains("NOT ILIKE 'succ%'"))
    }

    /** Provider clients are read to expose a gap; they must never carry the secret reference. */
    @Test fun providerClientsExposeConfigurationAndNeverTheSecretReference() {
        val query = AuthDirectoryQueries.read(AuthDirectory.ProviderClients)
        assertTrue(query.contains("c.client_id"), query)
        assertFalse(query.contains("c.client_secret_ref,"), "the secret reference is selected as a value")
        assertTrue(query.contains("THEN 'none' ELSE 'configured'"), "secret presence is not reduced to a flag")
    }

    /**
     * The findings and the composition read are the reason this pane can say anything the security
     * review only asserts, so they have to execute — against the real schema, not a shape test.
     */
    @Test fun findingsAndCompositionExecuteAndCarryTheirEvidence() {
        AuthDbFixture.ensure()
        val (app, _) = AuthDbFixture.seedUser()
        DriverManager.getConnection("jdbc:h2:mem:reaktor_auth_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE").use { connection ->
            listOf(
                AuthDirectory.Composition, AuthDirectory.StaleRefresh,
                AuthDirectory.OverScopedTokens, AuthDirectory.UnusedAccess, AuthDirectory.SigningKeys,
            ).forEach { directory ->
                val query = AuthDirectoryQueries.read(directory, app).replace("heimdall.", "")
                connection.createStatement().use { statement ->
                    statement.executeQuery(query).use { result ->
                        val names = (1..result.metaData.columnCount).map { result.metaData.getColumnLabel(it).lowercase() }
                        assertFalse(
                            names.any { it in setOf("token_hash", "secret_hash", "private_key_ref", "public_jwks") },
                            "$directory leaks credential material",
                        )
                        // Deliberately not a `when` over the enum: Kotlin emits a synthetic
                        // WhenMappings class for that, and the JUnit runner picks it up as a test
                        // class with nothing to run.
                        if (directory == AuthDirectory.Composition) {
                            assertEquals(listOf("bucket", "label", "total"), names)
                        }
                        if (directory == AuthDirectory.UnusedAccess) {
                            assertTrue("evidence" in names, "no evidence column")
                        }
                    }
                }
            }
        }
    }
}
