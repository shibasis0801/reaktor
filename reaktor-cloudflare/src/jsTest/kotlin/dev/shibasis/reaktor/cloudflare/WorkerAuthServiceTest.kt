@file:OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
package dev.shibasis.reaktor.cloudflare

import dev.shibasis.reaktor.service.contract.Served
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.await
import kotlinx.coroutines.promise
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** reaktor-auth's contract served from one Worker over the real workerd D1, the way an app's `AuthAdapter` calls it. */
class WorkerAuthServiceTest {
    private class Host(val miniflare: Miniflare, val env: dynamic, val service: WorkerAuthService, val app: Hono, val erased: MutableList<String>) {
        val cloudflare get() = CloudflareContext(env.unsafeCast<CloudflareEnv>())
        val database get() = cloudflare.requireD1("DB")

        suspend fun call(method: String, path: String, token: String? = null, body: String? = null): Pair<Int, String> {
            val init: dynamic = js("({})")
            init.method = method
            val headers: dynamic = js("({})")
            headers["content-type"] = "application/json"
            if (token != null) headers["authorization"] = "Bearer $token"
            init.headers = headers
            if (body != null) init.body = body
            val url = "https://hearth.invalid$path"
            val request: dynamic = js("new Request(url, init)")
            val response: dynamic = app.fetch(request, env, js("({waitUntil:function(){}})")).unsafeCast<Promise<dynamic>>().await()
            return (response.status as Int) to response.text().unsafeCast<Promise<String>>().await()
        }

        suspend fun signIn(subject: String, provider: String = "google"): String =
            service.open(cloudflare, VerifiedIdentity(provider, subject, email = null, nonce = null)).second

        suspend fun count(table: String): Int =
            database.rawFirstOrNull("SELECT COUNT(*) AS n FROM $table")!!.requireInt("n")
    }

    private fun hosted(test: suspend Host.() -> Unit): Promise<Unit> = GlobalScope.promise {
        val options: dynamic = js("({})")
        options.modules = true
        options.script = "export default { fetch() { return new Response('auth-test'); } }"
        options.compatibilityDate = "2026-05-26"
        options.d1Databases = js("({ DB: 'worker-auth' })")
        val miniflare = Miniflare(options)
        try {
            val env: dynamic = js("({})")
            env.DB = miniflare.getD1Database("DB").await()
            val erased = mutableListOf<String>()
            val service = WorkerAuthService("hearth", "DB", { emptyList() }, onErase = { _, account -> erased += account })
            val host = Host(miniflare, env, service, Hono().mount(Served(service)).sync(service, "DB"), erased)
            (WorkerAuthService.SCHEMA + ";" + D1SyncLog.SCHEMA).split(';').map(String::trim).filter(String::isNotEmpty)
                .forEach { host.database.execute(it) }
            host.test()
        } finally {
            miniflare.dispose().await()
        }
    }

    private fun op(key: String, value: String, hlc: String) =
        """{"ops":[{"store":"listened","key":"$key","value":${if (value == "null") value else "\"$value\""},"hlc":"$hlc","device":"phone"}]}"""

    @Test fun signInRefusesAnotherAppAndAnUnverifiableToken() = hosted {
        val other = call("POST", "/auth/sign-in", body = """{"idToken":"not.a.token","appId":"cairn","provider":"GOOGLE","environment":"PROD"}""")
        assertEquals(400, other.first)
        assertTrue("InvalidAppId" in other.second, other.second)
        val forged = call("POST", "/auth/sign-in", body = """{"idToken":"not.a.token","appId":"hearth","provider":"GOOGLE","environment":"PROD"}""")
        assertEquals(400, forged.first)
        assertTrue("InvalidIdToken" in forged.second, forged.second)
        assertEquals(0, count("accounts"))
    }

    @Test fun oneAccountPerProviderAndSubjectAndASessionPerSignIn() = hosted {
        val phone = signIn("ada")
        val tablet = signIn("ada")
        signIn("ada", provider = "apple")
        assertNotEquals(phone, tablet)
        assertEquals(2, count("accounts"))
        assertEquals(3, count("sessions"))
        val (status, me) = call("POST", "/auth/session/me", phone, "{}")
        assertEquals(200, status)
        val account = JSON.parse<dynamic>(me).principalId as String
        assertEquals(account, JSON.parse<dynamic>(call("POST", "/auth/session/me", tablet, "{}").second).principalId as String)
        // Only the token's hash is stored.
        assertEquals(null, database.rawFirstOrNull("SELECT account FROM sessions WHERE token_hash = %V", phone))
    }

    @Test fun signingOutWithTheRefreshTokenAloneEndsTheSession() = hosted {
        val token = signIn("ada")
        val (refreshed, body) = call("POST", "/auth/session/refresh", body = """{"refreshToken":"$token"}""")
        assertEquals(200, refreshed)
        assertEquals(token, JSON.parse<dynamic>(body).tokenSet.refreshToken as String)
        // What AuthAdapter sends: the refresh token in the body, no Authorization header.
        assertEquals(200, call("POST", "/auth/session/logout", body = """{"refreshToken":"$token"}""").first)
        assertEquals(401, call("POST", "/auth/session/me", token, "{}").first)
        assertEquals(401, call("GET", "/sync/pull", token).first)
        assertEquals(401, call("POST", "/auth/session/refresh", body = """{"refreshToken":"$token"}""").first)
        assertEquals(401, call("POST", "/auth/session/logout", body = """{"refreshToken":"$token"}""").first)
    }

    @Test fun signingOutEverywhereEndsEverySessionOfThatAccountOnly() = hosted {
        val phone = signIn("ada")
        val tablet = signIn("ada")
        val other = signIn("grace")
        val (status, body) = call("POST", "/auth/session/logout-all", phone, "{}")
        assertEquals(200, status)
        assertEquals(2, JSON.parse<dynamic>(body).revokedSessions as Int)
        assertEquals(401, call("GET", "/sync/pull", tablet).first)
        assertEquals(200, call("GET", "/sync/pull", other).first)
    }

    @Test fun syncIsPerAccountAndNeedsASession() = hosted {
        val ada = signIn("ada")
        val grace = signIn("grace")
        assertEquals(401, call("POST", "/sync/push", body = op("ep-1", "50000", "001788782460000.000000.phone")).first)
        assertEquals(401, call("GET", "/sync/pull", "made-up").first)
        assertEquals(200 to """{"stored":1}""", call("POST", "/sync/push", ada, op("ep-1", "50000", "001788782460000.000000.phone")))
        assertEquals(400, call("POST", "/sync/push", ada, "hello").first)
        assertEquals(400, call("POST", "/sync/push", ada, op("ep-2", "1", "yesterday")).first)
        assertEquals(1, (JSON.parse<dynamic>(call("GET", "/sync/pull", ada).second).ops as Array<dynamic>).size)
        assertEquals(0, (JSON.parse<dynamic>(call("GET", "/sync/pull", grace).second).ops as Array<dynamic>).size)
    }

    @Test fun deletingTheAccountErasesItsDataThenItsSessionsThenItself() = hosted {
        val token = signIn("ada")
        val other = signIn("grace")
        call("POST", "/sync/push", token, op("ep-1", "50000", "001788782460000.000000.phone"))
        val account = JSON.parse<dynamic>(call("POST", "/auth/session/me", token, "{}").second).principalId as String
        val (status, body) = call("POST", "/auth/account/deactivate", token, "{}")
        assertEquals(200, status)
        assertEquals(true, JSON.parse<dynamic>(body).deactivated as Boolean)
        assertEquals(listOf(account), erased)
        assertEquals(401, call("GET", "/sync/pull", token).first)
        assertEquals(1, count("accounts"))
        assertEquals(1, count("sessions"))
        assertEquals(200, call("GET", "/sync/pull", other).first)
        assertEquals(401, call("POST", "/auth/account/deactivate", token, "{}").first)
    }

    @Test fun whatTheWorkerDoesNotOfferIsNotAnswered() = hosted {
        val token = signIn("ada")
        for (path in listOf("/auth/anonymous", "/auth/token", "/auth/pat/mint", "/auth/pat/verify")) {
            val (status, body) = call("POST", path, token, """{"appId":"hearth","name":"x","rawToken":"x","environment":"PROD"}""")
            assertEquals(501, status, "$path answered $status: $body")
        }
    }
}
