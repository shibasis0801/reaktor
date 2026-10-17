package dev.shibasis.reaktor.io.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import io.ktor.client.request.get
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OriginRoutesTest {
    @AfterTest
    fun forgetRoutes() = OriginRoutes.clear()

    @Test
    fun withoutRoutesEveryAddressIsLeftAlone() {
        assertEquals("https://edge.bestbuds.ai/social", OriginRoutes.resolve("https://edge.bestbuds.ai/social"))
    }

    @Test
    fun aRoutedOriginKeepsItsPathQueryAndFragment() {
        OriginRoutes.route("https://edge.bestbuds.ai", "https://app.bestbuds.ai/api")

        assertEquals(
            "https://app.bestbuds.ai/api/social/profile?id=7#top",
            OriginRoutes.resolve("https://edge.bestbuds.ai/social/profile?id=7#top"),
        )
        assertEquals("https://app.bestbuds.ai/api", OriginRoutes.resolve("https://edge.bestbuds.ai"))
    }

    @Test
    fun aSocketFollowsItsOriginAndStaysASocket() {
        OriginRoutes.route("https://chat.bestbuds.ai", "https://app.bestbuds.ai/api/chat")

        assertEquals(
            "wss://app.bestbuds.ai/api/chat/parties/chat-service/42?_pk=a",
            OriginRoutes.resolve("wss://chat.bestbuds.ai/parties/chat-service/42?_pk=a"),
        )
    }

    @Test
    fun anInsecureTargetTakesSocketsWithIt() {
        OriginRoutes.route("https://chat.bestbuds.ai", "http://localhost:8787/api/chat")

        assertEquals(
            "ws://localhost:8787/api/chat/parties/room",
            OriginRoutes.resolve("wss://chat.bestbuds.ai/parties/room"),
        )
    }

    @Test
    fun onlyTheExactOriginIsRouted() {
        OriginRoutes.route("https://edge.bestbuds.ai", "https://app.bestbuds.ai/api")

        assertEquals("http://edge.bestbuds.ai/social", OriginRoutes.resolve("http://edge.bestbuds.ai/social"))
        assertEquals("https://edge.bestbuds.ai:8443/x", OriginRoutes.resolve("https://edge.bestbuds.ai:8443/x"))
        assertEquals("https://media.bestbuds.ai/x", OriginRoutes.resolve("https://media.bestbuds.ai/x"))
        assertEquals("https://app.bestbuds.ai/api/x", OriginRoutes.resolve("HTTPS://Edge.BestBuds.ai/x"))
    }

    @Test
    fun routingAnOriginAgainReplacesItsTarget() {
        OriginRoutes.route("https://edge.bestbuds.ai", "https://one.example/api")
        OriginRoutes.route("https://edge.bestbuds.ai/", "https://two.example/api/")

        assertEquals(1, OriginRoutes.routes.size)
        assertEquals("https://two.example/api/x", OriginRoutes.resolve("https://edge.bestbuds.ai/x"))
    }

    @Test
    fun aRouteMustStartFromABareOrigin() {
        assertFailsWith<IllegalArgumentException> {
            OriginRoutes.route("https://edge.bestbuds.ai/social", "https://app.bestbuds.ai/api")
        }
    }

    @Test
    fun aClientThatRoutesOriginsSendsRequestsThroughTheProxy() = runTest {
        OriginRoutes.route("https://edge.bestbuds.ai", "https://app.bestbuds.ai/api")
        val seen = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            seen += request.url.toString()
            respondOk()
        }) {
            routeOrigins()
        }

        client.get("https://edge.bestbuds.ai/social/profile?id=7")
        client.get("https://example.com/unrouted")

        assertEquals(
            listOf("https://app.bestbuds.ai/api/social/profile?id=7", "https://example.com/unrouted"),
            seen,
        )
    }
}
