package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.conductor.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.concurrent.Semaphore

/** Bounded native peer questions using A2A 1.0 JSON-RPC and the existing task journal. */
class AgentA2aWorkspace(private val workspace: AgentWorkspace) {
    private val blocking = Semaphore(2)
    private class PeerFailure(val code: Int, message: String) : IllegalArgumentException(message)
    fun card(provider: RuntimeKind, url: String): JsonObject {
        requireProvider(provider)
        return buildJsonObject {
            put("name", "Reaktor ${provider.name}"); put("description", "Bounded read-only engineering questions through the native runtime. Reuse messageId only for an identical question; poll retained tasks. Active-task continuation is not supported by this profile.")
            put("version", "1.0.0")
            putJsonArray("supportedInterfaces") { addJsonObject { put("url", url); put("protocolBinding", "JSONRPC"); put("protocolVersion", "1.0") } }
            putJsonObject("capabilities") { put("streaming", false); put("pushNotifications", false) }
            put("defaultInputModes", JsonArray(listOf(JsonPrimitive("text/plain"))))
            put("defaultOutputModes", JsonArray(listOf(JsonPrimitive("text/plain"))))
            putJsonArray("skills") { addJsonObject { put("id", "engineering-question"); put("name", "Engineering question"); put("description", "A read-only question of at most 6000 characters, with a one-minute runtime budget; optional metadata.parentRunId binds delegated usage."); put("tags", JsonArray(listOf(JsonPrimitive("code-review")))) } }
            putJsonObject("securitySchemes") { putJsonObject("workspaceBearer") { putJsonObject("httpAuthSecurityScheme") { put("scheme", "Bearer") } } }
            putJsonArray("security") { addJsonObject { put("workspaceBearer", JsonArray(emptyList())) } }
        }
    }

    suspend fun exchange(provider: RuntimeKind, body: String, version: String?): JsonObject {
        val request = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return error(JsonNull, -32700, "Parse error")
        val id = request["id"] ?: JsonNull
        if (request["jsonrpc"] != JsonPrimitive("2.0") || id == JsonNull || id !is JsonPrimitive || (!id.isString && id.longOrNull == null)) return error(id, -32600, "Invalid request")
        if (version != "1.0") return error(id, -32009, "VersionNotSupportedError: this interface requires A2A-Version 1.0")
        return try {
            requireProvider(provider)
            require(request.keys.all { it in setOf("jsonrpc", "id", "method", "params") })
            val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
            val value = when (request.text("method")) {
                "SendMessage" -> send(provider, params)
                "GetTask" -> {
                    params.keys("id", optional = setOf("historyLength"))
                    params["historyLength"]?.let { require(it is JsonPrimitive && !it.isString && it.intOrNull?.let { count -> count in 0..20 } == true) }
                    task(get(provider, params.text("id")))
                }
                "CancelTask" -> {
                    params.keys("id")
                    val saved = get(provider, params.text("id"))
                    if (saved.status != AgentRunStatus.Running && saved.recovery == AgentRecovery.None) throw PeerFailure(-32002, "TaskNotCancelableError")
                    task(workspace.cancel(saved.id))
                }
                "ListTasks", "SendStreamingMessage", "SubscribeToTask" -> throw PeerFailure(-32004, "UnsupportedOperationError: use GetTask on the retained task id")
                else -> return error(id, -32601, "UnsupportedOperationError")
            }
            buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", value) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PeerFailure) { error(id, failure.code, requireNotNull(failure.message)) }
        catch (failure: Exception) { error(id, -32602, failure.message ?: "Invalid parameters") }
    }

    private suspend fun send(provider: RuntimeKind, params: JsonObject): JsonObject {
        params.keys("message", optional = setOf("configuration", "metadata"))
        val message = params.getValue("message").jsonObject
        message.keys("messageId", "role", "parts", optional = setOf("contextId"))
        require(message["role"] == JsonPrimitive("ROLE_USER"))
        val messageId = message.text("messageId")
        require(messageId.length in 1..128)
        val parts = message.getValue("parts").jsonArray
        require(parts.size in 1..16)
        val text = parts.joinToString("\n") { part -> part.jsonObject.keys("text"); part.jsonObject.text("text") }
        require(text.isNotBlank() && text.length <= 6000) { "Question exceeds the peer budget" }
        val configuration = params["configuration"]?.jsonObject ?: JsonObject(emptyMap())
        configuration.keys(optional = setOf("returnImmediately", "historyLength", "acceptedOutputModes"))
        configuration["returnImmediately"]?.let { require(it is JsonPrimitive && !it.isString && it.booleanOrNull != null) }
        configuration["historyLength"]?.let { require(it is JsonPrimitive && !it.isString && it.intOrNull?.let { count -> count in 0..20 } == true) }
        configuration["acceptedOutputModes"]?.jsonArray?.let { modes -> require(modes.isNotEmpty() && modes.all { it == JsonPrimitive("text/plain") }) }
        val metadata = params["metadata"]?.jsonObject ?: JsonObject(emptyMap())
        metadata.keys(optional = setOf("parentRunId"))
        val context = ContextPacket(workspaceId = workspace.info().workspaceRoot, principalId = "authenticated-workspace-peer", source = "a2a/1.0",
            revision = digest(canonical(JsonObject(params.filterKeys { it != "configuration" })).toString()), freshness = "request-bound")
        val immediate = configuration["returnImmediately"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!immediate && !blocking.tryAcquire()) throw PeerFailure(-32000, "Blocking peer capacity is busy; use returnImmediately and GetTask")
        try {
            var run = workspace.submit(AgentSubmission("a2a/${provider.name}/$messageId", provider, text,
                threadId = message["contextId"]?.let { message.text("contextId") }, parentRunId = metadata["parentRunId"]?.let { metadata.text("parentRunId") },
                context = context, timeoutMillis = 60000))
            while (!immediate && run.status == AgentRunStatus.Running && run.pending.isEmpty()) {
                run = workspace.awaitChange(run.id, run.revision, 30000)
            }
            return buildJsonObject { put("task", task(run)) }
        } finally {
            if (!immediate) blocking.release()
        }
    }

    private fun get(provider: RuntimeKind, id: String): AgentRunRecord = runCatching { workspace.get(id) }.getOrNull()
        ?.takeIf { isPeer(it, provider) } ?: throw PeerFailure(-32001, "TaskNotFoundError")
    private fun isPeer(run: AgentRunRecord, provider: RuntimeKind) = run.provider == provider && runCatching {
        val submitted = workspace.submission(run.id)
        submitted.requestId.startsWith("a2a/${provider.name}/") && submitted.context?.source == "a2a/1.0" && !submitted.allowWrites
    }.getOrDefault(false)
    private fun requireProvider(provider: RuntimeKind) { require(provider in setOf(RuntimeKind.Codex, RuntimeKind.ClaudeCode) && provider in workspace.info().providers) { "Native peer is not configured" } }

    private fun task(run: AgentRunRecord): JsonObject = buildJsonObject {
        put("id", run.id); put("contextId", run.threadId)
        putJsonObject("status") {
            put("state", when {
                run.status == AgentRunStatus.Completed -> "TASK_STATE_COMPLETED"
                run.status == AgentRunStatus.Failed -> "TASK_STATE_FAILED"
                run.status == AgentRunStatus.Interrupted && run.failure == "Stopped by the user" -> "TASK_STATE_CANCELED"
                run.status == AgentRunStatus.Interrupted || run.pending.isNotEmpty() -> "TASK_STATE_INPUT_REQUIRED"
                else -> "TASK_STATE_WORKING"
            })
            put("timestamp", Instant.ofEpochMilli(run.updatedAt).toString())
        }
        if (run.output.isNotEmpty()) putJsonArray("artifacts") { addJsonObject {
            put("artifactId", "${run.id}/${run.revision}"); put("name", "Native peer answer")
            putJsonArray("parts") { addJsonObject { put("text", run.output.take(6000)) } }
        } }
        putJsonObject("metadata") {
            put("provider", run.provider.name); put("revision", run.revision)
            put("partial", run.outputTruncated || run.output.length > 6000)
            run.parentRunId?.let { put("parentRunId", it) }
            run.usage?.let { put("usage", ConductorJson.encodeToJsonElement(AgentUsage.serializer(), it)) }
            run.failure?.let { put("failure", it.take(512)) }
        }
    }

    private fun canonical(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonical(it.value) })
        is JsonArray -> JsonArray(value.map(::canonical))
        else -> value
    }
    private fun JsonObject.text(name: String) = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$name must be a string")
    private fun JsonObject.keys(vararg required: String, optional: Set<String> = emptySet()) {
        require(keys.containsAll(required.toList()) && keys.all { it in required || it in optional }) { "Unsupported or missing profile fields" }
    }
    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id); putJsonObject("error") { put("code", code); put("message", message.take(512)) }
    }
}
