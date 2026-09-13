package dev.shibasis.reaktor.conductor.appserver

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.io.PipedReader
import java.io.PipedWriter
import kotlin.test.*

/**
 * The transport, exercised over a pair of pipes.
 *
 * Every case here is a way a peer misbehaves: it answers out of order, sends a line that is not
 * JSON, asks a question of its own, or dies mid-request. None of them may hang a caller, because a
 * hung caller in an agent loop looks exactly like a model thinking.
 */
class JsonRpcStdioTest {
    @Test fun answersAreMatchedByIdEvenWhenTheyArriveOutOfOrder() = runBlocking(Dispatchers.IO) {
        withPeer { rpc, peer ->
            val first = async { rpc.request("thread/start") }
            val second = async { rpc.request("turn/start") }
            val ids = listOf(peer.readRequest(), peer.readRequest()).associateBy { it.method }
            // Deliberately reversed: the second question is answered first.
            peer.reply(ids.getValue("turn/start").id, buildJsonObject { put("turn", "T") })
            peer.reply(ids.getValue("thread/start").id, buildJsonObject { put("thread", "TH") })
            assertEquals("TH", first.await()["thread"]?.jsonPrimitive?.content)
            assertEquals("T", second.await()["turn"]?.jsonPrimitive?.content)
        }
    }

    @Test fun aLineThatIsNotJsonIsSkippedInsteadOfKillingTheSession() = runBlocking(Dispatchers.IO) {
        withPeer { rpc, peer ->
            val waiting = async { rpc.request("initialize") }
            val id = peer.readRequest().id
            peer.line("not json at all")
            peer.line("""{"method":"remoteControl/status/changed","params":{"status":"disabled"}}""")
            peer.reply(id, buildJsonObject { put("ok", true) })
            assertTrue(waiting.await()["ok"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun aRequestCarryingBothIdAndMethodIsTheServerAskingUsSomething() = runBlocking(Dispatchers.IO) {
        withPeer { rpc, peer ->
            val inbound = async { rpc.inbound.first() }
            delay(50)
            peer.line("""{"jsonrpc":"2.0","id":9,"method":"execCommandApproval","params":{"command":"rm -rf /"}}""")
            val message = assertIs<JsonRpcInbound.ServerRequest>(inbound.await())
            assertEquals("execCommandApproval", message.method)
            assertEquals("rm -rf /", message.params["command"]?.jsonPrimitive?.content)
            rpc.respond(message.id, buildJsonObject { put("decision", "denied") })
            val answer = peer.readLine()
            assertTrue(answer.contains("\"denied\""))
            assertTrue(answer.contains("\"id\":9"), "The server's own id has to come back unchanged")
        }
    }

    @Test fun aPeerThatDiesMidRequestFailsTheCallerRatherThanParkingIt() = runBlocking(Dispatchers.IO) {
        withPeer { rpc, peer ->
            // The failing call stays in the test body: an `async` that throws cancels the whole
            // scope before an assertion can look at it.
            launch { peer.readRequest(); peer.close() }
            val failure = assertFailsWith<JsonRpcException> { rpc.request("thread/start") }
            assertTrue(failure.message!!.contains("closed"))
        }
    }

    @Test fun anErrorReplyBecomesAnExceptionCarryingTheServersCode() = runBlocking(Dispatchers.IO) {
        withPeer { rpc, peer ->
            launch {
                val id = peer.readRequest().id
                peer.line("""{"id":$id,"error":{"code":-32602,"message":"expectedTurnId did not match"}}""")
            }
            val failure = assertFailsWith<JsonRpcException> { rpc.request("turn/steer") }
            assertEquals(-32602, failure.code)
            assertTrue(failure.message!!.contains("expectedTurnId"))
        }
    }

    private class Peer(private val toClient: PipedWriter, private val fromClient: java.io.BufferedReader) {
        fun line(text: String) { toClient.write(text + "\n"); toClient.flush() }
        suspend fun readLine(): String = withContext(Dispatchers.IO) { fromClient.readLine() } ?: error("client wrote nothing")
        suspend fun readRequest(): Request {
            val message = Json.parseToJsonElement(readLine()).jsonObject
            return Request(message.getValue("id").jsonPrimitive.long, message.getValue("method").jsonPrimitive.content)
        }
        fun reply(id: Long, result: JsonObject) = line(buildJsonObject { put("id", id); put("result", result) }.toString())
        fun close() = toClient.close()
        data class Request(val id: Long, val method: String)
    }

    private suspend fun withPeer(body: suspend (JsonRpcStdio, Peer) -> Unit) = coroutineScope {
        val serverToClient = PipedWriter()
        val clientInput = java.io.BufferedReader(PipedReader(serverToClient, 1 shl 16))
        val clientToServer = PipedWriter()
        val serverInput = java.io.BufferedReader(PipedReader(clientToServer, 1 shl 16))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rpc = JsonRpcStdio(clientInput, clientToServer, scope)
        try {
            withTimeout(10000) { body(rpc, Peer(serverToClient, serverInput)) }
        } finally {
            rpc.close(); scope.cancel(); runCatching { serverToClient.close() }
        }
    }
}
