package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.tooling.auth.AuditFilter
import dev.shibasis.reaktor.tooling.auth.AuthConsoleQueries
import dev.shibasis.reaktor.tooling.auth.AuthTenancyQueries
import dev.shibasis.reaktor.tooling.database.BoundQuery
import dev.shibasis.reaktor.tooling.database.JdbcQueryParameters
import org.junit.Assume.assumeTrue
import org.postgresql.util.PSQLException
import java.io.File
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*

class AuthConsolePostgresTest {
    @Test fun postgresMatchesBoundSearchTextAndRefusesPatternsItCannotCompile() {
        val url = System.getenv("REAKTOR_AUTH_TEST_JDBC_URL")
        assumeTrue("Set REAKTOR_AUTH_TEST_JDBC_URL to a disposable local PostgreSQL database", url != null)
        require(url!!.startsWith("jdbc:postgresql://127.0.0.1:") || url.startsWith("jdbc:postgresql://localhost:"))
        DriverManager.getConnection(url).use { db ->
            db.autoCommit = false
            try {
                val (app, identity, principal, session) = List(4) { UUID.randomUUID().toString() }
                db.createStatement().use { statement ->
                    statement.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN CREATE ROLE anon; END IF; END $$")
                    statement.execute(File("src/jvmMain/kotlin/dev/shibasis/reaktor/auth/heimdall.sql").readText().replace("\\set ON_ERROR_STOP on", ""))
                    statement.execute("""
                        INSERT INTO heimdall.app (id, name) VALUES ('$app', 'bestbuds');
                        INSERT INTO heimdall.identity (id, primary_email) VALUES ('$identity', 'o''brien\ops@example.test');
                        INSERT INTO heimdall.principal (id, kind, identity_id) VALUES ('$principal', 'USER', '$identity');
                        INSERT INTO heimdall.session (id, principal_id, app_id, expires_at, created_at)
                            VALUES ('$session', '$principal', '$app', now() + interval '20 days', now() - interval '150 days');
                        INSERT INTO heimdall.auth_audit_event (event_type, outcome, subject_principal_id, app_id, session_id, reason, user_agent)
                            VALUES ('AUTH_FAILURE', 'FAILURE', '$principal', '$app', '$session', '100%_sure', 'Safari on macOS');
                    """.trimIndent())
                }
                fun read(query: BoundQuery): List<String> = db.prepareStatement(query.statement).use { statement ->
                    JdbcQueryParameters.bind(statement, query.parameters)
                    statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
                }
                fun refusal(query: BoundQuery): String? {
                    val savepoint = db.setSavepoint()
                    return try { read(query); null } catch (failure: PSQLException) { failure.sqlState } finally { db.rollback(savepoint) }
                }

                assertEquals(listOf(principal), read(AuthTenancyQueries.people(search = "email:o'brien\\ops")))
                assertEquals(1, read(AuthConsoleQueries.events(AuditFilter(search = "reason:100%_sure"))).size)
                assertTrue(read(AuthConsoleQueries.events(AuditFilter(search = "reason:1%sure"))).isEmpty())
                assertEquals(listOf(session), read(AuthConsoleQueries.sessions(search = "device:~\\mmacOS age:200d")))
                assertTrue(read(AuthConsoleQueries.sessions(search = "age:100d")).isEmpty())

                val nobody = UUID.randomUUID().toString()
                assertEquals("2201B", refusal(AuthConsoleQueries.sessions(principalId = nobody, search = "device:~a*+")))
                assertEquals("2201B", refusal(AuthConsoleQueries.events(AuditFilter(principalId = nobody, search = "-~\\p{L}"))))
                assertEquals("2201B", refusal(AuthTenancyQueries.people(appId = nobody, search = "name:~(?<first>o)")))

                db.createStatement().use { it.execute("SET LOCAL standard_conforming_strings = off") }
                assertTrue(read(AuthConsoleQueries.sessions(search = "device:~\\'OR'1'='1")).isEmpty())
            } finally {
                db.rollback()
            }
        }
    }
}
