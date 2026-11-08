package dev.shibasis.reaktor.tooling.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class McpStdioRelay(private val endpoint: URI, private val headers: Map<String, String> = emptyMap(), private val admit: () -> Unit = {}) {
    private class Reply(val status: Int, val body: String, val session: String? = null)

    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
    private val writing = Any()
    private val renewing = Any()
    @Volatile private var session: String? = null
    @Volatile private var greeting: String? = null
    @Volatile private var open = true

    fun run(input: InputStream, output: OutputStream) {
        val calls = Executors.newVirtualThreadPerTaskExecutor()
        try {
            input.bufferedReader().lineSequence().filter(String::isNotBlank).forEach { line ->
                val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
                when {
                    message?.method == "initialize" -> {
                        greeting = line
                        val reply = greet(line)
                        session = reply.session?.takeIf { reply.status == 200 }
                        answer(output, message, reply)
                        listen(output)
                    }
                    message?.get("id") == null -> answer(output, message, call(line))
                    else -> calls.submit { answer(output, message, call(line)) }
                }
            }
        } finally {
            open = false
            calls.shutdown()
            calls.awaitTermination(5, TimeUnit.SECONDS)
            session?.let { id -> runCatching { http.send(request(id).DELETE().build(), HttpResponse.BodyHandlers.discarding()) } }
        }
    }

    private fun greet(line: String): Reply {
        var admitted = false
        repeat(Patience) {
            val reply = try { post(null, line) } catch (failure: IOException) {
                if (!open) throw failure
                Thread.sleep(1000)
                return@repeat
            }
            if (reply.status == 404 && !admitted) {
                admitted = true
                runCatching(admit)
                return@repeat
            }
            return reply
        }
        return Reply(503, "Nothing answered at $endpoint")
    }

    private fun call(line: String): Reply {
        val sent = session ?: if (renew(null)) session else null
        val reply = attempt(sent, line)
        return if ((reply.status == 404 || reply.status == 0) && sent != null && renew(sent)) attempt(session, line) else reply
    }

    private fun renew(stale: String?): Boolean = synchronized(renewing) {
        if (session != stale) return session != null
        val reply = greet(greeting ?: return false)
        session = reply.session?.takeIf { reply.status == 200 }
        session?.let { attempt(it, Initialized) }
        session != null
    }

    private fun attempt(session: String?, line: String): Reply = try { post(session, line) } catch (failure: IOException) { Reply(0, failure.message ?: failure.javaClass.simpleName) }

    private fun post(session: String?, line: String): Reply {
        val response = http.send(request(session).timeout(Duration.ofMinutes(30)).header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream").POST(HttpRequest.BodyPublishers.ofString(line)).build(), HttpResponse.BodyHandlers.ofString())
        val streamed = response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")
        return Reply(response.statusCode(), if (streamed) events(response.body().lineSequence()).joinToString("\n") else response.body(),
            response.headers().firstValue(McpStreamableHttp.SessionHeader).orElse(null))
    }

    private fun request(session: String?): HttpRequest.Builder = HttpRequest.newBuilder(endpoint).apply {
        headers.forEach { (name, value) -> header(name, value) }
        session?.let { header(McpStreamableHttp.SessionHeader, it) }
    }

    private fun listen(output: OutputStream) = Thread.ofVirtual().name("mcp-relay/events").start {
        while (open) {
            val current = session
            if (current == null) { Thread.sleep(1000); continue }
            try {
                val response = http.send(request(current).header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofLines())
                when (response.statusCode()) {
                    200 -> response.body().use { lines -> events(lines.iterator().asSequence()).forEach { write(output, it) } }
                    404 -> renew(current)
                    else -> Thread.sleep(1000)
                }
            } catch (_: IOException) { if (open) Thread.sleep(1000) }
        }
    }

    private fun events(lines: Sequence<String>): Sequence<String> = sequence {
        val data = StringBuilder()
        for (line in lines + "") {
            if (line.startsWith("data:")) data.append(line.removePrefix("data:").trimStart())
            else if (line.isEmpty() && data.isNotEmpty()) { yield(data.toString()); data.clear() }
        }
    }

    private fun answer(output: OutputStream, message: JsonObject?, reply: Reply) {
        val id = message?.get("id")
        when {
            reply.status in 200..299 -> reply.body.lineSequence().filter(String::isNotBlank).forEach { write(output, it) }
            id != null -> write(output, buildJsonObject {
                put("jsonrpc", "2.0"); put("id", id)
                putJsonObject("error") { put("code", -32603); put("message", "The Reaktor kernel at $endpoint answered ${reply.status}: ${reply.body.take(300)}") }
            }.toString())
        }
    }

    private fun write(output: OutputStream, text: String) = synchronized(writing) {
        output.write((text.trim() + "\n").toByteArray())
        output.flush()
    }

    private val JsonObject.method get() = (this["method"] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val Patience = 60
        const val Initialized = """{"jsonrpc":"2.0","method":"notifications/initialized"}"""
    }
}
