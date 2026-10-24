package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.TokenRequest
import dev.shibasis.reaktor.auth.api.VerifyPatRequest
import dev.shibasis.reaktor.auth.api.AppService
import dev.shibasis.reaktor.auth.api.AuthService
import dev.shibasis.reaktor.auth.runtime.AuthPat
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeGraph
import dev.shibasis.reaktor.auth.runtime.AuthTokenGrants
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.connected
import dev.shibasis.reaktor.graph.core.Graph
import dev.shibasis.reaktor.graph.uses
import dev.shibasis.reaktor.graph.core.autoWire
import dev.shibasis.reaktor.graph.core.node.BasicNode
import dev.shibasis.reaktor.graph.core.requireFullyWired
import dev.shibasis.reaktor.portgraph.port.consumes
import dev.shibasis.reaktor.portgraph.port.flattenedValues
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AuthRuntimeGraphIntegrationTest {
    @BeforeTest
    fun setup() = AuthDbFixture.ensure()

    @Test
    fun runtimeGraphExposesAuthCapabilitiesAsPorts() = runBlocking {
        val fx = AuthRuntimeGraphFixture()
        val runtime = fx.runtime
        val consumer = AuthRuntimeConsumerFixture(runtime)
        runtime.attach(consumer)
        runtime.autoWire()
        runtime.requireFullyWired()

        val (_, rawToken) = runtime.personalTokens.createPersonalAccessToken(
            principalId = null,
            name = "graph-consumer",
            scopes = listOf("mcp:read"),
        )

        assertTrue(consumer.patPort.isConnected())
        assertTrue(consumer.tokenGrantsPort.isConnected())
        assertTrue(consumer.httpServicePort.connected)
        assertTrue(consumer.appServicePort.connected)
        assertTrue(runtime.authHandlers.providerPorts.flattenedValues().any { it.key.key == AuthService().anonymous.name })
        assertTrue(runtime.authHandlers.providerPorts.flattenedValues().any { it.key.key == AuthService().token.name })

        val pat = consumer.patPort.suspended { verify(VerifyPatRequest(rawToken)) }
        assertEquals(StatusCode.OK, pat.statusCode)
        assertEquals("graph-consumer", pat.name)
    }

    @Test
    fun httpServiceDelegatesPatGrantThroughGraphPorts() = runBlocking {
        val fx = AuthRuntimeGraphFixture()
        val runtime = fx.runtime
        val (pat, rawToken) = runtime.personalTokens.createPersonalAccessToken(
            principalId = null,
            name = "graph-token",
            scopes = listOf("mcp:read", "mcp:write"),
        )

        val response = runtime.api.token(
            TokenRequest(rawToken = rawToken, audience = "manna-mcp", ttlSeconds = 300)
        )

        assertEquals(StatusCode.OK, response.statusCode)
        assertEquals(pat.id, response.tokenId)
        assertEquals(listOf("mcp:read", "mcp:write"), response.scopes)
        assertNotNull(runtime.jwt.verifier.verifyReaktorToken(response.accessToken, listOf("manna-mcp")).getOrNull())
        Unit
    }

    @Test
    fun theRuntimeServesEveryAuthAndAppCall() {
        val fx = AuthRuntimeGraphFixture()

        assertEquals(12, fx.runtime.service.calls.size)
        assertEquals(2, fx.runtime.appService.calls.size)
    }
}

private class AuthRuntimeConsumerFixture(graph: Graph) : BasicNode(graph) {
    val patPort by consumes<AuthPat>()
    val tokenGrantsPort by consumes<AuthTokenGrants>()
    val httpServicePort by uses(AuthService())
    val appServicePort by uses(AppService())
}

private class AuthRuntimeGraphFixture {
    val runtime = AuthRuntimeGraph.create(AuthDbFixture.adapter())
}
