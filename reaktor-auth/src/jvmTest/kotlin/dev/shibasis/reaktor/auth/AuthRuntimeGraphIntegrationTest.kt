package dev.shibasis.reaktor.auth

import dev.shibasis.reaktor.auth.api.TokenRequest
import dev.shibasis.reaktor.auth.api.VerifyPatRequest
import dev.shibasis.reaktor.auth.api.AppServer
import dev.shibasis.reaktor.auth.api.AuthServer
import dev.shibasis.reaktor.auth.runtime.AuthAppService
import dev.shibasis.reaktor.auth.runtime.AuthHttpService
import dev.shibasis.reaktor.auth.runtime.AuthPat
import dev.shibasis.reaktor.auth.runtime.AuthRuntimeGraph
import dev.shibasis.reaktor.auth.runtime.AuthTokenGrants
import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.graph.core.Graph
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
        assertTrue(consumer.httpServicePort.isConnected())
        assertTrue(consumer.appServicePort.isConnected())
        assertTrue(runtime.authHandlers.providerPorts.flattenedValues().any { it.key.key == "/anonymous" })
        assertTrue(runtime.authHandlers.providerPorts.flattenedValues().any { it.key.key == "/token" })

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

        val response = runtime.service.token(
            TokenRequest(rawToken = rawToken, audience = "manna-mcp", ttlSeconds = 300)
        )

        assertEquals(StatusCode.OK, response.statusCode)
        assertEquals(pat.id, response.tokenId)
        assertEquals(listOf("mcp:read", "mcp:write"), response.scopes)
        assertNotNull(runtime.jwt.verifier.verifyReaktorToken(response.accessToken, listOf("manna-mcp")).getOrNull())
        Unit
    }

    @Test
    fun springServerAdaptersExposeGraphHandlersToServiceRouters() {
        val fx = AuthRuntimeGraphFixture()

        assertEquals(9, AuthServer(fx.runtime.service).handlers.size)
        assertEquals(2, AppServer(fx.runtime.appService).handlers.size)
    }
}

private class AuthRuntimeConsumerFixture(graph: Graph) : BasicNode(graph) {
    val patPort by consumes<AuthPat>()
    val tokenGrantsPort by consumes<AuthTokenGrants>()
    val httpServicePort by consumes<AuthHttpService>()
    val appServicePort by consumes<AuthAppService>()
}

private class AuthRuntimeGraphFixture {
    val runtime = AuthRuntimeGraph.create(AuthDbFixture.adapter())
}
