package dev.shibasis.reaktor.google.connect

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleConnectorTest {
    private val google = FakeGoogle()
    private val store = MemoryGrantStore()
    private val clock = TestClock()
    private val connector = GoogleConnector(google.client(), store, GrantSealer(SECRET), google.endpoints(), clock)
    private val key = GrantKey("service-a", "person-1")

    @AfterTest
    fun stop() {
        google.server.shutdown()
    }

    private suspend fun finish(
        scopes: List<String> = listOf(CALENDAR),
        person: FakeGoogle.Person = FakeGoogle.Person(),
        grantKey: GrantKey = key,
    ): GoogleCompletion {
        val url = connector.begin(grantKey, scopes, RETURN, null)
        val (state, code) = google.consent(url, person)
        return connector.complete(state, code, null)
    }

    private suspend fun connect(
        scopes: List<String> = listOf(CALENDAR),
        person: FakeGoogle.Person = FakeGoogle.Person(),
        grantKey: GrantKey = key,
    ): GoogleOutcome {
        val done = finish(scopes, person, grantKey)
        assertEquals(GoogleOutcome.Pending, done.outcome)
        return connector.settle(grantKey, assertNotNull(done.handle))
    }

    private fun params(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    private fun scenario(block: suspend CoroutineScope.() -> Unit) = runBlocking { block() }

    @Test
    fun `the consent address asks for offline access with PKCE S256, every scope at once and a login hint`() = scenario {
        val url = URI(connector.begin(key, listOf(CALENDAR, DRIVE), RETURN, "owner@example.test"))
        val query = FakeGoogle.query(url.rawQuery)
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth", "${url.scheme}://${url.host}${url.path}")
        assertEquals("code", query["response_type"])
        assertEquals("offline", query["access_type"])
        assertEquals("true", query["include_granted_scopes"])
        assertEquals("consent", query["prompt"])
        assertEquals("S256", query["code_challenge_method"])
        assertEquals(google.redirectUri, query["redirect_uri"])
        assertEquals(google.clientId, query["client_id"])
        assertEquals("owner@example.test", query["login_hint"])
        assertEquals(listOf("openid", "email", CALENDAR, DRIVE), query.getValue("scope").split(' '))
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(query.getValue("code_challenge")))
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(query.getValue("state")))
        assertNull(FakeGoogle.query(URI(connector.begin(key, listOf(CALENDAR), RETURN, null)).rawQuery)["login_hint"])
        assertFailsWith<IllegalArgumentException> { connector.begin(key, listOf("https://evil.example/scope"), RETURN, null) }
    }

    @Test
    fun `the callback exchanges the code with the verifier and parks the grant behind a handle until it is settled`() = scenario {
        val url = connector.begin(key, listOf(CALENDAR), RETURN, null)
        val state = FakeGoogle.query(URI(url).rawQuery).getValue("state")
        val consent = store.consents.values.single()
        assertNotEquals(state, consent.hash)
        assertFalse(consent.sealed.contains(state))
        val (_, code) = google.consent(url)
        val done = connector.complete(state, code, null)
        assertEquals(RETURN, done.returnUrl)
        assertEquals(GoogleOutcome.Pending, done.outcome)
        val handle = assertNotNull(done.handle)
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(handle))
        assertNull(connector.grant(key))
        val parked = store.pending.values.single()
        assertNotEquals(handle, parked.hash)
        assertTrue(parked.sealed.startsWith("v1."))
        (google.issuedAccess + google.issuedRefresh + handle).forEach { assertFalse(parked.sealed.contains(it)) }
        assertEquals(GoogleOutcome.Connected, connector.settle(key, handle))
        assertEquals(listOf("openid", "email", CALENDAR), connector.grant(key)?.scopes)
        assertTrue(store.pending.isEmpty())
        val forged = connector.begin(key, listOf(CALENDAR), RETURN, null)
        val forgedState = FakeGoogle.query(URI(forged).rawQuery).getValue("state")
        val (_, other) = google.consent(forged.replace(Regex("code_challenge=[^&]+"), "code_challenge=${FakeGoogle.s256("another-verifier")}"))
        assertEquals(GoogleCompletion(RETURN, GoogleOutcome.Failed, null), connector.complete(forgedState, other, null))
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun `a state is single use, lives ten minutes, and an unknown one is refused without a return address`() = scenario {
        val url = connector.begin(key, listOf(CALENDAR), RETURN, null)
        val (state, code) = google.consent(url)
        assertEquals(GoogleOutcome.Pending, connector.complete(state, code, null).outcome)
        assertEquals(GoogleCompletion(null, GoogleOutcome.Expired, null), connector.complete(state, code, null))
        assertEquals(GoogleCompletion(null, GoogleOutcome.Expired, null), connector.complete("not-a-state", code, null))
        assertEquals(GoogleCompletion(null, GoogleOutcome.Expired, null), connector.complete(null, code, null))
        val late = connector.begin(key, listOf(CALENDAR), RETURN, null)
        val (lateState, lateCode) = google.consent(late)
        clock.advance(11 * 60)
        assertEquals(GoogleCompletion(RETURN, GoogleOutcome.Expired, null), connector.complete(lateState, lateCode, null))
        assertEquals(1, google.exchanges.get())
    }

    @Test
    fun `a declined consent is denied, and any other Google error fails, both without a handle`() = scenario {
        val declined = FakeGoogle.query(URI(connector.begin(key, listOf(CALENDAR), RETURN, null)).rawQuery).getValue("state")
        assertEquals(GoogleCompletion(RETURN, GoogleOutcome.Denied, null), connector.complete(declined, null, "access_denied"))
        val broken = FakeGoogle.query(URI(connector.begin(key, listOf(CALENDAR), RETURN, null)).rawQuery).getValue("state")
        assertEquals(GoogleCompletion(RETURN, GoogleOutcome.Failed, null), connector.complete(broken, null, "invalid_scope"))
        assertNull(connector.grant(key))
        assertTrue(store.pending.isEmpty())
        assertEquals(0, google.exchanges.get())
    }

    @Test
    fun `the id token must name this client as audience and Google as issuer`() = scenario {
        assertEquals(GoogleOutcome.Failed, finish(person = FakeGoogle.Person(audience = "someone-else.apps.googleusercontent.com")).outcome)
        assertEquals(GoogleOutcome.Failed, finish(person = FakeGoogle.Person(issuer = "https://evil.example")).outcome)
        assertNull(connector.grant(key))
        assertTrue(store.pending.isEmpty())
        assertEquals(2, google.revoked.size)
    }

    @Test
    fun `a partial grant keeps what Google granted, and a grant without offline access fails`() = scenario {
        assertEquals(GoogleOutcome.Partial, connect(listOf(CALENDAR, DRIVE), FakeGoogle.Person(grant = { scopes -> scopes - DRIVE })))
        assertEquals(listOf("openid", "email", CALENDAR), connector.grant(key)?.scopes)
        val stranger = GrantKey("service-a", "person-2")
        assertEquals(GoogleOutcome.Failed, connect(grantKey = stranger, person = FakeGoogle.Person(sub = "google-sub-9", refresh = false)))
        assertNull(connector.grant(stranger))
    }

    @Test
    fun `connecting another Google account revokes the grant it replaces`() = scenario {
        connect()
        val first = google.issuedRefresh.single()
        assertEquals("google-sub-1", connector.grant(key)?.accountId)
        assertEquals(GoogleOutcome.Connected, connect(person = FakeGoogle.Person(sub = "google-sub-2", email = "other@example.test")))
        assertEquals(listOf(first), google.revoked)
        assertEquals("other@example.test", connector.grant(key)?.account)
        assertEquals("google-sub-2", connector.grant(key)?.accountId)
    }

    @Test
    fun `a consent finished in another person's browser never attaches their Google account to the person who started it`() = scenario {
        val done = finish(person = FakeGoogle.Person(sub = "google-sub-victim", email = "victim@example.test"))
        val handle = assertNotNull(done.handle)
        val victim = GrantKey("service-a", "person-2")
        assertEquals(GoogleOutcome.Refused, connector.settle(victim, handle))
        assertNull(connector.grant(key))
        assertNull(connector.grant(victim))
        assertEquals(google.issuedRefresh, google.revoked)
        assertEquals(GoogleOutcome.Refused, connector.settle(key, handle))
        assertNull(connector.grant(key))
        assertTrue(store.pending.isEmpty())
    }

    @Test
    fun `a consent nobody settles is revoked and deleted by the next request after ten minutes`() = scenario {
        val handle = assertNotNull(finish().handle)
        clock.advance(9 * 60)
        assertNull(connector.grant(GrantKey("service-a", "someone-else")))
        assertEquals(1, store.pending.size)
        assertTrue(google.revoked.isEmpty())
        clock.advance(2 * 60)
        assertNull(connector.grant(GrantKey("service-a", "someone-else")))
        assertTrue(store.pending.isEmpty())
        assertEquals(google.issuedRefresh, google.revoked)
        assertEquals(GoogleOutcome.Refused, connector.settle(key, handle))
        assertNull(connector.grant(key))
    }

    @Test
    fun `a replayed handle is refused and leaves the settled grant alone`() = scenario {
        val handle = assertNotNull(finish().handle)
        assertEquals(GoogleOutcome.Connected, connector.settle(key, handle))
        assertEquals(GoogleOutcome.Refused, connector.settle(key, handle))
        assertEquals(GoogleOutcome.Refused, connector.settle(key, "A".repeat(43)))
        assertEquals(GoogleOutcome.Refused, connector.settle(key, "not a handle"))
        assertNotNull(connector.grant(key))
        assertTrue(google.revoked.isEmpty())
    }

    @Test
    fun `an expired handle is refused and its grant revoked`() = scenario {
        val handle = assertNotNull(finish().handle)
        clock.advance(9 * 60 + 45)
        assertNull(connector.grant(GrantKey("service-a", "someone-else")))
        assertEquals(1, store.pending.size)
        clock.advance(30)
        assertEquals(GoogleOutcome.Refused, connector.settle(key, handle))
        assertNull(connector.grant(key))
        assertTrue(store.pending.isEmpty())
        assertEquals(google.issuedRefresh, google.revoked)
    }

    @Test
    fun `settling with the right subject but another service is refused`() = scenario {
        val handle = assertNotNull(finish().handle)
        val elsewhere = GrantKey("service-b", key.subject)
        assertEquals(GoogleOutcome.Refused, connector.settle(elsewhere, handle))
        assertNull(connector.grant(elsewhere))
        assertEquals(GoogleOutcome.Refused, connector.settle(key, handle))
        assertNull(connector.grant(key))
        assertEquals(google.issuedRefresh, google.revoked)
    }

    @Test
    fun `grants are sealed per service and subject, and the store never holds a token`() = scenario {
        connect()
        val other = GrantKey("service-b", "person-1")
        val sealed = store.grants.getValue(key)
        assertTrue(sealed.startsWith("v1."))
        (google.issuedAccess + google.issuedRefresh).forEach { token -> assertFalse(sealed.contains(token)) }
        store.grants[other] = sealed
        assertNull(connector.grant(other))
        store.grants[GrantKey("service-a", "person-2")] = sealed
        assertNull(connector.grant(GrantKey("service-a", "person-2")))
        store.grants[key] = sealed.dropLast(2) + if (sealed.endsWith("AA")) "BB" else "AA"
        assertNull(connector.grant(key))
        assertIs<GoogleCall.NoGrant>(connector.call(key, "calendar", "calendars.get", params("""{"calendarId":"x"}""")))
    }

    @Test
    fun `the sealer opens only what it sealed for the same key and purpose`() = scenario {
        val sealer = GrantSealer(SECRET)
        val sealed = sealer.seal(key, "grant", "a secret value")
        assertEquals("a secret value", sealer.open(key, "grant", sealed))
        assertNotEquals(sealed, sealer.seal(key, "grant", "a secret value"))
        assertNull(sealer.open(key, "consent", sealed))
        assertNull(sealer.open(GrantKey("service-a", "person-2"), "grant", sealed))
        assertNull(GrantSealer(SECRET.reversed()).open(key, "grant", sealed))
        assertNull(sealer.open(key, "grant", "v2.$sealed"))
        assertNull(sealer.open(key, "grant", "v1.%%%"))
        assertFailsWith<IllegalArgumentException> { GrantSealer("too short") }
    }

    @Test
    fun `an operation needs a granted scope and never reaches Google without one`() = scenario {
        connect(listOf(CALENDAR))
        val refused = connector.call(key, "drive", "files.get", params("""{"fileId":"abc"}"""))
        assertIs<GoogleCall.ScopeMissing>(refused)
        assertTrue(DRIVE in refused.anyOf)
        assertIs<GoogleCall.UnknownOperation>(connector.call(key, "calendar", "calendarList.list", params("{}")))
        assertIs<GoogleCall.UnknownOperation>(connector.call(key, "gmail", "messages.list", params("{}")))
        assertIs<GoogleCall.NoGrant>(connector.call(GrantKey("service-b", "person-1"), "calendar", "calendars.get", params("""{"calendarId":"x"}""")))
        assertTrue(google.apiRequests.isEmpty())
    }

    @Test
    fun `an expired access token is refreshed once for concurrent calls, and a rotated refresh token replaces the old one`() = scenario {
        connect()
        google.rotate = true
        google.api = { FakeGoogle.json(200, """{"kind":"calendar#calendar","id":"cal-1"}""") }
        clock.advance(3600)
        val results = (1..4).map { async { connector.call(key, "calendar", "calendars.get", params("""{"calendarId":"cal-1"}""")) } }.awaitAll()
        results.forEach { assertIs<GoogleCall.Done>(it) }
        assertEquals(1, google.refreshCount.get())
        val rotated = google.issuedRefresh.last()
        clock.advance(3600)
        assertIs<GoogleCall.Done>(connector.call(key, "calendar", "calendars.get", params("""{"calendarId":"cal-1"}""")))
        assertEquals(2, google.refreshCount.get())
        assertNotEquals(rotated, google.issuedRefresh.last())
        assertEquals(3, google.issuedRefresh.size)
    }

    @Test
    fun `a 401 from Google refreshes the grant and retries once`() = scenario {
        connect()
        google.api = { FakeGoogle.json(200, """{"kind":"calendar#calendar","id":"cal-1"}""") }
        google.expireAccess()
        assertIs<GoogleCall.Done>(connector.call(key, "calendar", "calendars.get", params("""{"calendarId":"cal-1"}""")))
        assertEquals(1, google.refreshCount.get())
        assertEquals(2, google.apiRequests.size)
    }

    @Test
    fun `a grant Google revoked is forgotten`() = scenario {
        connect()
        google.revokeEverything()
        clock.advance(3600)
        assertIs<GoogleCall.Revoked>(connector.call(key, "calendar", "calendars.get", params("""{"calendarId":"cal-1"}""")))
        assertNull(connector.grant(key))
        assertTrue(store.grants.isEmpty())
    }

    @Test
    fun `revoking revokes the refresh token at Google and deletes the grant`() = scenario {
        connect()
        val refresh = google.issuedRefresh.single()
        assertEquals(true, connector.revoke(key))
        assertEquals(listOf(refresh), google.revoked)
        assertNull(connector.grant(key))
        assertNull(connector.revoke(key))
        assertIs<GoogleCall.NoGrant>(connector.call(key, "calendar", "calendars.get", params("""{"calendarId":"cal-1"}""")))
    }

    @Test
    fun `Google errors come back with their status and reason, and bad parameters never reach Google`() = scenario {
        connect()
        google.api = { FakeGoogle.json(404, FakeGoogle.problem(404, "notFound", "Not Found")) }
        assertEquals(GoogleCall.Failed(404, "notFound", "Not Found"), connector.call(key, "calendar", "calendars.get", params("""{"calendarId":"gone"}""")))
        google.apiRequests.clear()
        assertIs<GoogleCall.Invalid>(connector.call(key, "calendar", "calendars.get", params("{}")))
        assertIs<GoogleCall.Invalid>(connector.call(key, "calendar", "events.insert", params("""{"calendarId":"c","body":"not an object"}""")))
        assertTrue(google.apiRequests.isEmpty())
    }

    companion object {
        const val SECRET = "connector-test-key-0123456789abcdefghijklmnop"
        const val RETURN = "https://app.example.test/settings/connections"
        const val CALENDAR = "https://www.googleapis.com/auth/calendar.app.created"
        const val DRIVE = "https://www.googleapis.com/auth/drive.file"
    }
}
