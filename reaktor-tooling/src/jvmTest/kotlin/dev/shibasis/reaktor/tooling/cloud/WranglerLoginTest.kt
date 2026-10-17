package dev.shibasis.reaktor.tooling.cloud

import dev.shibasis.reaktor.tooling.io.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.test.*

class WranglerLoginTest {
    private val now = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()

    private fun config(block: (File) -> Unit) {
        val root = Files.createTempDirectory("wrangler-login-").toFile()
        try { block(File(root, "default.toml")) } finally { root.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir"))) }
    }

    @Test fun aFreshTokenIsUsedWithoutRenewal() = config { file ->
        file.writeText("oauth_token = \"current\"\nexpiration_time = \"2026-10-16T13:00:00.000Z\"\nrefresh_token = \"stored\"\n")
        val login = WranglerLogin(file, tokenUrl = "http://127.0.0.1:9/never", clock = { now })
        assertEquals("current", runBlocking { login.token() })
    }

    @Test fun anExpiredTokenIsRenewedAndTheRotatedRefreshTokenIsKept() = config { file ->
        file.writeText("oauth_token = \"expired\"\nexpiration_time = \"2026-10-16T11:00:00.000Z\"\nrefresh_token = \"stored\"\nscopes = [ \"account:read\" ]\n")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"renewed","expires_in":3600,"refresh_token":"rotated","scope":"account:read"}"""))
            server.start()
            val login = WranglerLogin(file, tokenUrl = server.url("/oauth2/token").toString(), clientId = "client", clock = { now })
            assertEquals("renewed", runBlocking { login.token() })
            val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("POST", request.method)
            assertEquals("grant_type=refresh_token&refresh_token=stored&client_id=client", request.body.readUtf8())
            val text = file.readText()
            assertContains(text, "oauth_token = \"renewed\"")
            assertContains(text, "refresh_token = \"rotated\"")
            assertContains(text, "expiration_time = \"2026-10-16T13:00:00Z\"")
            assertContains(text, "scopes = [ \"account:read\" ]")
            assertEquals(setOf("OWNER_READ", "OWNER_WRITE"), Files.getPosixFilePermissions(file.toPath()).map { it.name }.toSet())
        }
    }

    @Test fun aRefusedRenewalAsksForASignIn() = config { file ->
        file.writeText("oauth_token = \"expired\"\nexpiration_time = \"2026-10-16T11:00:00.000Z\"\nrefresh_token = \"revoked\"\n")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant"}"""))
            server.start()
            val login = WranglerLogin(file, tokenUrl = server.url("/oauth2/token").toString(), clock = { now })
            assertContains(assertFailsWith<CloudflareFailure> { runBlocking { login.token() } }.message.orEmpty(), "invalid_grant")
            assertContains(file.readText(), "refresh_token = \"revoked\"")
        }
    }

    @Test fun noSignInAtAllSaysHowToSignIn() = config { file ->
        val login = WranglerLogin(file, tokenUrl = "http://127.0.0.1:9/never", clock = { now })
        assertContains(assertFailsWith<CloudflareFailure> { runBlocking { login.token() } }.message.orEmpty(), "wrangler login")
    }
}
