package dev.shibasis.reaktor.conductor.cli

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

class AntigravityApp(
    private val home: File = File(System.getProperty("user.home")),
    private val bundle: File = File("/Applications/Antigravity.app"),
    private val alive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
) {
    data class Hub(val port: Int, val csrf: String)
    data class Project(val id: String, val folder: String, val writable: Boolean, val git: Boolean = false)
    data class Run(val running: Boolean, val steps: Int, val ended: String?)

    private val http: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build()
    @Volatile private var cachedLabels: Pair<Long, Map<String, String>>? = null
    private val server: File get() = bundle.resolve("Contents/Resources/bin/language_server")
    private val daemon: File get() = home.resolve(".gemini/antigravity/daemon")

    val installed: Boolean get() = server.canExecute()

    fun transcript(conversation: String): File =
        home.resolve(".gemini/antigravity/brain/$conversation/.system_generated/logs/transcript.jsonl")

    fun hub(): Hub? = daemon.listFiles { file -> file.name.startsWith("hub_") && file.name.endsWith(".json") }.orEmpty()
        .mapNotNull { file -> runCatching { file.lastModified() to Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() }
        .filter { (_, data) -> data.long("pid")?.let(alive) == true }
        .maxByOrNull { it.first }?.second
        ?.let { data -> Hub(data.long("httpPort")?.toInt() ?: return null, data.string("csrfToken") ?: return null) }

    suspend fun connect(timeoutMillis: Long = 90_000): Hub {
        hub()?.let { return it }
        check(installed) { "Antigravity is not installed at ${bundle.path}" }
        withContext(Dispatchers.IO) { ProcessBuilder("open", "-g", "-a", bundle.path).start().waitFor(30, TimeUnit.SECONDS) }
        return withTimeoutOrNull(timeoutMillis) {
            var found = hub()
            while (found == null) {
                delay(500)
                found = hub()
            }
            found
        } ?: error("Antigravity did not start its language server within ${timeoutMillis / 1000} seconds")
    }

    fun call(hub: Hub, method: String, body: JsonObject = JsonObject(emptyMap())): JsonObject {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${hub.port}/exa.language_server_pb.LanguageServerService/$method"))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Connect-Protocol-Version", "1")
            .header("x-codeium-csrf-token", hub.csrf)
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() == 200) { "Antigravity $method answered ${response.statusCode()}: ${response.body().take(300)}" }
        return response.body().takeIf(String::isNotBlank)?.let { Json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
    }

    fun projects(hub: Hub): List<Project> = (call(hub, "ListProjects")["projectIds"] as? JsonArray).orEmpty()
        .mapNotNull { it.jsonPrimitive.contentOrNull }
        .flatMap { id -> projectFolders(id, call(hub, "ReadProject", buildJsonObject { put("id", id) })["project"] as? JsonObject) }

    fun project(hub: Hub, directory: File): Project? = choose(projects(hub), directory.canonicalPath)

    fun start(hub: Hub, project: Project, directory: File, model: String, title: String, prompt: String): String {
        val answer = agentapi(hub, project.id, directory, "new-conversation", "--model=$model", "--title=$title", prompt)
        return conversationId(answer) ?: error("Antigravity started a conversation but named none: ${answer.toString().take(300)}")
    }

    fun send(hub: Hub, project: Project?, directory: File, conversation: String, text: String) {
        agentapi(hub, project?.id, directory, "send-message", conversation, text)
    }

    fun model(hub: Hub, conversation: String): String? {
        val placeholder = planModel(call(hub, "GetConversationMetadata", buildJsonObject { put("conversationId", conversation) })) ?: return null
        return labels(hub)[placeholder] ?: placeholder
    }

    private fun labels(hub: Hub): Map<String, String> {
        val now = System.currentTimeMillis()
        cachedLabels?.takeIf { now - it.first < LabelMillis }?.let { return it.second }
        return modelLabels(call(hub, "GetCascadeModelConfigData")).also { cachedLabels = now to it }
    }

    fun run(hub: Hub, conversation: String): Run? =
        runOf((call(hub, "GetAllCascadeTrajectories")["trajectorySummaries"] as? JsonObject)?.get(conversation) as? JsonObject)

    fun cancel(hub: Hub, conversation: String) {
        call(hub, "CancelCascadeInvocation", buildJsonObject { put("cascadeId", conversation) })
    }

    private fun agentapi(hub: Hub, project: String?, directory: File, vararg arguments: String): JsonObject {
        val process = ProcessBuilder(listOf(server.path, "agentapi") + arguments).directory(directory).apply {
            environment()["ANTIGRAVITY_LS_ADDRESS"] = "127.0.0.1:${hub.port}"
            environment()["ANTIGRAVITY_CSRF_TOKEN"] = hub.csrf
            project?.let { environment()["ANTIGRAVITY_PROJECT_ID"] = it }
        }.start()
        val standard = StringBuilder()
        val failures = StringBuilder()
        val readers = listOf(
            Thread { standard.append(process.inputStream.bufferedReader().readText()) },
            Thread { failures.append(process.errorStream.bufferedReader().readText()) },
        ).onEach { it.isDaemon = true; it.start() }
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("Antigravity agentapi ${arguments.first()} did not answer within 120 seconds")
        }
        readers.forEach { it.join(5_000) }
        val output = standard.toString()
        val errors = failures.toString()
        val answer = runCatching { Json.parseToJsonElement(output.ifBlank { errors }).jsonObject }
            .getOrElse { error("Antigravity agentapi ${arguments.first()} exited ${process.exitValue()}: ${(errors.ifBlank { output }).takeLast(400)}") }
        answer.string("error")?.takeIf(String::isNotBlank)?.let { error("Antigravity refused ${arguments.first()}: $it") }
        return answer
    }

    companion object {
        private val Uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        internal fun choose(projects: List<Project>, path: String): Project? =
            projects.filter { path == it.folder || path.startsWith(it.folder.trimEnd('/') + "/") }
                .sortedWith(compareByDescending<Project> { it.folder.length }.thenByDescending { it.writable }.thenByDescending { it.git })
                .firstOrNull()

        internal fun projectFolders(id: String, project: JsonObject?): List<Project> =
            ((project?.get("projectResources") as? JsonObject)?.get("resources") as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .mapNotNull { resource ->
                    val git = resource["gitFolder"] as? JsonObject
                    val uri = git?.string("folderUri") ?: resource.string("folderUri") ?: return@mapNotNull null
                    if (!uri.startsWith("file://")) return@mapNotNull null
                    Project(id, File(URI(uri)).canonicalPath, git == null || git["allowWrite"]?.jsonPrimitive?.booleanOrNull == true, git != null)
                }

        private val ConversationKeys = listOf("conversationId", "conversation_id", "cascadeId", "cascade_id")

        internal fun conversationId(answer: JsonElement): String? = when (answer) {
            is JsonObject -> ConversationKeys.firstNotNullOfOrNull { key -> (answer[key] as? JsonPrimitive)?.contentOrNull?.takeIf(Uuid::matches) }
                ?: answer.values.firstNotNullOfOrNull(::conversationId)
            is JsonArray -> answer.firstNotNullOfOrNull(::conversationId)
            else -> null
        }

        private const val LabelMillis = 600_000L

        internal fun planModel(metadata: JsonObject): String? = listOf(
            listOf("response", "conversationMetadata", "metadata"), listOf("conversationMetadata", "metadata"), listOf("metadata"),
        ).firstNotNullOfOrNull { prefix -> metadata.path(*(prefix + listOf("staticConfig", "cascadeConfig", "plannerConfig")).toTypedArray())?.string("planModel") }

        fun variant(label: String): String = antigravityModelNames.entries.firstOrNull { it.value == label }?.key
            ?: label.lowercase().replace(" (", "-").replace(")", "").trim().replace(' ', '-')

        internal fun modelLabels(data: JsonObject): Map<String, String> = (data["clientModelConfigs"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { config ->
                val label = config.string("label") ?: return@mapNotNull null
                val model = (config["modelOrAlias"] as? JsonObject)?.string("model") ?: return@mapNotNull null
                model to label
            }.toMap()

        internal fun runOf(summary: JsonObject?): Run? = summary?.let {
            Run(it.string("status") == "CASCADE_RUN_STATUS_RUNNING", it.long("stepCount")?.toInt() ?: 0, it.string("latestExecutorTerminationReason"))
        }

        private fun JsonObject.path(vararg keys: String): JsonObject? =
            keys.fold(this as JsonObject?) { node, key -> node?.get(key) as? JsonObject }

        private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.long(key: String): Long? = (get(key) as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    }
}

object AntigravityConversationModels {
    private const val KeptMillis = 600_000L
    private val app = AntigravityApp()
    private val known = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, String?>>()

    fun of(conversation: String): String? {
        val now = System.currentTimeMillis()
        known[conversation]?.takeIf { now - it.first < KeptMillis }?.let { return it.second }
        val model = runCatching { app.hub()?.let { hub -> app.model(hub, conversation)?.let(AntigravityApp::variant) } }.getOrNull()
        known[conversation] = now to model
        return model
    }
}
