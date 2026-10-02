package dev.shibasis.reaktor.tooling.api

import com.google.auth.oauth2.GoogleCredentials
import dev.shibasis.reaktor.tooling.cloud.CloudflareAccounts
import dev.shibasis.reaktor.tooling.cloud.CloudflareLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.TimeUnit

@Serializable
data class ApiResponse(val status: Int, val body: JsonElement?, val text: String, val millis: Long) {
    val ok: Boolean get() = status in 200..299
}

@Serializable
data class GoogleApi(val id: String, val title: String, val description: String, val discovery: String, val preferred: Boolean)

@Serializable
data class ApiSpecRecord(val name: String, val url: String, val digest: String, val fetchedAtMillis: Long, val operations: Int)

class CloudApiFailure(message: String) : Exception(message)

class CloudApis(
    private val workspace: File,
    private val home: File = File(System.getProperty("user.home")),
    private val clock: () -> Long = System::currentTimeMillis,
    private val sources: Map<String, String> = Sources,
    private val fetch: suspend (String) -> String = ::download,
) {
    private val cache = File(home, ".reaktor/api-specs")
    private val loaded = HashMap<String, ApiSurface>()
    private val lock = Mutex()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    suspend fun surface(name: String, refresh: Boolean = false): ApiSurface = lock.withLock {
        if (!refresh) loaded[name]?.let { return@withLock it }
        val url = specUrl(name)
        val file = File(cache, "${name.replace(':', '_')}.json")
        val stale = !file.isFile || clock() - file.lastModified() > RefreshMillis
        val text = if (refresh || stale) runCatching { fetch(url) }.onSuccess { withContext(Dispatchers.IO) { file.parentFile.mkdirs(); file.writeText(it) } }
            .getOrElse { failure -> if (file.isFile) withContext(Dispatchers.IO) { file.readText() } else throw CloudApiFailure("Could not fetch the $name API description from $url: ${failure.message}") }
        else withContext(Dispatchers.IO) { file.readText() }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val document = Json.parseToJsonElement(text).jsonObject
        val surface = if (name.startsWith("gcp:")) ApiSpecs.discovery(document, digest) else ApiSpecs.openApi(name, document, digest)
        loaded[name] = surface
        surface
    }

    fun records(): List<ApiSpecRecord> = cache.listFiles { file -> file.extension == "json" && file.name != "gcp-directory.json" }.orEmpty().map { file ->
        val name = file.nameWithoutExtension.replaceFirst('_', ':')
        ApiSpecRecord(name, sources[name].orEmpty(), loaded[name]?.digest.orEmpty(), file.lastModified(), loaded[name]?.operations?.size ?: 0)
    }

    suspend fun operation(key: String): ApiOperation {
        val surface = surface(surfaceOf(key))
        return surface[key.substringAfterLast(':')] ?: throw CloudApiFailure("No operation $key in ${surface.title}")
    }

    suspend fun call(key: String, arguments: JsonObject): ApiResponse {
        val operation = operation(key)
        val request = operation.request(withDefaults(operation, arguments))
        val first = send(request, credentials(operation.api, googleAccount))
        if (!operation.api.startsWith("gcp:") || first.status != 403 || operation.effect != ApiEffect.Read || adc()) return first
        return (googleAccounts() - setOfNotNull(googleAccount)).firstNotNullOfOrNull { account ->
            send(request, credentials(operation.api, account)).takeIf { it.ok }?.also { googleAccount = account }
        } ?: first
    }

    private suspend fun send(request: ApiRequest, headers: Map<String, String>): ApiResponse {
        val started = clock()
        val builder = HttpRequest.newBuilder(URI.create(request.fullUrl())).timeout(Duration.ofSeconds(60))
            .header("Accept", "application/json")
            .method(request.method, request.body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        if (request.body != null) builder.header("Content-Type", "application/json")
        (headers + request.headers).forEach { (name, value) -> builder.header(name, value) }
        val response = http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofString()).await()
        val text = response.body()
        return ApiResponse(response.statusCode(), runCatching { Json.parseToJsonElement(text) }.getOrNull(), text.take(MaxText), clock() - started)
    }

    var googleAccount: String? = System.getenv("GOOGLE_CLOUD_ACCOUNT")?.takeIf { it.isNotBlank() }
        private set

    fun googleAccounts(): List<String> = runCatching {
        command(listOf(gcloud(), "auth", "list", "--format=value(account)")).lines().map(String::trim).filter { "@" in it }
    }.getOrDefault(emptyList())

    fun withDefaults(operation: ApiOperation, arguments: JsonObject): JsonObject {
        val defaults = buildMap<String, String> {
            if (operation.api == "cloudflare") CloudflareAccounts.of(workspace)?.let { put("account_id", it); put("account_identifier", it) }
            if (operation.api.startsWith("gcp:")) googleProject()?.let { project ->
                put("project", project); put("projectId", project)
            }
        }
        val missing = operation.parameters.filter { it.location == "path" && it.name !in arguments && it.name in defaults }
        if (missing.isEmpty()) return arguments
        return JsonObject(arguments + missing.associate { parameter ->
            val value = defaults.getValue(parameter.name)
            parameter.name to JsonPrimitive(if (parameter.reserved && operation.api.startsWith("gcp:")) "projects/$value" else value)
        })
    }

    fun googleProject(): String? = System.getenv("GOOGLE_CLOUD_PROJECT")?.takeIf { it.isNotBlank() }
        ?: runCatching { command(listOf(gcloud(), "config", "get-value", "project")).trim().takeIf { it.isNotEmpty() && it != "(unset)" } }.getOrNull()

    suspend fun credentials(api: String, account: String? = googleAccount): Map<String, String> = when {
        api == "cloudflare" -> mapOf("Authorization" to "Bearer ${CloudflareLogin.forWorkspace(workspace).token()}")
        api == "supabase" -> mapOf("Authorization" to "Bearer ${supabaseToken() ?: throw CloudApiFailure(
            "Supabase needs a personal access token: set SUPABASE_ACCESS_TOKEN or save it as the first line of config/supabase.token")}")
        api.startsWith("gcp:") -> mapOf("Authorization" to "Bearer ${googleToken(account)}")
        else -> emptyMap()
    }

    fun signIn(api: String): String = when {
        api == "cloudflare" -> CloudflareLogin.forWorkspace(workspace).source
        api == "supabase" -> if (supabaseToken() == null) "none: set SUPABASE_ACCESS_TOKEN or save a personal access token as config/supabase.token" else "personal access token"
        api.startsWith("gcp:") -> googleAccount ?: if (adc()) "application default credentials" else "the active gcloud account"
        else -> "none"
    }

    suspend fun refusal(key: String, response: ApiResponse): String? {
        if (response.status != 401 && response.status != 403) return null
        val operation = operation(key)
        val needs = operation.permissions.takeIf { it.isNotEmpty() }?.let { " It needs one of: ${it.joinToString()}." }.orEmpty()
        return "Refused for ${signIn(operation.api)}.$needs"
    }

    private fun supabaseToken(): String? = System.getenv("SUPABASE_ACCESS_TOKEN")?.takeIf { it.isNotBlank() }
        ?: listOf("config/supabase.token", "config/mcp/supabase.token").map { File(workspace, it) }.firstOrNull(File::isFile)
            ?.readLines()?.map(String::trim)?.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }

    private fun adc(): Boolean = System.getenv("GOOGLE_APPLICATION_CREDENTIALS") != null || File(home, ".config/gcloud/application_default_credentials.json").isFile

    private suspend fun googleToken(account: String?): String = withContext(Dispatchers.IO) {
        if (adc()) runCatching {
            GoogleCredentials.getApplicationDefault().createScoped(listOf("https://www.googleapis.com/auth/cloud-platform")).apply { refreshIfExpired() }.accessToken.tokenValue
        }.getOrNull()?.let { return@withContext it }
        command(listOf(gcloud(), "auth", "print-access-token") + listOfNotNull(account?.let { "--account=$it" })).trim().takeIf { it.isNotEmpty() }
            ?: throw CloudApiFailure("Google Cloud needs a sign-in: run gcloud auth login, or gcloud auth application-default login")
    }

    private fun gcloud(): String = (System.getenv("PATH").orEmpty().split(File.pathSeparator) + listOf("/opt/homebrew/bin", "/usr/local/bin",
        "${home.path}/google-cloud-sdk/bin", "/opt/homebrew/share/google-cloud-sdk/bin")).map { File(it, "gcloud") }.firstOrNull { it.canExecute() }?.path ?: "gcloud"

    private fun command(argv: List<String>): String {
        val process = ProcessBuilder(argv).redirectErrorStream(false).start()
        process.outputStream.close()
        val out = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) { process.destroyForcibly(); throw CloudApiFailure("${argv.first()} timed out") }
        if (process.exitValue() != 0) throw CloudApiFailure("${argv.joinToString(" ")} failed: ${process.errorStream.bufferedReader().readText().take(300)}")
        return out
    }

    companion object {
        const val RefreshMillis: Long = 7 * 86_400_000L
        private const val MaxText = 2_000_000
        val Sources: Map<String, String> = mapOf(
            "cloudflare" to "https://raw.githubusercontent.com/cloudflare/api-schemas/main/openapi.json",
            "supabase" to "https://api.supabase.com/api/v1-json",
        )
        const val GoogleDirectory: String = "https://discovery.googleapis.com/discovery/v1/apis"
        val GoogleApis: List<String> = listOf("compute.v1", "run.v2", "pubsub.v1", "logging.v2", "monitoring.v3", "artifactregistry.v1",
            "iam.v1", "cloudresourcemanager.v3", "serviceusage.v1", "storage.v1", "secretmanager.v1", "container.v1")

        suspend fun execute(call: dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.CloudApiCall): String {
            val apis = CloudApis(File(call.workspace))
            val response = apis.call(call.operation, Json.parseToJsonElement(call.arguments).jsonObject)
            if (!response.ok) throw CloudApiFailure(listOfNotNull("${call.operation} answered HTTP ${response.status}.", apis.refusal(call.operation, response),
                response.text.take(600)).joinToString(" "))
            return Json.encodeToString(ApiResponse.serializer(), response.copy(body = response.body.takeIf { response.text.length < MaxText }))
        }

        fun response(output: String): ApiResponse = Json.decodeFromString(ApiResponse.serializer(), output)

        fun surfaceOf(key: String): String = if (key.startsWith("gcp:")) "gcp:" + key.removePrefix("gcp:").substringBefore(':') else key.substringBefore(':')

        private suspend fun download(url: String): String {
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
                .sendAsync(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).GET().build(), HttpResponse.BodyHandlers.ofString()).await()
            if (response.statusCode() !in 200..299) throw CloudApiFailure("HTTP ${response.statusCode()} from $url")
            return response.body()
        }
    }

    private suspend fun specUrl(name: String): String = when {
        name.startsWith("gcp:") -> name.removePrefix("gcp:").let { api ->
            googleApis().firstOrNull { it.id == api }?.discovery
                ?: "https://${api.substringBefore('.')}.googleapis.com/\$discovery/rest?version=${api.substringAfter('.')}"
        }
        else -> sources[name] ?: throw CloudApiFailure("Unknown API $name. Known: ${sources.keys.joinToString()} and gcp:<api>.<version>")
    }

    suspend fun googleApis(): List<GoogleApi> {
        directory?.let { return it }
        val file = File(cache, "gcp-directory.json")
        val text = if (file.isFile && clock() - file.lastModified() < RefreshMillis) withContext(Dispatchers.IO) { file.readText() }
        else runCatching { fetch(GoogleDirectory) }.onSuccess { withContext(Dispatchers.IO) { file.parentFile.mkdirs(); file.writeText(it) } }
            .getOrElse { if (file.isFile) withContext(Dispatchers.IO) { file.readText() } else return emptyList() }
        val items = (Json.parseToJsonElement(text).jsonObject["items"] as? kotlinx.serialization.json.JsonArray).orEmpty()
        return items.mapNotNull { item ->
            val node = item as? JsonObject ?: return@mapNotNull null
            fun text(key: String) = (node[key] as? JsonPrimitive)?.content
            GoogleApi("${text("name")}.${text("version")}", text("title").orEmpty(), text("description").orEmpty(),
                text("discoveryRestUrl") ?: return@mapNotNull null, (node["preferred"] as? JsonPrimitive)?.content == "true")
        }.also { directory = it }
    }

    private var directory: List<GoogleApi>? = null

    private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
}
