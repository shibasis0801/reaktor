package dev.shibasis.reaktor.service.contract

import dev.shibasis.reaktor.core.network.StatusCode
import dev.shibasis.reaktor.service.Forbidden
import dev.shibasis.reaktor.service.HttpMethod
import dev.shibasis.reaktor.service.Request
import dev.shibasis.reaktor.service.Response
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.milliseconds

@Serializable
sealed class ChatRequest : Request() {
    @Serializable
    @SerialName("test.ChatRequest.Messages")
    class Messages(
        val chatId: String = "",
        val limit: Int = 20,
        override val headers: MutableMap<String, String> = mutableMapOf(),
        override val queryParams: MutableMap<String, String> = mutableMapOf("limit" to limit.toString()),
        override val pathParams: MutableMap<String, String> = mutableMapOf("chatId" to chatId),
    ) : ChatRequest()

    @Serializable
    @SerialName("test.ChatRequest.Send")
    class Send(
        val chatId: String = "",
        val text: String,
        override val headers: MutableMap<String, String> = mutableMapOf(),
        override val queryParams: MutableMap<String, String> = mutableMapOf(),
        override val pathParams: MutableMap<String, String> = mutableMapOf("chatId" to chatId),
    ) : ChatRequest()
}

@Serializable
sealed class ChatResponse(
    override val statusCode: StatusCode = StatusCode.OK,
    override val headers: MutableMap<String, String> = mutableMapOf(),
) : Response() {
    @Serializable
    @SerialName("test.ChatResponse.Page")
    data class Page(val chatId: String, val limit: Int, val caller: String) : ChatResponse(StatusCode.OK)

    @Serializable
    @SerialName("test.ChatResponse.Sent")
    data class Sent(val text: String) : ChatResponse(StatusCode.CREATED)

    @Serializable
    @SerialName("test.ChatResponse.Failure")
    sealed class Failure(private val hack: StatusCode) : ChatResponse(hack) {
        @Serializable
        @SerialName("test.ChatResponse.Failure.Unauthorized")
        data object Unauthorized : Failure(StatusCode.UNAUTHORIZED)

        @Serializable
        @SerialName("test.ChatResponse.Failure.Busy")
        data class Busy(val message: String) : Failure(StatusCode.SERVICE_UNAVAILABLE)
    }
}

open class Chat : Service("test.chat") {
    open val messages by get<ChatRequest.Messages, ChatResponse>("/messages/{chatId}")
    open val send by post<ChatRequest.Send, ChatResponse>("/messages/{chatId}")
}

private class Desk {
    var busy = 0

    fun messages(request: ChatRequest.Messages): ChatResponse {
        val caller = request.headers.entries.firstOrNull { it.key.equals("Authorization", ignoreCase = true) }?.value
            ?: return ChatResponse.Failure.Unauthorized
        if (busy > 0) return ChatResponse.Failure.Busy("try again").also { busy -= 1 }
        return ChatResponse.Page(request.pathParams.getValue("chatId"), request.queryParams.getValue("limit").toInt(), caller)
    }

    fun send(request: ChatRequest.Send): ChatResponse {
        if (request.text == "forbidden") throw Forbidden("Not in this chat")
        return ChatResponse.Sent("${request.pathParams["chatId"]}:${request.text}")
    }
}

private class NewChatServer(private val desk: Desk) : Chat() {
    override val messages = super.messages.handle { request -> desk.messages(request) }
    override val send = super.send.handle { request -> desk.send(request) }
}

private class Seen(val method: String, val path: String, val query: Map<String, List<String>>, val body: String?, val headers: Map<String, String>)

private fun HttpRequestData.seen() = Seen(
    method.value,
    url.encodedPath,
    url.parameters.entries().associate { it.key to it.value },
    (body as? TextContent)?.text,
    headers.entries().associate { it.key to it.value.joinToString(",") },
)

private fun MockRequestHandleScope.answer(status: Int, body: String, headers: Map<String, String> = emptyMap()): HttpResponseData =
    respond(body, HttpStatusCode.fromValue(status), headersOf(*(headers + ("Content-Type" to "application/json")).map { it.key to listOf(it.value) }.toTypedArray()))

private fun newServer(served: Served<*>, seen: MutableList<Seen> = mutableListOf()) = HttpClient(MockEngine { request ->
    seen += request.seen()
    val outbound = served.dispatch(
        HttpMethod.valueOf(request.method.value),
        request.url.encodedPath,
        Inbound(
            query = request.url.parameters.entries().associate { it.key to it.value },
            headers = request.headers.entries().associate { it.key to it.value.joinToString(",") },
            body = (request.body as? TextContent)?.text,
        ),
    )
    answer(outbound.status, outbound.body, outbound.headers)
})

private fun realTime(block: suspend CoroutineScope.() -> Unit) = runTest { withContext(Dispatchers.Default) { block() } }

private fun signedIn() = mutableMapOf("Authorization" to "Bearer ada")

private class InstalledExchange(val method: String, val path: String, val query: Map<String, List<String>>, val body: String?, val headers: Map<String, String>, val status: Int, val answer: String)

private val installed = listOf(
    InstalledExchange("GET", "/messages/c 1", mapOf("limit" to listOf("7")), null, signedIn() + ("X-Environment" to "PROD"),
        200, """{"type":"test.ChatResponse.Page","statusCode":"OK","headers":{},"chatId":"c 1","limit":7,"caller":"Bearer ada"}"""),
    InstalledExchange("GET", "/messages/c1", mapOf("limit" to listOf("20")), null, mapOf("X-Environment" to "PROD"),
        401, """{"type":"test.ChatResponse.Failure.Unauthorized"}"""),
    InstalledExchange("POST", "/messages/c1", emptyMap(), """{"chatId":"c1","text":"hello","headers":{"Authorization":"Bearer ada"},"queryParams":{},"pathParams":{"chatId":"c1"}}""",
        signedIn() + ("X-Environment" to "PROD"), 201, """{"type":"test.ChatResponse.Sent","statusCode":"CREATED","headers":{},"text":"c1:hello"}"""),
    InstalledExchange("POST", "/messages/c1", emptyMap(), """{"chatId":"c1","text":"forbidden","headers":{},"queryParams":{},"pathParams":{"chatId":"c1"}}""",
        mapOf("X-Environment" to "PROD"), 403, """{"error":"Not in this chat"}"""),
)

private val installedCalls: List<suspend (Chat) -> Unit> = listOf(
    { it.messages(ChatRequest.Messages("c 1", limit = 7, headers = signedIn())) },
    { it.messages(ChatRequest.Messages("c1")) },
    { it.send(ChatRequest.Send("c1", "hello", headers = signedIn())) },
    { it.send(ChatRequest.Send("c1", "forbidden")) },
)

private fun replaying(answers: List<InstalledExchange>) = HttpClient(MockEngine { request ->
    val seen = request.seen()
    val match = answers.first { it.method == seen.method && it.path == seen.path && it.body == seen.body && it.headers["Authorization"] == seen.headers["Authorization"] }
    answer(match.status, match.answer)
})

class WireTest {
    @Test
    fun theNewServerAnswersAnInstalledClientExactlyAsTheOldServerDid() = realTime {
        val served = Served(NewChatServer(Desk()))
        installed.forEach { request ->
            val outbound = served.dispatch(HttpMethod.valueOf(request.method), request.path, Inbound(query = request.query, headers = request.headers, body = request.body))
            assertEquals(request.status, outbound.status, request.path)
            assertEquals(request.answer, outbound.body, request.path)
        }
    }

    @Test
    fun theNewServerStillAnswersABusyDeskWithTheOldFailureShape() = realTime {
        val outbound = Served(NewChatServer(Desk().apply { busy = 1 })).dispatch(HttpMethod.GET, "/messages/c1",
            Inbound(query = mapOf("limit" to listOf("20")), headers = signedIn() + ("X-Environment" to "PROD")))
        assertEquals(503, outbound.status)
        assertEquals("""{"type":"test.ChatResponse.Failure.Busy","statusCode":"SERVICE_UNAVAILABLE","headers":{},"hack":"SERVICE_UNAVAILABLE","message":"try again"}""", outbound.body)
    }

    @Test
    fun theMigratedClientPutsTheInstalledClientsRequestsOnTheWire() = realTime {
        val seen = mutableListOf<Seen>()
        val api = Chat() over HttpLink("https://chat.test", newServer(Served(NewChatServer(Desk())), seen), retry = null)
        installedCalls.forEach { runCatching { it(api) } }

        assertEquals(installed.size, seen.size)
        installed.zip(seen).forEach { (before, after) ->
            assertEquals(before.method, after.method)
            assertEquals(before.path, after.path)
            assertEquals(before.query, after.query)
            assertEquals(before.body, after.body)
            listOf("Authorization", "X-Environment").forEach { name -> assertEquals(before.headers[name], after.headers[name], name) }
        }
    }

    @Test
    fun theMigratedClientReadsTheOldServersAnswers() = realTime {
        val api = Chat() over HttpLink("https://chat.test", replaying(installed), retry = null)

        assertEquals(ChatResponse.Page("c 1", 7, "Bearer ada"), api.messages(ChatRequest.Messages("c 1", limit = 7, headers = signedIn())))

        val refused = api.messages(ChatRequest.Messages("c1"))
        assertEquals(ChatResponse.Failure.Unauthorized, refused)
        assertEquals(401, refused.transportStatusCode.code)
        assertFalse(refused.isSuccess)

        val sent = api.send(ChatRequest.Send("c1", "hello", headers = signedIn()))
        assertEquals(ChatResponse.Sent("c1:hello"), sent)
        assertEquals(201, sent.transportStatusCode.code)

        val forbidden = assertFailsWith<Rejected> { api.send(ChatRequest.Send("c1", "forbidden")) }
        assertEquals(403, forbidden.status)
        assertEquals("Not in this chat", forbidden.message)
    }

    @Test
    fun aTypedFailureStillReachesTheCallerAfterRetries() = realTime {
        val desk = Desk().apply { busy = 1 }
        val retried = Chat() over HttpLink("https://chat.test", newServer(Served(NewChatServer(desk))), retry = Retry(backoff = listOf(1.milliseconds)))
        assertIs<ChatResponse.Page>(retried.messages(ChatRequest.Messages("c1", headers = signedIn())))

        desk.busy = 5
        val exhausted = retried.messages(ChatRequest.Messages("c1", headers = signedIn()))
        assertEquals(ChatResponse.Failure.Busy("try again"), exhausted)
        assertEquals(503, exhausted.transportStatusCode.code)
        assertEquals(2, desk.busy)
    }
}
