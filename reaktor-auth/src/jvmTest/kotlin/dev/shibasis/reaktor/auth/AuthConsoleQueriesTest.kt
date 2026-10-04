package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import dev.shibasis.reaktor.tooling.auth.AuditFilter
import dev.shibasis.reaktor.tooling.auth.AuthConsoleQueries
import dev.shibasis.reaktor.tooling.auth.SessionState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.*
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class AuthConsoleQueriesTest {
    private val url = "jdbc:h2:mem:reaktor_auth_test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"

    private class Seeded(val app: String, val user: String, val active: String, val ended: String, val fixture: String, val chain: List<String>)

    private fun seed(): Seeded {
        AuthDbFixture.ensure()
        val (app, user) = AuthDbFixture.seedUser()
        val now = Clock.System.now()
        val active = UUID.randomUUID().toString()
        val ended = UUID.randomUUID().toString()
        val fixture = UUID.randomUUID().toString()
        val family = UUID.randomUUID().toString()
        val chain = List(3) { UUID.randomUUID().toString() }
        transaction {
            Sessions.insert { it[Sessions.id] = Sessions.entityId(UUID.fromString(active))
                it.fields(Session(active, user, app, expiresAt = now + 20.days, data = EMPTY_JSON, createdAt = now - 2.hours)) }
            Sessions.insert { it[Sessions.id] = Sessions.entityId(UUID.fromString(ended))
                it.fields(Session(ended, user, app, expiresAt = now - 1.days, data = EMPTY_JSON, createdAt = now - 40.days)) }
            Sessions.insert { it[Sessions.id] = Sessions.entityId(UUID.fromString(fixture))
                it.fields(Session(fixture, user, app, expiresAt = now + 5.days, createdAt = now - 3.hours,
                    data = JsonObject(mapOf("bestbudsDevAuthFixture" to JsonPrimitive(true))))) }
            chain.forEachIndexed { index, id ->
                RefreshTokens.insert { it[RefreshTokens.id] = RefreshTokens.entityId(UUID.fromString(id))
                    it.fields(RefreshToken(id, active, "hash-$id", family, user, previousTokenId = chain.getOrNull(index - 1),
                        expiresAt = now + 20.days, usedAt = if (index < 2) now - 1.hours else null,
                        rotatedAt = if (index < 2) now - 1.hours else null, data = EMPTY_JSON, createdAt = now - (3 - index).hours)) }
            }
            fun event(type: AuthAuditEventType, outcome: AuthAuditOutcome, session: String?, reason: String? = null, credential: String? = null) =
                AuthAuditEvents.insert { it[AuthAuditEvents.id] = AuthAuditEvents.entityId(UUID.randomUUID())
                    it.fields(AuthAuditEvent(UUID.randomUUID().toString(), type, outcome, subjectPrincipalId = user, appId = app,
                        sessionId = session, credentialType = credential, reason = reason, ipAddress = "203.0.113.7",
                        userAgent = "BestBuds/1.0 iOS", data = EMPTY_JSON, createdAt = now - 1.hours)) }
            event(AuthAuditEventType.TOKEN_MINT, AuthAuditOutcome.SUCCESS, active, credential = "external_login")
            event(AuthAuditEventType.TOKEN_REFRESH, AuthAuditOutcome.SUCCESS, active, credential = "refresh_token")
            event(AuthAuditEventType.AUTH_FAILURE, AuthAuditOutcome.FAILURE, active, "invalid_refresh_token", "refresh_token")
            event(AuthAuditEventType.AUTH_FAILURE, AuthAuditOutcome.FAILURE, null, "invalid_refresh_token", "refresh_token")
        }
        return Seeded(app, user, active, ended, fixture, chain)
    }

    private fun rows(sql: String): List<Map<String, String?>> = DriverManager.getConnection(url).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery(sql.replace("heimdall.", "")).use { result ->
                val names = (1..result.metaData.columnCount).map { result.metaData.getColumnLabel(it).lowercase() }
                buildList { while (result.next()) add(names.associateWith { result.getString(it) }) }
            }
        }
    }

    @Test fun everyConsoleReadRunsAgainstTheRuntimeSchemaWithoutCredentialMaterial() {
        val seeded = seed()
        listOf(
            AuthConsoleQueries.pulse(), AuthConsoleQueries.pulse(seeded.app),
            AuthConsoleQueries.activity(), AuthConsoleQueries.activity(seeded.app, seeded.user),
            AuthConsoleQueries.failureReasons(), AuthConsoleQueries.providers(), AuthConsoleQueries.linkedAccounts(seeded.user),
            AuthConsoleQueries.sessions(), AuthConsoleQueries.sessions(state = SessionState.Revoked, search = "iOS"),
            AuthConsoleQueries.refreshChain(seeded.active),
            AuthConsoleQueries.events(AuditFilter()), AuthConsoleQueries.events(AuditFilter(outcome = "FAILURE", reason = "invalid_refresh_token")),
        ).forEach { sql ->
            val names = rows(sql).firstOrNull()?.keys.orEmpty()
            assertFalse(names.any { it in setOf("token_hash", "secret_hash", "private_key_ref", "data", "profile", "public_jwks", "client_secret_ref") }, sql.take(80))
        }
    }

    @Test fun pulseCountsOnlyTheScopedApp() {
        val seeded = seed()
        val pulse = rows(AuthConsoleQueries.pulse(seeded.app)).single()
        assertEquals("1", pulse["users"])
        assertEquals("2", pulse["active_sessions"])
        assertEquals("1", pulse["fixture_sessions"])
        assertEquals("1", pulse["live_refresh"])
        assertEquals("1", pulse["sign_ins_1d"])
        assertEquals("2", pulse["failures_1d"])
        assertEquals("1", pulse["active_30d"])
    }

    @Test fun sessionsCarryTheirRefreshChainAndTheDeviceTheAuditTrailSaw() {
        val seeded = seed()
        val sessions = rows(AuthConsoleQueries.sessions(principalId = seeded.user)).associateBy { it["session_id"] }
        val active = sessions.getValue(seeded.active)
        assertEquals("active", active["status"])
        assertEquals("3", active["refreshes"])
        assertEquals("1", active["live_refresh"])
        assertEquals("BestBuds/1.0 iOS", active["user_agent"])
        assertEquals("1", active["failures"])
        assertEquals("expired", sessions.getValue(seeded.ended)["status"])
        assertEquals("fixture", sessions.getValue(seeded.fixture)["origin"])
        assertEquals("real", active["origin"])
        assertEquals(listOf(seeded.active), rows(AuthConsoleQueries.sessions(principalId = seeded.user, state = SessionState.Active, fixtures = false)).map { it["session_id"] })
        assertEquals(listOf(seeded.fixture), rows(AuthConsoleQueries.sessions(principalId = seeded.user, fixtures = true)).map { it["session_id"] })
        val chain = rows(AuthConsoleQueries.refreshChain(seeded.active))
        assertEquals(seeded.chain, chain.map { it["token_id"] })
        assertEquals(listOf("rotated", "rotated", "live"), chain.map { it["status"] })
    }

    @Test fun activityAndFailuresGroupTheTrailByDayAndReason() {
        val seeded = seed()
        val series = rows(AuthConsoleQueries.activity(seeded.app))
        assertEquals("2", series.filter { it["series"] == "event" && it["event_type"] == "AUTH_FAILURE" }.sumOf { it["total"]!!.toInt() }.toString())
        assertEquals("1", series.single { it["series"] == "active" }["total"])
        assertEquals(mapOf("real" to 1, "fixture" to 1), series.filter { it["series"] == "session" }.groupBy { it["event_type"]!! }.mapValues { (_, rows) -> rows.sumOf { it["total"]!!.toInt() } })
        val reasons = rows(AuthConsoleQueries.failureReasons(seeded.app))
        assertEquals("invalid_refresh_token", reasons.single()["reason"])
        assertEquals("2", reasons.single()["total"])
        assertEquals("2", reasons.single()["last_day"])
        val failures = rows(AuthConsoleQueries.events(AuditFilter(appId = seeded.app, outcome = "FAILURE")))
        assertEquals(2, failures.size)
        assertEquals(1, rows(AuthConsoleQueries.events(AuditFilter(appId = seeded.app, sessionId = seeded.active, eventType = "AUTH_FAILURE"))).size)
    }

    @Test fun filtersRefuseAnythingThatCouldLeaveTheirLiteral() {
        seed()
        assertFails { AuthConsoleQueries.events(AuditFilter(reason = "x' OR '1'='1")) }
        assertFails { AuthConsoleQueries.events(AuditFilter(eventType = "TOKEN_MINT;")) }
        assertTrue(rows(AuthConsoleQueries.sessions(search = "a%")).isEmpty())
        assertFails { AuthConsoleQueries.sessions(appId = "not-a-uuid") }
        assertFails { AuthConsoleQueries.activity(days = 0) }
        assertTrue(AuthConsoleQueries.events(AuditFilter(search = "TOKEN_MINT")).contains("""ILIKE '%TOKEN\_MINT%' ESCAPE '\'"""))
        assertTrue(AuthConsoleQueries.sessions(page = 2).endsWith("LIMIT 200 OFFSET 400"))
    }

    @Test fun sharedSearchFiltersSessionsAndEventsBeforePaging() {
        val seeded = seed()
        assertEquals(listOf(seeded.active), rows(AuthConsoleQueries.sessions(principalId = seeded.user,
            search = "device:~iOS is:active -is:fixture age:1d")).map { it["session_id"] })
        assertTrue(rows(AuthConsoleQueries.sessions(principalId = seeded.user, search = "device:Android")).isEmpty())
        val events = rows(AuthConsoleQueries.events(AuditFilter(principalId = seeded.user,
            search = "reason:invalid_refresh_token is:failed -device:Android age:1d")))
        assertEquals(2, events.size)
        assertTrue(events.all { it["outcome"] == "FAILURE" })
        assertTrue(rows(AuthConsoleQueries.events(AuditFilter(principalId = seeded.user,
            search = "reason:x'OR'1'='1"))).isEmpty())
    }

    @Test fun exposureReadsTheCatalogForTheTwoSchemasThatMatter() {
        val sql = AuthConsoleQueries.exposure()
        assertTrue(sql.contains("aclexplode(c.relacl)"))
        assertTrue(sql.contains("n.nspname IN ('public', 'heimdall')"))
        assertTrue(sql.contains("c.relrowsecurity AS rls_enabled"))
        assertTrue(sql.contains("AS schema_create"))
    }
}
