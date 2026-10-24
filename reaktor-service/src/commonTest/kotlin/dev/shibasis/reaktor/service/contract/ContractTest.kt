package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.service.HttpMethod
import dev.shibasis.reaktor.service.ServiceExecutionPhase
import dev.shibasis.reaktor.service.SpanBuffer
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Serializable
data class Greeting(val name: String, val excited: Boolean = false, val tags: List<String> = emptyList())

@Serializable
data class Greeted(val text: String)

@Serializable
data class Sign(val guest: String, val note: String)

@Serializable
data class Signed(val entry: Int)

@Serializable
data class Count(val upTo: Int)

@Serializable
data class Health(val ok: Boolean)

@Serializable
sealed interface GuestbookProblem {
    @Serializable
    @SerialName("full")
    data object Full : GuestbookProblem

    @Serializable
    @SerialName("banned")
    data class Banned(val guest: String) : GuestbookProblem
}

open class Greeter : Service("test.greeter") {
    open val greet by get<Greeting, Greeted>("/greet/{name}", effect = Effect.ReadOnly)
    open val sign by post<Sign, Signed>("/guests/{guest}", idempotency = Idempotency.Keyed, status = 201)
    open val health by call<Unit, Health>()
    open val count by stream<Count, Int>()
}

class GreeterServer : Greeter() {
    val keys = mutableListOf<String?>()
    var remaining: kotlin.time.Duration? = null
    private var entries = 0

    override val greet = super.greet.handle { request ->
        remaining = exchange().remaining
        Greeted("Hello, ${request.name}" + (if (request.excited) "!" else "") + request.tags.joinToString("") { " #$it" })
    }

    override val sign = super.sign.handle { request ->
        if (request.guest == "mallory") reject<GuestbookProblem>(GuestbookProblem.Banned(request.guest), 403, "Banned")
        keys += exchange().metadata[Metadata.IdempotencyKey]
        entries += 1
        Signed(entries)
    }

    override val health = super.health.handle { Health(true) }

    override val count = super.count.handle { request -> flow { for (value in 1..request.upTo) emit(value) } }
}

class Doorman : Greeter() {
    override val greet = super.greet.handle { request -> Greeted("Welcome, ${request.name}") }
}

class Herald : Greeter() {
    override val greet = super.greet.handle { request -> Greeted(super.greet(request).text.uppercase()) }
}

private class Recording(
    private val label: String,
    override val position: Int,
    override val sides: Set<Side>,
    private val seen: MutableList<String>,
) : Interceptor {
    override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R {
        seen += "$label:${exchange.side}:${exchange.name}"
        return next(exchange)
    }
}

private fun loopback(served: Served<*>, answer: (HttpMethod, Int) -> Int? = { _, _ -> null }): HttpClient {
    var hits = 0
    return HttpClient(MockEngine { request ->
        hits += 1
        val method = HttpMethod.valueOf(request.method.value)
        answer(method, hits)?.let { return@MockEngine respond("""{"error":"injected"}""", HttpStatusCode.fromValue(it)) }
        val outbound = served.dispatch(
            method,
            request.url.encodedPath,
            Inbound(
                query = request.url.parameters.entries().associate { it.key to it.value },
                headers = request.headers.entries().associate { it.key to it.value.joinToString(",") },
                body = (request.body as? TextContent)?.text,
            ),
        )
        respond(outbound.body, HttpStatusCode.fromValue(outbound.status), headersOf(*outbound.headers.map { it.key to listOf(it.value) }.toTypedArray()))
    })
}

private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }

private fun quick() = Retry(backoff = listOf(1.milliseconds, 1.milliseconds))

class ContractTest {
    @Test
    fun clientAndServerInterceptorsRunInPositionOrder() = runTest {
        val seen = mutableListOf<String>()
        val served = Served(GreeterServer()).intercept(Recording("security", Position.Security, setOf(Side.Server), seen))
        val link = LocalLink(served).intercept(
            Recording("app", Position.Application, setOf(Side.Client), seen),
            Recording("telemetry", Position.Telemetry, setOf(Side.Client), seen),
        )

        val greeted = (Greeter() over link).greet(Greeting("Ada"))

        assertEquals("Hello, Ada", greeted.text)
        assertEquals(listOf("telemetry:Client:greet", "app:Client:greet", "security:Server:greet"), seen)
    }

    @Test
    fun routesCarryPathAndQueryFromRequestFields() {
        val request = Greeting("Ada Lovelace", excited = true, tags = listOf("math", "engines"))

        val outgoing = Greeter().greet.binding.encode(Greeting.serializer(), request)

        assertEquals("/greet/Ada%20Lovelace", outgoing.path)
        assertEquals(listOf("excited" to "true", "tags" to "math", "tags" to "engines"), outgoing.query)
        assertEquals(null, outgoing.body)
        val decoded = Greeter().greet.binding.decode(
            Greeting.serializer(),
            Inbound(pathParams = mapOf("name" to "Ada Lovelace"), query = mapOf("excited" to listOf("true"), "tags" to listOf("math", "engines"))),
        )
        assertEquals(request, decoded)
        assertEquals(mapOf("guest" to "Bob Smith"), Greeter().sign.binding.match(HttpMethod.POST, "/guests/Bob%20Smith"))
    }

    @Test
    fun httpRoundTripKeepsRoutesStatusesAndProblems() = realTime {
        val server = GreeterServer()
        val served = Served(server)
        val api = Greeter() over HttpLink("https://greeter.test", loopback(served))

        assertEquals("Hello, Ada! #x", api.greet(Greeting("Ada", excited = true, tags = listOf("x"))).text)
        assertEquals(Signed(1), api.sign(Sign("bob", "hi")))
        assertNotNull(server.keys.single())
        val refused = assertFailsWith<Rejected> { api.sign(Sign("mallory", "hi")) }
        assertEquals(403, refused.status)
        assertEquals(GuestbookProblem.Banned("mallory"), refused.problemAs<GuestbookProblem>())
        assertEquals(Health(true), api.health())
    }

    @Test
    fun retriesOnlyWhatIsSafeToRepeat() = realTime {
        val served = Served(GreeterServer())
        var attempts = 0
        val flaky = HttpLink("https://greeter.test", loopback(served) { method, hit ->
            attempts = hit
            if (hit == 1) (if (method == HttpMethod.GET) 503 else 504) else null
        }, retry = quick())
        assertEquals("Hello, Ada", (Greeter() over flaky).greet(Greeting("Ada")).text)
        assertEquals(2, attempts)

        val unsafe = HttpLink("https://greeter.test", loopback(served) { _, hit -> attempts = hit; 504 }, retry = quick())
        assertFailsWith<OutcomeUnknown> { (Greeter() over unsafe).health() }
        assertEquals(1, attempts)

        val keyed = HttpLink("https://greeter.test", loopback(served) { _, hit -> attempts = hit; if (hit == 1) 504 else null }, retry = quick())
        assertEquals(Signed(1), (Greeter() over keyed).sign(Sign("bob", "again")))
        assertEquals(2, attempts)
    }

    @Test
    fun streamsAndDeadlinesCrossTheLocalLink() = runTest {
        val server = GreeterServer()
        val api = Greeter() over LocalLink(Served(server), deadline = 2.seconds)

        assertEquals(listOf(1, 2, 3), api.count(Count(3)).toList())
        api.greet(Greeting("Ada"))
        val remaining = assertNotNull(server.remaining)
        assertTrue(remaining > 0.seconds && remaining <= 2.seconds)
    }

    @Test
    fun spansJoinClientAndServerIntoOneTrace() = runTest {
        val spans = SpanBuffer()
        val served = Served(GreeterServer()).intercept(Spans("server", spans))
        val api = Greeter() over LocalLink(served).intercept(Spans("app", spans))

        api.greet(Greeting("Ada"))
        assertFailsWith<Rejected> { api.sign(Sign("mallory", "no")) }

        val (clientGreet, serverGreet) = spans.snapshot().filter { it.operation == "greet" }.sortedBy { it.phase }
        assertEquals(ServiceExecutionPhase.CLIENT, clientGreet.phase)
        assertEquals(ServiceExecutionPhase.SERVER, serverGreet.phase)
        assertEquals(clientGreet.traceId, serverGreet.traceId)
        assertEquals(clientGreet.spanId, serverGreet.parentId)
        assertEquals("test.greeter", serverGreet.contract)
        assertEquals(listOf(403, 403), spans.snapshot().filter { it.operation == "sign" }.map { it.status })
    }

    @Test
    fun compatibilityIsComputedFromSchemas() {
        assertEquals(Verdict.Exact, Compatibility.between(Greeter().shape, Greeter().shape).verdict)
        val issues = mutableListOf<String>()
        Compatibility.readable(SchemaShape.of(ProfileV1.serializer().descriptor), SchemaShape.of(ProfileV2.serializer().descriptor), "profile", issues)
        Compatibility.readable(SchemaShape.of(ProfileV2.serializer().descriptor), SchemaShape.of(ProfileV1.serializer().descriptor), "profile", issues)
        assertEquals(emptyList(), issues)
        Compatibility.readable(SchemaShape.of(ProfileV3.serializer().descriptor), SchemaShape.of(ProfileV1.serializer().descriptor), "profile", issues)
        Compatibility.readable(SchemaShape.of(Mood.serializer().descriptor), SchemaShape.of(MoodV2.serializer().descriptor), "mood", issues)
        assertEquals(listOf("profile.age: required by the reader, never written", "mood: test.Mood cannot read [Sad]"), issues)
        assertNotEquals(Greeter().fingerprint, Renamed().fingerprint)
    }

    @Test
    fun anImplementationAnswersOnlyWhatItOverrides() = runTest {
        val doorman = Doorman()
        assertEquals(setOf("greet"), doorman.answers)
        assertEquals("Welcome, Ada", doorman.greet(Greeting("Ada")).text)
        assertEquals(501, assertFailsWith<Unavailable> { doorman.health() }.status)

        var attempts = 0
        val counted = LocalLink(doorman).intercept(object : Interceptor {
            override suspend fun <In, R> intercept(exchange: Exchange<In, R>, next: Next<In, R>): R { attempts += 1; return next(exchange) }
        }, quick())
        val api = Greeter() over counted
        assertEquals("Welcome, Ada", api.greet(Greeting("Ada")).text)
        assertEquals(501, assertFailsWith<Unavailable> { api.health() }.status)
        assertEquals(2, attempts)
    }

    @Test
    fun aSubclassOverridesSomeEndpointsAndLinksTheRest() = runTest {
        val herald = Herald() over LocalLink(GreeterServer())
        assertEquals("HELLO, ADA", herald.greet(Greeting("Ada")).text)
        assertEquals(Health(true), herald.health())
        assertEquals(listOf("greet", "sign", "health", "count"), herald.operations.map { it.name })
        assertEquals(setOf("greet"), herald.answers)
    }
}

@Serializable
@SerialName("test.Profile")
data class ProfileV1(val name: String)

@Serializable
@SerialName("test.Profile")
data class ProfileV2(val name: String, val bio: String = "")

@Serializable
@SerialName("test.Profile")
data class ProfileV3(val name: String, val age: Int)

@Serializable
@SerialName("test.Mood")
enum class Mood { Happy }

@Serializable
@SerialName("test.Mood")
enum class MoodV2 { Happy, Sad }

open class Renamed : Service("test.greeter") {
    open val greet by get<Greeting, Greeted>("/hello/{name}")
    open val sign by post<Sign, Signed>("/guests/{guest}")
    open val health by call<Unit, Health>()
    open val count by stream<Count, Int>()
}
