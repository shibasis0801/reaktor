package dev.shibasis.reaktor.tooling.mcp.door

import dev.shibasis.reaktor.tooling.ProviderAvailability
import dev.shibasis.reaktor.tooling.ToolingProviderState
import dev.shibasis.reaktor.tooling.terminateTree
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * A provider that is a child process speaking MCP over its standard streams.
 *
 * It differs from a supervised run on exactly the points a run gets right for itself: stdin stays
 * open because the conversation lasts as long as the child does, and the environment is an
 * allow-list because a third party's server has no business reading this process's credentials.
 */
class StdioMcpLink(
    private val provider: String,
    private val command: List<String>,
    private val workingDirectory: File,
    private val environment: Map<String, String>,
    private val scope: CoroutineScope,
    private val startupTimeoutMillis: Long = 240_000,
    private val callTimeoutMillis: Long = 120_000,
    private val idleStopMillis: Long = 900_000,
    private val onListChanged: () -> Unit = {},
    /** Fetched each time the child starts and handed to it alone. */
    private val secrets: () -> Map<String, String> = { emptyMap() },
) : McpLink {
    private class Child(val process: Process, val writer: BufferedWriter, val jobs: List<Job>)

    private val starting = Mutex()
    private val writing = Mutex()
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val errors = ArrayDeque<String>()
    private val generations = AtomicLong()
    @Volatile private var child: Child? = null
    @Volatile private var lastUsed = System.nanoTime()
    @Volatile private var failures = 0
    @Volatile private var retryAfter = 0L
    @Volatile private var failure: String? = null
    @Volatile private var closed = false
    @Volatile private var needsSignIn: String? = null

    override val generation: Long get() = generations.get()
    override val cheap: Boolean = false

    override fun state(): ToolingProviderState = when {
        child?.process?.isAlive == true -> observed(ProviderAvailability.Available, null)
        needsSignIn != null -> observed(ProviderAvailability.AuthRequired, needsSignIn)
        failure != null -> observed(ProviderAvailability.Unavailable, listOfNotNull(failure, tail()).joinToString(" | "))
        else -> observed(ProviderAvailability.Unknown, "not started; it starts on the first call")
    }

    override suspend fun exchange(request: JsonObject): JsonObject? {
        val running = running()
        lastUsed = System.nanoTime()
        val id = request["id"]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.content ?: it.toString() }
            ?: run { write(running, request); return null }
        val answer = CompletableDeferred<JsonObject>().also { waiting[id] = it }
        try { write(running, request) } catch (broken: Exception) { waiting.remove(id); throw broken }
        val method = (request["method"] as? JsonPrimitive)?.contentOrNull
        val budget = if (method == "initialize") startupTimeoutMillis else callTimeoutMillis
        val response = try { withTimeoutOrNull(budget) { answer.await() } } finally { waiting.remove(id) }
        if (response == null) {
            // The child may still be working; tell it to stop rather than leave it spending on an answer nobody reads.
            runCatching { write(running, buildJsonObject {
                put("jsonrpc", "2.0"); put("method", "notifications/cancelled")
                putJsonObject("params") { put("requestId", id); put("reason", "timed out after ${budget}ms") }
            }) }
            error("$provider did not answer $method within ${budget / 1000}s")
        }
        failures = 0
        lastUsed = System.nanoTime()
        return response
    }

    private suspend fun write(target: Child, message: JsonObject) = try {
        writing.withLock {
            withContext(Dispatchers.IO) { target.writer.write(message.toString()); target.writer.newLine(); target.writer.flush() }
        }
    } catch (broken: java.io.IOException) {
        throw IllegalStateException("$provider is not accepting requests: ${broken.message}")
    }

    override suspend fun close() {
        closed = true
        stop("closed")
    }

    private suspend fun running(): Child {
        child?.takeIf { it.process.isAlive }?.let { return it }
        return starting.withLock {
            child?.takeIf { it.process.isAlive }?.let { return it }
            check(!closed) { "$provider is closed" }
            val wait = retryAfter - System.currentTimeMillis()
            check(wait <= 0) { "$provider failed to start ${failures} time(s); next attempt in ${wait / 1000 + 1}s. ${failure.orEmpty()}" }
            start()
        }
    }

    private suspend fun start(): Child = withContext(Dispatchers.IO) {
        synchronized(errors) { errors.clear() }
        val secret = try { secrets() } catch (missing: CredentialMissing) { needsSignIn = missing.message; throw missing }
        needsSignIn = null
        val process = try {
            ProcessBuilder(command).directory(workingDirectory).redirectErrorStream(false).apply {
                environment().clear()
                environment().putAll(environment)
                environment().putAll(secret)
            }.start()
        } catch (spawn: Exception) {
            failed("could not start ${command.first()}: ${spawn.message}")
            throw IllegalStateException(failure)
        }
        val started = System.currentTimeMillis()
        failure = null
        val jobs = listOf(
            scope.launch(Dispatchers.IO) { readAnswers(process.inputStream) },
            scope.launch(Dispatchers.IO) { process.errorStream.bufferedReader().useLines { lines -> lines.forEach(::noted) } },
            scope.launch(Dispatchers.IO) {
                val code = process.onExit().join().exitValue()
                if (!closed && System.currentTimeMillis() - started < EARLY_EXIT_MILLIS) failed("exited with code $code within ${EARLY_EXIT_MILLIS / 1000}s of starting")
                else if (!closed) failure = "exited with code $code"
                val gone = IllegalStateException("$provider exited with code $code")
                waiting.values.toList().forEach { it.completeExceptionally(gone) }
                waiting.clear()
            },
            scope.launch { idleWatch() },
        )
        generations.incrementAndGet()
        Child(process, process.outputStream.bufferedWriter(), jobs).also { child = it }
    }

    private suspend fun stop(reason: String) {
        val current = starting.withLock { child.also { child = null } } ?: return
        noted("stopping: $reason")
        runCatching { current.writer.close() }
        current.process.terminateTree()
        current.jobs.forEach(Job::cancel)
    }

    private suspend fun idleWatch() {
        while (scope.isActive && !closed) {
            delay(IDLE_CHECK_MILLIS)
            val idleFor = (System.nanoTime() - lastUsed) / 1_000_000
            if (waiting.isEmpty() && idleFor >= idleStopMillis && child != null) { stop("idle for ${idleFor / 1000}s"); return }
        }
    }

    private fun readAnswers(stream: InputStream) {
        val reader = stream.bufferedReader()
        while (true) {
            val line = boundedLine(reader) ?: return
            if (line.isBlank()) continue
            val message = runCatching { DoorJson.parseToJsonElement(line) as? JsonObject }.getOrNull()
            // Build tools print to stdout too. A line that is not a message is noise, not a reason to drop the child.
            if (message == null) { noted("stdout: ${line.take(MAX_NOTE)}"); continue }
            val id = message["id"]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.content ?: it.toString() }
            val method = (message["method"] as? JsonPrimitive)?.contentOrNull
            when {
                method == null && id != null -> waiting.remove(id)?.complete(message)
                method != null && id != null -> refuse(message, method)
                method == "notifications/tools/list_changed" -> runCatching(onListChanged)
            }
        }
    }

    /** The door declared no client capabilities, so a request from the child is one it should not have sent. */
    private fun refuse(message: JsonObject, method: String) {
        val current = child ?: return
        scope.launch { runCatching { write(current, buildJsonObject {
            put("jsonrpc", "2.0"); put("id", message.getValue("id"))
            putJsonObject("error") { put("code", -32601); put("message", "The Reaktor door does not serve $method") }
        }) } }
    }

    private fun boundedLine(reader: java.io.BufferedReader): String? {
        val value = StringBuilder()
        while (true) {
            val char = reader.read()
            if (char == -1) return value.toString().takeIf { it.isNotEmpty() }
            if (char == 10) return value.toString()
            check(value.length < MAX_LINE) { "$provider sent a line over ${MAX_LINE / 1_000_000}MB" }
            value.append(char.toChar())
        }
    }

    private fun failed(reason: String) {
        failure = reason
        failures += 1
        retryAfter = System.currentTimeMillis() + minOf(60_000L, 1_000L shl minOf(failures, 6))
    }

    private fun noted(line: String) = synchronized(errors) {
        errors.addLast(line.take(MAX_NOTE))
        while (errors.size > MAX_NOTES) errors.removeFirst()
    }

    private fun tail(): String? = synchronized(errors) { errors.takeLast(3).joinToString(" / ").takeIf { it.isNotEmpty() } }

    private fun observed(availability: ProviderAvailability, detail: String?) =
        ToolingProviderState(provider, availability, detail, System.currentTimeMillis())

    private companion object {
        const val MAX_LINE = 64_000_000
        const val MAX_NOTE = 400
        const val MAX_NOTES = 60
        const val EARLY_EXIT_MILLIS = 5_000L
        const val IDLE_CHECK_MILLIS = 30_000L
    }
}
