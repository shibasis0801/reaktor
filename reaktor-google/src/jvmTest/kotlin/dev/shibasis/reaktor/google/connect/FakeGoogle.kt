package dev.shibasis.reaktor.google.connect

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class TestClock(var now: Instant = Instant.now()) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advance(seconds: Long) {
        now = now.plusSeconds(seconds)
    }
}

class MemoryGrantStore : GoogleGrantStore {
    val grants = ConcurrentHashMap<GrantKey, String>()
    val consents = ConcurrentHashMap<String, Held>()
    val pending = ConcurrentHashMap<String, Held>()

    override suspend fun read(key: GrantKey): String? = grants[key]

    override suspend fun write(key: GrantKey, sealed: String) {
        grants[key] = sealed
    }

    override suspend fun delete(key: GrantKey): Boolean = grants.remove(key) != null

    override suspend fun holdConsent(consent: Held, staleBefore: Instant) {
        consents.values.removeIf { it.createdAt.isBefore(staleBefore) }
        consents[consent.hash] = consent
    }

    override suspend fun takeConsent(stateHash: String): Held? = consents.remove(stateHash)

    override suspend fun holdPending(pending: Held) {
        this.pending[pending.hash] = pending
    }

    override suspend fun takePending(handleHash: String): Held? = pending.remove(handleHash)

    override suspend fun pendingBefore(cutoff: Instant): List<Held> = pending.values.filter { it.createdAt.isBefore(cutoff) }.sortedBy { it.createdAt }
}

class FakeGoogle : Dispatcher() {
    data class Person(
        val sub: String = "google-sub-1",
        val email: String = "owner@example.test",
        val grant: (List<String>) -> List<String> = { it },
        val audience: String? = null,
        val issuer: String = "https://accounts.google.com",
        val refresh: Boolean = true,
    )

    private data class Code(val challenge: String, val redirectUri: String, val clientId: String, val scopes: List<String>, val person: Person)
    private data class Grant(val sub: String, val scopes: List<String>, var revoked: Boolean = false)
    private data class Access(val refresh: String, val scopes: List<String>, var expired: Boolean = false)

    val server = MockWebServer().apply { dispatcher = this@FakeGoogle }
    val clientId = "connector-test.apps.googleusercontent.com"
    val clientSecret = "connector-test-secret"
    val redirectUri = "https://connect.example.test/connect/google/callback"
    val revoked = CopyOnWriteArrayList<String>()
    val refreshCount = AtomicInteger()
    val exchanges = AtomicInteger()
    val apiRequests = CopyOnWriteArrayList<RecordedRequest>()
    val issuedAccess = CopyOnWriteArrayList<String>()
    val issuedRefresh = CopyOnWriteArrayList<String>()
    var rotate = false
    var api: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404).setBody(problem(404, "notFound")) }

    private val codes = ConcurrentHashMap<String, Code>()
    private val grants = ConcurrentHashMap<String, Grant>()
    private val access = ConcurrentHashMap<String, Access>()
    private val counter = AtomicInteger()

    fun endpoints() = GoogleEndpoints(
        consent = "https://accounts.google.com/o/oauth2/v2/auth",
        token = server.url("/token").toString(),
        revoke = server.url("/revoke").toString(),
        api = server.url("/").toString(),
    )

    fun client() = GoogleOAuthClient(clientId, clientSecret, redirectUri)

    fun consent(url: String, person: Person = Person()): Pair<String, String> {
        val query = query(URI(url).rawQuery)
        val asked = query.getValue("scope").split(' ').filter(String::isNotBlank)
        val code = "code-${counter.incrementAndGet()}"
        codes[code] = Code(query.getValue("code_challenge"), query.getValue("redirect_uri"), query.getValue("client_id"), person.grant(asked), person)
        return query.getValue("state") to code
    }

    fun expireAccess() {
        access.values.forEach { it.expired = true }
    }

    fun revokeEverything() {
        grants.values.forEach { it.revoked = true }
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.requestUrl!!.encodedPath
        return when (path) {
            "/token" -> token(query(request.body.readUtf8()))
            "/revoke" -> revoke(query(request.body.readUtf8()))
            else -> {
                apiRequests += request
                val bearer = request.getHeader("Authorization")?.removePrefix("Bearer ")
                val found = bearer?.let(access::get)
                when {
                    found == null || found.expired || grants[found.refresh]?.revoked == true -> json(401, problem(401, "authError"))
                    else -> api(request)
                }
            }
        }
    }

    private fun token(form: Map<String, String>): MockResponse {
        if (form["client_id"] != clientId || form["client_secret"] != clientSecret) return json(401, """{"error":"invalid_client"}""")
        return when (form["grant_type"]) {
            "authorization_code" -> {
                exchanges.incrementAndGet()
                val code = codes.remove(form["code"]) ?: return json(400, """{"error":"invalid_grant","error_description":"Malformed auth code."}""")
                if (code.redirectUri != form["redirect_uri"] || code.clientId != form["client_id"] || s256(form["code_verifier"].orEmpty()) != code.challenge) {
                    return json(400, """{"error":"invalid_grant","error_description":"Bad Request"}""")
                }
                val refresh = "1//refresh-${counter.incrementAndGet()}"
                grants[refresh] = Grant(code.person.sub, code.scopes)
                issuedRefresh += refresh
                val idToken = idToken(code.person)
                val issued = issue(refresh, code.scopes)
                json(200, issued.dropLast(1) + (if (code.person.refresh) ""","refresh_token":"$refresh"""" else "") + ""","id_token":"$idToken"}""")
            }
            "refresh_token" -> {
                refreshCount.incrementAndGet()
                val refresh = form["refresh_token"].orEmpty()
                val grant = grants[refresh]
                if (grant == null || grant.revoked) return json(400, """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}""")
                if (rotate) {
                    val next = "1//refresh-${counter.incrementAndGet()}"
                    grant.revoked = true
                    grants[next] = Grant(grant.sub, grant.scopes)
                    issuedRefresh += next
                    json(200, issue(next, grant.scopes).dropLast(1) + ""","refresh_token":"$next"}""")
                } else json(200, issue(refresh, grant.scopes))
            }
            else -> json(400, """{"error":"unsupported_grant_type"}""")
        }
    }

    private fun revoke(form: Map<String, String>): MockResponse {
        val token = form["token"].orEmpty()
        val grant = grants[token]
        if (grant == null || grant.revoked) return json(400, """{"error":"invalid_token"}""")
        grant.revoked = true
        revoked += token
        return MockResponse().setResponseCode(200)
    }

    private fun issue(refresh: String, scopes: List<String>): String {
        val token = "ya29.access-${counter.incrementAndGet()}"
        access[token] = Access(refresh, scopes)
        issuedAccess += token
        return """{"access_token":"$token","expires_in":3599,"scope":"${scopes.joinToString(" ")}","token_type":"Bearer"}"""
    }

    private fun idToken(person: Person): String {
        val now = Instant.now().epochSecond
        val header = encode("""{"alg":"RS256","typ":"JWT"}""")
        val payload = encode(
            """{"iss":"${person.issuer}","aud":"${person.audience ?: clientId}","sub":"${person.sub}","email":"${person.email}","email_verified":true,"iat":$now,"exp":${now + 3600}}""",
        )
        return "$header.$payload.c2lnbmF0dXJl"
    }

    companion object {
        fun json(status: Int, body: String): MockResponse = MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json; charset=UTF-8").setBody(body)

        fun problem(status: Int, reason: String, message: String = reason): String =
            """{"error":{"code":$status,"message":"$message","errors":[{"domain":"global","reason":"$reason","message":"$message"}]}}"""

        fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter(String::isNotBlank).associate { part ->
            val name = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8)
            val value = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8)
            name to value
        }

        fun s256(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

        private fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    }
}
