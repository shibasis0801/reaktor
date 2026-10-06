package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.infra.ProviderHttpFailure
import dev.shibasis.reaktor.tooling.infra.readIslandShapes
import kotlinx.serialization.json.Json
import java.net.http.HttpTimeoutException
import java.util.concurrent.ExecutionException
import kotlin.test.*

class IslandJvmClientTest {
    @Test fun twelveIslandsIncludeTransitiveClientsAndUnreadableHostsWithoutRepeatingCycles() {
        val initial = (1..11).map { "island$it.fixture.test" }
        val fetched = mutableListOf<String>()
        val result = readIslandShapes(initial) { host ->
            fetched += host
            when (host) {
                initial[7] -> throw ProviderHttpFailure(404, "private-body")
                initial[8] -> throw ProviderHttpFailure(401, "private-body")
                initial[9] -> throw ExecutionException(HttpTimeoutException("private-diagnostic"))
                else -> shape(if (host == initial[0]) "https://transitive.fixture.test" else "https://${initial[0]}")
            }
        }
        assertEquals(12, result.size)
        assertEquals(fetched.size, fetched.distinct().size)
        assertEquals("transitive.fixture.test", result.last().host)
        assertEquals("Not a Reaktor island (HTTP 404)", result.single { it.host == initial[7] }.failure)
        assertEquals("HTTP 401", result.single { it.host == initial[8] }.failure)
        assertEquals("Timeout reading island shape", result.single { it.host == initial[9] }.failure)
        assertTrue(result.none { it.failure?.contains("private-") == true })
    }

    @Test fun traversalIsBoundedAndSkipsNonHttpsOrCredentialBearingUrls() {
        var count = 0
        val result = readIslandShapes(listOf("root.fixture.test")) {
            count++
            shape("https://next$count.fixture.test")
        }
        assertEquals(64, count)
        assertEquals(64, result.size)
        for (url in listOf("http://other.fixture.test", "https://user:password@other.fixture.test", "https://other.fixture.test:8443")) {
            assertEquals(1, readIslandShapes(listOf("root.fixture.test")) { shape(url) }.size)
        }
        val notIsland = readIslandShapes(listOf("root.fixture.test")) { Json.parseToJsonElement("{}") }.single()
        assertEquals("Not a Reaktor island", notIsland.failure)
    }

    private fun shape(url: String) = Json.parseToJsonElement("""{"island":"fixture","runtime":"worker","graph":{"scopes":[],"nodes":[{"attributes":{"role":"client","baseUrl":"$url"}}],"wires":[],"routes":[]}}""")
}
