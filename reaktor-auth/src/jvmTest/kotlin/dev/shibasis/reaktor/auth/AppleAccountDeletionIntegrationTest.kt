package dev.shibasis.reaktor.auth

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.nimbusds.jose.proc.JWSVerificationKeySelector
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import dev.shibasis.reaktor.auth.jwt.UserAuthenticationProvider
import dev.shibasis.reaktor.auth.runtime.AppleRevocationClient
import dev.shibasis.reaktor.auth.runtime.AppleRevocationSettings
import dev.shibasis.reaktor.auth.runtime.AuthAccess
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeConfig
import dev.shibasis.reaktor.auth.spring.AuthSpringConfiguration
import dev.shibasis.reaktor.core.framework.json
import org.springframework.context.support.GenericApplicationContext
import org.springframework.core.ResolvableType
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.Optional
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Supplier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSession
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.api.DeactivateAccountRequest
import dev.shibasis.reaktor.auth.api.RefreshRequest
import dev.shibasis.reaktor.auth.kernel.PrincipalStatus
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeGraph
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.contract.LocalLink
import dev.shibasis.reaktor.service.contract.over
import kotlinx.coroutines.runBlocking
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppleAccountDeletionIntegrationTest {
    private val appleKey = ECKeyGenerator(Curve.P_256).generate()
    private val settings = AppleRevocationSettings(
        "synthetic-app", Environment.STAGE, "synthetic-client", "synthetic-team",
        "synthetic-key", "SYNTHETIC_APPLE_REVOCATION_SECRET",
    )

    private fun pem(encoded: ByteArray): String =
        "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(encoded)}\n-----END PRIVATE KEY-----"

    private fun springRuntime(secret: String?, allowEphemeral: Boolean = true): GenericApplicationContext {
        AuthDbFixture.ensure()
        val context = GenericApplicationContext()
        val configuration = object : AuthSpringConfiguration() {
            override fun appleRevocationSecret(name: String): String? {
                assertEquals(settings.privateKeyEnvironmentVariable, name)
                return secret
            }
        }
        context.registerBean("authRuntime", AuthRuntimeGraph::class.java, Supplier {
            configuration.reaktorAuthRuntime(
                context, AuthDbFixture.adapter(),
                context.getBeanProvider<List<UserAuthenticationProvider>>(
                    ResolvableType.forClassWithGenerics(List::class.java, UserAuthenticationProvider::class.java)),
                context.getBeanProvider(UserAuthenticationProvider::class.java),
                context.getBeanProvider(AuthAccess::class.java),
                "", "", allowEphemeral, "", "", json.encodeToString(listOf(settings)),
            )
        })
        try {
            context.refresh()
            return context
        } catch (error: Exception) {
            context.close()
            throw error
        }
    }

    private suspend fun assertDeletion(runtime: AuthRuntimeGraph, code: String? = null) {
        val (appId, principalId) = AuthDbFixture.seedUser()
        val principal = assertNotNull(AuthDbFixture.principalById(principalId))
        AuthDbFixture.seedProviderAccount(assertNotNull(principal.identityId), "linked-subject", UserProvider.APPLE)
        val api = AuthService() over LocalLink(runtime.service)
        val session = runtime.sessionLifecycle.createSession(principalId, appId, environment = Environment.STAGE)
        val tokens = assertNotNull(api.sessionRefresh(RefreshRequest(session.rawRefreshToken, environment = Environment.STAGE)).tokenSet)
        val request = DeactivateAccountRequest(
            audience = appId, environment = Environment.STAGE,
            headers = mutableMapOf("Authorization" to "Bearer ${tokens.accessToken}"),
            appleAuthorizationCode = code, appleClientId = settings.clientId,
        )
        assertTrue(api.accountDeletionOptions(request).appleLinked)
        assertTrue(api.accountDeactivate(request).deactivated)
        assertEquals(PrincipalStatus.SOFT_DELETED, AuthDbFixture.principalById(principalId)?.status)
        assertEquals(StatusCode.UNAUTHORIZED, api.sessionRefresh(RefreshRequest(session.rawRefreshToken, environment = Environment.STAGE)).statusCode)
    }

    private suspend fun assertSpringServes(secret: String?) {
        springRuntime(secret).use { context ->
            assertTrue(context.isActive)
            assertDeletion(context.getBean(AuthRuntimeGraph::class.java))
        }
    }

    @Test
    fun springStartsAndServesDeletionWithUnsetAppleKey() = runBlocking { assertSpringServes(null) }

    @Test
    fun springStartsAndServesDeletionWithEmptyAppleKey() = runBlocking { assertSpringServes("") }

    @Test
    fun springStartsAndServesDeletionWithEscapedAppleKey() = runBlocking {
        val secret = "\"${pem(appleKey.toECPrivateKey().encoded).replace("\n", "\\r\\n")}\""
        val loaded = AppleRevocationClient.from(settings, secret)
        assertFalse(loaded.clientSecret().isBlank())
        assertSpringServes(secret)
    }

    @Test
    fun springStartsAndServesDeletionWithMalformedAppleKey() = runBlocking { assertSpringServes("invalid-key") }

    @Test
    fun springStartsAndServesDeletionWithNonP256AppleKey() = runBlocking {
        assertSpringServes(pem(ECKeyGenerator(Curve.P_384).generate().toECPrivateKey().encoded))
    }

    @Test
    fun springStartsAndServesDeletionWithWrongKeyType() = runBlocking {
        assertSpringServes(pem(RSAKeyGenerator(2048).generate().toRSAPrivateKey().encoded))
    }

    @Test
    fun optionalAppleFallbackDoesNotRelaxRequiredJwtSigningKey() {
        assertFailsWith<Exception> { springRuntime(null, allowEphemeral = false) }
    }

    private suspend fun assertMockedDeletion(
        token: String = identityToken(),
        respond: (String, Int) -> Pair<Int, String>,
    ): List<String> {
        val client = ReplyHttpClient(respond)
        val (appId, principalId) = AuthDbFixture.seedUser()
        val runtime = AuthRuntimeGraph.create(AuthDbFixture.adapter(), AuthRuntimeConfig(
            appleRevocationClients = listOf(AppleRevocationClient.from(settings.copy(appId = appId), pem(appleKey.toECPrivateKey().encoded))),
            appleRevocationHttpClient = client,
        ))
        runtime.jwt.verifier.getProcessor(UserAuthenticationProvider.Apple(listOf(settings.clientId))).jwsKeySelector =
            JWSVerificationKeySelector(JWSAlgorithm.RS256, ImmutableJWKSet(JWKSet(identityKey.toPublicJWK())))
        val principalAppId = appId
        val principal = assertNotNull(AuthDbFixture.principalById(principalId))
        AuthDbFixture.seedProviderAccount(assertNotNull(principal.identityId), "linked-subject", UserProvider.APPLE)
        val api = AuthService() over LocalLink(runtime.service)
        val session = runtime.sessionLifecycle.createSession(principalId, principalAppId, environment = Environment.STAGE)
        val tokens = assertNotNull(api.sessionRefresh(RefreshRequest(session.rawRefreshToken, environment = Environment.STAGE)).tokenSet)
        client.identityReply = """{"id_token":"$token","refresh_token":"synthetic-refresh"}"""
        assertTrue(api.accountDeactivate(DeactivateAccountRequest(
            audience = principalAppId, environment = Environment.STAGE,
            headers = mutableMapOf("Authorization" to "Bearer ${tokens.accessToken}"),
            appleAuthorizationCode = "synthetic-code", appleClientId = settings.clientId,
        )).deactivated)
        assertEquals(PrincipalStatus.SOFT_DELETED, AuthDbFixture.principalById(principalId)?.status)
        assertEquals(StatusCode.UNAUTHORIZED, api.sessionRefresh(RefreshRequest(session.rawRefreshToken, environment = Environment.STAGE)).statusCode)
        return client.paths
    }

    @Test
    fun rejectedExchangeCodesStillDeactivateThroughTheRuntimeGraph() = runBlocking {
        AuthDbFixture.ensure()
        for (error in listOf("invalid_client", "invalid_grant")) {
            assertEquals(listOf("/auth/token"), assertMockedDeletion { _, _ -> 400 to """{"error":"$error"}""" })
        }
    }

    @Test
    fun transientAndTimeoutExchangeFailuresStillDeactivateThroughTheRuntimeGraph() = runBlocking {
        AuthDbFixture.ensure()
        assertEquals(listOf("/auth/token", "/auth/token"), assertMockedDeletion { _, _ -> 503 to "" })
        assertEquals(listOf("/auth/token", "/auth/token"), assertMockedDeletion { _, _ -> throw HttpTimeoutException("synthetic timeout") })
    }

    @Test
    fun invalidOrMissingIdentityRepliesNeverReachRevocation() = runBlocking {
        AuthDbFixture.ensure()
        for (reply in listOf("invalid-json", "{}", """{"refresh_token":"synthetic-refresh"}""", """{"id_token":"malformed"}""")) {
            assertEquals(listOf("/auth/token"), assertMockedDeletion { _, _ -> 200 to reply })
        }
    }

    @Test
    fun realJwtVerificationRejectsExpiredWrongAudienceIssuerAndSubject() = runBlocking {
        AuthDbFixture.ensure()
        val invalid = listOf(identityToken(expires = Instant.now().minusSeconds(300)),
            identityToken(audience = "wrong-audience"), identityToken(issuer = "https://invalid.example"),
            identityToken(subject = "wrong-subject"))
        for (token in invalid) {
            assertEquals(listOf("/auth/token"), assertMockedDeletion(token) { _, _ -> 200 to "IDENTITY" })
        }
    }

    @Test
    fun successfulExchangeRevokesOnceAndBoundedRetriesKeepDeletionWorking() = runBlocking {
        AuthDbFixture.ensure()
        assertEquals(listOf("/auth/token", "/auth/revoke"), assertMockedDeletion { path, _ ->
            200 to if (path == "/auth/token") "IDENTITY" else ""
        })
        assertEquals(listOf("/auth/token", "/auth/revoke", "/auth/revoke"), assertMockedDeletion { path, count ->
            when {
                path == "/auth/token" -> 200 to "IDENTITY"
                count == 2 -> 503 to ""
                else -> 200 to ""
            }
        })
        assertEquals(listOf("/auth/token", "/auth/revoke", "/auth/revoke"), assertMockedDeletion { path, _ ->
            if (path == "/auth/token") 200 to "IDENTITY" else 503 to ""
        })
    }

    private fun identityToken(
        audience: String = settings.clientId,
        issuer: String = "https://appleid.apple.com",
        subject: String = "linked-subject",
        expires: Instant = Instant.now().plusSeconds(300),
    ): String = SignedJWT(JWSHeader(JWSAlgorithm.RS256), JWTClaimsSet.Builder()
        .issuer(issuer).audience(audience).subject(subject).claim("email", "synthetic@example.test")
        .expirationTime(Date.from(expires)).build()).apply { sign(RSASSASigner(identityKey)) }.serialize()

    private class ReplyHttpClient(private val respond: (String, Int) -> Pair<Int, String>) : HttpClient() {
        private val defaults = newHttpClient()
        val paths = mutableListOf<String>()
        var identityReply = ""
        override fun cookieHandler(): Optional<CookieHandler> = Optional.empty()
        override fun connectTimeout(): Optional<Duration> = Optional.of(Duration.ofSeconds(1))
        override fun followRedirects(): Redirect = Redirect.NEVER
        override fun proxy(): Optional<ProxySelector> = Optional.empty()
        override fun sslContext(): SSLContext = defaults.sslContext()
        override fun sslParameters(): SSLParameters = defaults.sslParameters()
        override fun authenticator(): Optional<Authenticator> = Optional.empty()
        override fun version(): Version = Version.HTTP_1_1
        override fun executor(): Optional<Executor> = Optional.empty()
        override fun <T : Any?> send(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): HttpResponse<T> {
            assertEquals("https", request.uri().scheme)
            assertEquals("appleid.apple.com", request.uri().host)
            val path = request.uri().path
            paths += path
            val (status, rawBody) = respond(path, paths.size)
            val body = if (rawBody == "IDENTITY") identityReply else rawBody
            return object : HttpResponse<T> {
                override fun statusCode(): Int = status
                override fun request(): HttpRequest = request
                override fun previousResponse(): Optional<HttpResponse<T>> = Optional.empty()
                override fun headers(): HttpHeaders = HttpHeaders.of(emptyMap()) { _, _ -> true }
                @Suppress("UNCHECKED_CAST")
                override fun body(): T = body as T
                override fun sslSession(): Optional<SSLSession> = Optional.empty()
                override fun uri(): URI = request.uri()
                override fun version(): Version = Version.HTTP_1_1
            }
        }
        override fun <T : Any?> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>): CompletableFuture<HttpResponse<T>> =
            error("Asynchronous transport is not part of this test")
        override fun <T : Any?> sendAsync(request: HttpRequest, handler: HttpResponse.BodyHandler<T>, pushPromiseHandler: HttpResponse.PushPromiseHandler<T>): CompletableFuture<HttpResponse<T>> =
            error("Asynchronous transport is not part of this test")
    }

    companion object {
        private val identityKey by lazy { RSAKeyGenerator(2048).generate() }
    }

    @Test
    fun mixedProviderAccountIsDetectedAndDeletionSurvivesMissingAppleConfiguration() = runBlocking {
        AuthDbFixture.ensure()
        val (appId, principalId) = AuthDbFixture.seedUser()
        val principal = assertNotNull(AuthDbFixture.principalById(principalId))
        AuthDbFixture.seedProviderAccount(assertNotNull(principal.identityId), UUID.randomUUID().toString(), UserProvider.APPLE)
        val runtime = AuthRuntimeGraph.create(AuthDbFixture.adapter())
        val api = AuthService() over LocalLink(runtime.service)
        val session = runtime.sessionLifecycle.createSession(principalId, appId, environment = Environment.STAGE)
        val tokens = assertNotNull(api.sessionRefresh(RefreshRequest(session.rawRefreshToken, environment = Environment.STAGE)).tokenSet)
        val request = DeactivateAccountRequest(
            audience = appId,
            headers = mutableMapOf("Authorization" to "Bearer ${tokens.accessToken}"),
            environment = Environment.STAGE,
            appleAuthorizationCode = UUID.randomUUID().toString(),
            appleClientId = "synthetic-client",
        )
        assertEquals(StatusCode.UNAUTHORIZED, api.accountDeletionOptions(request.copy(headers = mutableMapOf())).statusCode)
        assertTrue(api.accountDeletionOptions(request).appleLinked)
        assertTrue(api.accountDeactivate(request).deactivated)
        assertEquals(PrincipalStatus.SOFT_DELETED, AuthDbFixture.principalById(principalId)?.status)
        assertEquals(StatusCode.UNAUTHORIZED, api.sessionRefresh(RefreshRequest(session.rawRefreshToken, environment = Environment.STAGE)).statusCode)
    }
}
