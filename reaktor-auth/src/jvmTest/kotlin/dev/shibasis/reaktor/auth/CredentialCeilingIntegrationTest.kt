package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.api.MintPatRequest
import dev.shibasis.reaktor.auth.api.MintPatResponse
import dev.shibasis.reaktor.auth.api.TokenRequest
import dev.shibasis.reaktor.auth.jwt.toAuthContext
import dev.shibasis.reaktor.auth.kernel.AuthContext
import dev.shibasis.reaktor.auth.kernel.CredentialCeiling
import dev.shibasis.reaktor.auth.kernel.PrincipalKind
import dev.shibasis.reaktor.auth.kernel.ceiling
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeGraph
import dev.shibasis.reaktor.auth.runtime.ceiling
import dev.shibasis.reaktor.core.framework.EMPTY_JSON
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Environment
import dev.shibasis.reaktor.service.contract.LocalLink
import dev.shibasis.reaktor.service.contract.over
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialCeilingIntegrationTest {
    @BeforeTest
    fun setup() = AuthDbFixture.ensure()

    private val runtime = AuthRuntimeGraph.create(AuthDbFixture.adapter())
    private val server = AuthService() over LocalLink(runtime.service)

    private fun context(token: String, audience: String): AuthContext =
        assertNotNull(runtime.jwt.verifier.verifyReaktorToken(token, listOf(audience)).getOrNull()).toAuthContext()

    @Test
    fun aPatsScopesBecomeTheCeilingOfEveryTokenItIsExchangedFor() = runBlocking {
        val (appId, principalId) = AuthDbFixture.seedUser()
        val (pat, raw) = runtime.personalTokens.createPersonalAccessToken(principalId = principalId, name = "reader", scopes = listOf("mcp:read"), appId = appId)
        assertEquals(CredentialCeiling(scopes = setOf("mcp:read")), pat.ceiling)

        val exchanged = server.token(TokenRequest(grantType = "pat", rawToken = raw, audience = "manna-mcp", environment = Environment.STAGE))
        assertEquals(StatusCode.OK, exchanged.statusCode)
        assertEquals(CredentialCeiling(scopes = setOf("mcp:read")), context(exchanged.accessToken, "manna-mcp").ceiling)
    }

    @Test
    fun aRowWithoutTheMarkerKeepsItsOldReachAndABrokenMarkerReachesNothing() = runBlocking {
        val (pat, _) = runtime.personalTokens.createPersonalAccessToken(principalId = null, name = "legacy", scopes = listOf("mcp:read"))
        val legacy = pat.copy(data = JsonObject(emptyMap()))
        assertNull(legacy.ceiling)
        assertNull(context(runtime.personalTokens.createAccessToken(legacy, "manna-mcp", 300), "manna-mcp").ceiling)
        assertEquals(CredentialCeiling.Closed, pat.copy(data = JsonObject(mapOf("ceiling" to JsonPrimitive(2)))).ceiling)
        assertEquals(CredentialCeiling.Closed, pat.copy(data = JsonObject(mapOf("ceiling" to JsonPrimitive("one")))).ceiling)
    }

    @Test
    fun aHolderMintsOnlyForItselfWithinItsScopesAudiencesAppAndLifetime() = runBlocking {
        val (appId, principalId) = AuthDbFixture.seedUser()
        val (_, otherPrincipalId) = AuthDbFixture.seedUser()
        val workspace = seedContext(appId)
        val (_, holder) = runtime.personalTokens.createPersonalAccessToken(principalId = principalId, name = "holder",
            scopes = listOf("auth:pat:mint", "mcp:read"), contextId = workspace, appId = appId, allowedAudiences = listOf("manna-mcp"), expiresInDays = 7)

        suspend fun mint(principal: String? = principalId, scopes: List<String> = listOf("mcp:read"), audiences: List<String> = listOf("manna-mcp"),
                         days: Int? = 3, app: String? = null): MintPatResponse =
            server.mintPat(MintPatRequest(name = "child", scopes = scopes, principalId = principal, appId = app, allowedAudiences = audiences,
                expiresInDays = days, headers = bearer(holder), environment = Environment.STAGE))

        refused(mint(principal = otherPrincipalId), principalId, "pat_target_not_self")
        assertTrue(AuthDbFixture.auditEvents().any { it.actorPrincipalId == principalId && it.requestedPrincipal == otherPrincipalId })
        refused(mint(principal = null), principalId, "pat_target_not_self")
        refused(mint(scopes = listOf("mcp:write")), principalId, "pat_scope_exceeds_issuer")
        refused(mint(audiences = listOf("other-service")), principalId, "pat_audience_exceeds_issuer")
        refused(mint(app = "other-app"), principalId, "pat_app_not_issuers")
        refused(mint(days = null), principalId, "pat_outlives_issuer")
        refused(mint(days = 30), principalId, "pat_outlives_issuer")

        val child = mint()
        assertEquals(StatusCode.OK, child.statusCode)
        val minted = assertNotNull(runtime.personalTokens.verifyPersonalAccessToken(child.rawToken))
        assertEquals(principalId, minted.principalId)
        assertEquals(workspace, minted.contextId)
        assertEquals(appId, minted.appId)
        assertEquals(CredentialCeiling(scopes = setOf("mcp:read")), minted.ceiling)
        assertTrue(AuthDbFixture.auditEvents().any { it.tokenId == minted.id && it.issuer == "holder" })
    }

    @Test
    fun aPatBoundToAContextCannotBeExchangedIntoAnother() = runBlocking {
        val (appId, principalId) = AuthDbFixture.seedUser()
        val workspace = seedContext(appId)
        val (_, raw) = runtime.personalTokens.createPersonalAccessToken(principalId = principalId, name = "bound", scopes = listOf("mcp:read"),
            contextId = workspace, appId = appId)

        suspend fun exchange(contextId: String?) =
            server.token(TokenRequest(grantType = "pat", rawToken = raw, audience = "manna-mcp", contextId = contextId, environment = Environment.STAGE))

        assertEquals(StatusCode.FORBIDDEN, exchange(seedContext(appId)).statusCode)
        assertTrue(AuthDbFixture.auditEvents().any { it.subjectPrincipalId == principalId && it.reason == "context_not_allowed" && it.contextId == workspace })
        assertEquals(workspace, exchange(workspace).contextId)
        assertEquals(workspace, context(exchange(null).accessToken, "manna-mcp").contextId)
    }

    @Test
    fun delegationStaysInTheSubjectsContextAndUnderItsCeiling() = runBlocking {
        val (appId, principalId) = AuthDbFixture.seedUser()
        val clientId = "svc-${UUID.randomUUID()}"
        val secret = "secret-${UUID.randomUUID()}"
        AuthDbFixture.seedServiceAccount(clientId = clientId, appId = appId, authMethod = "SECRET", secretHash = sha256(secret),
            scopes = "delegate:read delegate:write", allowedAudiences = """["manna-mcp"]""")
        val workspace = seedContext(appId)
        val subjectToken = runtime.jwt.minter.mintPatAccessToken(patId = UUID.randomUUID().toString(), subject = principalId, name = "reader",
            audience = appId, scopes = listOf("delegate:read"), contextId = workspace, principalKind = PrincipalKind.USER, ttlSeconds = 300,
            ceiling = CredentialCeiling(scopes = setOf("delegate:read")))

        suspend fun exchange(contextId: String?) = server.token(TokenRequest(grantType = TokenExchange, clientId = clientId, clientSecret = secret,
            subjectToken = subjectToken, audience = "manna-mcp", scopes = listOf("delegate:read", "delegate:write"), contextId = contextId,
            environment = Environment.STAGE))

        assertEquals(StatusCode.FORBIDDEN, exchange("ws-2").statusCode)
        assertTrue(AuthDbFixture.auditEvents().any { it.subjectPrincipalId == principalId && it.reason == "subject_context_mismatch" && it.requested == "ws-2" })

        val delegated = exchange(null)
        assertEquals(StatusCode.OK, delegated.statusCode)
        assertEquals(listOf("delegate:read"), delegated.scopes)
        assertEquals(workspace, delegated.contextId)
        val context = context(delegated.accessToken, "manna-mcp")
        assertEquals(workspace, context.contextId)
        assertEquals(CredentialCeiling(scopes = setOf("delegate:read")), context.ceiling)
    }
}

private const val TokenExchange = "urn:ietf:params:oauth:grant-type:token-exchange"

private val AuthAuditEvent.issuer: String?
    get() = runCatching { data.jsonObject["issuer"]?.jsonPrimitive?.contentOrNull }.getOrNull()

private val AuthAuditEvent.requested: String?
    get() = runCatching { data.jsonObject["requestedContext"]?.jsonPrimitive?.contentOrNull }.getOrNull()

private val AuthAuditEvent.requestedPrincipal: String?
    get() = runCatching { data.jsonObject["requestedPrincipal"]?.jsonPrimitive?.contentOrNull }.getOrNull()

private fun refused(response: MintPatResponse, issuer: String, reason: String) {
    assertEquals(StatusCode.FORBIDDEN, response.statusCode, reason)
    assertTrue(AuthDbFixture.auditEvents().any { it.actorPrincipalId == issuer && it.reason == reason && it.issuer == "holder" }, reason)
}

private fun seedContext(appId: String): String = UUID.randomUUID().toString().also { context ->
    transaction { Contexts.insert { it[Contexts.id] = Contexts.entityId(UUID.fromString(context)); it.fields(Context(context, "workspace", appId, EMPTY_JSON)) } }
}

private fun bearer(rawToken: String): MutableMap<String, String> = mutableMapOf("Authorization" to "Bearer $rawToken")

private fun sha256(value: String): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
