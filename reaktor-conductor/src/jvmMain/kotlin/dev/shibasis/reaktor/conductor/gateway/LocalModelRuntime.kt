package dev.shibasis.reaktor.conductor.gateway

import dev.shibasis.reaktor.cloud.cloudflare.AiCompletion
import dev.shibasis.reaktor.conductor.AgentRuntime
import dev.shibasis.reaktor.conductor.ProviderCapability
import dev.shibasis.reaktor.conductor.ModelChoice
import dev.shibasis.reaktor.conductor.RuntimeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class LocalModelServer(val base: URI, private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
    fun models(): List<String> {
        val response = http.send(HttpRequest.newBuilder(base.resolve("/v1/models")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "$base answered ${response.statusCode()} for its model list" }
        return (Json.parseToJsonElement(response.body()).jsonObject["data"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull }
    }

    fun window(model: String): Int? {
        val response = http.send(HttpRequest.newBuilder(base.resolve("/api/ps")).timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        return (Json.parseToJsonElement(response.body()).jsonObject["models"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { (it["name"] as? JsonPrimitive)?.contentOrNull == model || (it["model"] as? JsonPrimitive)?.contentOrNull == model }
            ?.let { (it["context_length"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() }
    }

    suspend fun complete(body: JsonObject): AiCompletion = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val request = HttpRequest.newBuilder(base.resolve("/v1/chat/completions")).timeout(Duration.ofMinutes(10))
            .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()}: ${response.body().take(500)}" }
        AiCompletion(Json.parseToJsonElement(response.body()).jsonObject, logId = null, millis = System.currentTimeMillis() - started)
    }

    suspend fun route(): ChatRoute {
        val served = try {
            withContext(Dispatchers.IO) { models() }
        } catch (failure: Exception) {
            error(absent())
        }
        val model = served.firstOrNull() ?: error("The local model server at $base serves no models. Load or pull a model into it.")
        return ChatRoute("Local server $base", model, window = { name -> withContext(Dispatchers.IO) { window(name) } }) { body, _ -> complete(body) }
    }

    fun capability(): ProviderCapability {
        val served = runCatching { models() }
        return ProviderCapability(RuntimeKind.Local, executable = null,
            version = served.fold({ "OpenAI-compatible server at $base" }, { null }),
            models = served.getOrDefault(emptyList()).map { ModelChoice(it, it) },
            defaultModel = served.getOrNull()?.firstOrNull(),
            notes = listOf(served.fold(
                { "An open model served on this machine through the OpenAI-compatible chat API. It reads the workspace through confined read, search and list tools and never edits files." },
                { absent() })))
    }

    private fun absent() = "No local model server answered at $base. Start an OpenAI-compatible model server such as Ollama, " +
        "LM Studio or llama.cpp, or set $UrlVariable to where it listens."

    companion object {
        const val UrlVariable = "REAKTOR_LOCAL_MODEL_URL"

        fun fromEnvironment(environment: Map<String, String> = System.getenv()): LocalModelServer =
            LocalModelServer(address(environment[UrlVariable], environment["OLLAMA_HOST"]))

        fun address(url: String?, ollamaHost: String? = null): URI {
            url?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
                val parsed = URI(if ("://" in value) value else "http://$value")
                return URI(parsed.scheme, null, requireNotNull(parsed.host) { "$UrlVariable needs a host" }, parsed.port, null, null, null)
            }
            val value = ollamaHost?.trim()?.takeIf { it.isNotEmpty() } ?: "127.0.0.1:11434"
            val parsed = URI(if ("://" in value) value else "http://$value")
            val name = parsed.host?.takeUnless { it == "0.0.0.0" } ?: "127.0.0.1"
            val port = parsed.port.takeIf { it > 0 } ?: 11434
            return URI(parsed.scheme ?: "http", null, name, port, null, null, null)
        }
    }
}

class LocalModelRuntime(
    server: () -> LocalModelServer = { LocalModelServer.fromEnvironment() },
    clock: () -> Long = System::currentTimeMillis,
) : AgentRuntime by ChatToolRuntime(RuntimeKind.Local, clock, { server().route() })
