package dev.shibasis.reaktor.cloud.api

import dev.shibasis.reaktor.service.contract.Effect
import dev.shibasis.reaktor.cloud.cloudflare.CloudflareAccounts
import dev.shibasis.reaktor.cloud.cloudflare.CloudflareLogin
import dev.shibasis.reaktor.cloud.google.GoogleSignIn
import dev.shibasis.reaktor.cloud.google.GoogleSignInRequired
import dev.shibasis.reaktor.cloud.InfrastructureSession
import dev.shibasis.reaktor.cloud.docker.DockerContexts
import dev.shibasis.reaktor.cloud.docker.DockerEngine
import dev.shibasis.reaktor.cloud.grafana.GrafanaClient
import dev.shibasis.reaktor.cloud.http.Transport
import dev.shibasis.reaktor.cloud.http.yamlDocuments
import dev.shibasis.reaktor.cloud.kubernetes.KubernetesJvmClient
import dev.shibasis.reaktor.cloud.supabase.SupabaseLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpRequest
import java.security.MessageDigest
import java.time.Duration

@Serializable
data class ApiResponse(val status: Int, val body: JsonElement?, val text: String, val millis: Long, val remoteVersion: String? = null) {
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
    private val transport = Transport(Transport.Redirecting)
    private val google = GoogleSignIn(home)
    private val declarations by lazy { CloudDeclarations.of(workspace) }
    private var grafanaAwake = false

    suspend fun surface(name: String, refresh: Boolean = false): ApiSurface = lock.withLock {
        if (!refresh) loaded[name]?.let { return@withLock it }
        val url = specUrl(name)
        val file = File(cache, "${name.replace(':', '_')}.json")
        val stale = !file.isFile || clock() - file.lastModified() > RefreshMillis
        val text = if (refresh || stale) runCatching { if (name == Kubernetes) kubernetesSpec() else fetch(url) }.onSuccess { withContext(Dispatchers.IO) { file.parentFile.mkdirs(); file.writeText(it) } }
            .getOrElse { failure -> if (file.isFile) withContext(Dispatchers.IO) { file.readText() } else throw CloudApiFailure("Could not fetch the $name API description from $url: ${failure.message}") }
        else withContext(Dispatchers.IO) { file.readText() }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        val document = (if (text.trimStart().startsWith("{")) Json.parseToJsonElement(text) else yamlDocuments(text).first()).jsonObject
        val surface = when {
            name.startsWith("gcp:") -> ApiSpecs.discovery(document, digest)
            name == "grafana" -> ApiSpecs.openApi(name, document, digest, declarations.grafana?.url?.trimEnd('/')?.plus("/api"))
            else -> ApiSpecs.openApi(name, document, digest)
        }
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

    suspend fun plan(key: String, arguments: JsonObject): WritePlan = plan(operation(key), arguments)

    suspend fun plan(operation: ApiOperation, arguments: JsonObject): WritePlan {
        val request = planned(operation.request(withDefaults(operation, JsonObject(arguments - PlanArgument))))
        val reader = if (operation.method in setOf("PUT", "PATCH", "DELETE")) runCatching { surface(surfaceOf(operation.key)) }.getOrNull()?.operations
            ?.firstOrNull { it.method == "GET" && it.path == operation.path } else null
        val live = reader?.let { read ->
            call(read.key, JsonObject(arguments.filterKeys { name -> read.parameters.any { it.name == name && it.location == "path" } })).takeIf { it.ok }?.body
        }?.let { body -> (body as? JsonObject)?.takeIf { "success" in it }?.get("result") ?: body }
        return WritePlans.plan(operation.key, request, live, if (operation.method == "DELETE") null else request.body, liveVersion = live?.let(::remoteVersion))
    }

    suspend fun call(key: String, arguments: JsonObject): ApiResponse {
        val operation = operation(key)
        val request = operation.request(withDefaults(operation, JsonObject(arguments - PlanArgument)))
        if (operation.effect != Effect.LiveRead) {
            val approved = (arguments[PlanArgument] as? JsonPrimitive)?.contentOrNull
                ?: throw CloudApiFailure("$key changes something. Plan it with cloud.api.plan and pass the plan's digest as plan")
            if (WritePlans.digest(key, planned(request)) != approved) throw CloudApiFailure("$key no longer matches the approved plan; plan it again")
        }
        val first = when (operation.api) {
            "cloudflare" -> CloudflareLogin.forWorkspace(workspace).authenticated({ token -> send(request, mapOf("Authorization" to "Bearer $token")) }, { it.status == 401 })
            "docker" -> docker(request)
            Kubernetes -> kubernetes(request)
            "grafana" -> grafana(request)
            else -> send(request, credentials(operation.api, googleAccount))
        }.let { response -> if (operation.effect == Effect.LiveRead || !response.ok) response else response.copy(remoteVersion = response.remoteVersion ?: response.body?.let(::remoteVersion)) }
        if (!operation.api.startsWith("gcp:") || first.status != 403 || operation.effect != Effect.LiveRead || google.hasApplicationDefault()) return first
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
        val response = transport.send(builder.build())
        val text = response.body()
        return ApiResponse(response.statusCode(), runCatching { Json.parseToJsonElement(text) }.getOrNull(), text.take(MaxText), clock() - started)
    }

    private fun planned(request: ApiRequest) = PlannedRequest(request.method, request.fullUrl(),
        request.body?.let { runCatching { Json.parseToJsonElement(it) }.getOrDefault(JsonPrimitive(it)) })

    private suspend fun docker(request: ApiRequest): ApiResponse = withContext(Dispatchers.IO) {
        val started = clock()
        val response = DockerEngine(dockerContext()).send(request.method, request.fullUrl(), request.body?.let(Json::parseToJsonElement))
        val body = response.json.takeUnless { it is kotlinx.serialization.json.JsonNull } ?: response.body.lines().filter { it.isNotBlank() }.takeIf { it.size > 1 }
            ?.mapNotNull { line -> runCatching { Json.parseToJsonElement(line) }.getOrNull() }?.let(::JsonArray)
        ApiResponse(response.status, body, response.body.take(MaxText), clock() - started, DockerDigest.find(response.body)?.groupValues?.get(1))
    }

    private suspend fun kubernetes(request: ApiRequest): ApiResponse = withContext(Dispatchers.IO) {
        val started = clock()
        val (status, text) = InfrastructureSession().use { session ->
            KubernetesJvmClient(kubeconfig(), session).request(request.method, request.fullUrl(), request.body,
                if (request.method == "PATCH") "application/merge-patch+json" else "application/json")
        }
        ApiResponse(status, runCatching { Json.parseToJsonElement(text) }.getOrNull(), text.take(MaxText), clock() - started)
    }

    private suspend fun grafana(request: ApiRequest): ApiResponse {
        val site = declarations.grafana ?: throw CloudApiFailure("Grafana is not declared: add grafana to ${CloudDeclarations.Path}")
        if (!grafanaAwake) GrafanaClient.waker(workspace, site)?.invoke().also { grafanaAwake = true }
        return send(request, mapOf("Authorization" to GrafanaClient.authorization(workspace, site)()))
    }

    private suspend fun kubernetesSpec(): String = withContext(Dispatchers.IO) {
        InfrastructureSession().use { session ->
            val cluster = KubernetesJvmClient(kubeconfig(), session)
            val index = Json.parseToJsonElement(cluster.request("GET", "/openapi/v3").second).jsonObject["paths"] as? JsonObject ?: JsonObject(emptyMap())
            val documents = index.values.mapNotNull { ((it as? JsonObject)?.get("serverRelativeURL") as? JsonPrimitive)?.contentOrNull }
                .mapNotNull { url -> cluster.request("GET", url).takeIf { it.first == 200 }?.second?.let { Json.parseToJsonElement(it) as? JsonObject } }
            JsonObject(mapOf(
                "openapi" to JsonPrimitive("3.0.0"),
                "info" to JsonObject(mapOf("title" to JsonPrimitive("Kubernetes"), "version" to JsonPrimitive(cluster.server))),
                "paths" to JsonObject(documents.flatMap { (it["paths"] as? JsonObject).orEmpty().entries }.associate { it.key to it.value }),
                "components" to JsonObject(mapOf("schemas" to JsonObject(documents.flatMap {
                    ((it["components"] as? JsonObject)?.get("schemas") as? JsonObject).orEmpty().entries }.associate { it.key to it.value }))),
            )).toString()
        }
    }

    private fun kubeconfig(): File = declarations.kubernetes?.kubeconfig?.let { File(workspace, it) }
        ?: System.getenv("KUBECONFIG")?.split(File.pathSeparatorChar)?.firstOrNull { it.isNotBlank() }?.let(::File)
        ?: File(home, ".kube/config")

    private fun dockerContext() = declarations.docker?.context?.let { name -> DockerContexts.read(home).firstOrNull { it.name == name } }
        ?: DockerContexts.current(home)

    var googleAccount: String? = System.getenv("GOOGLE_CLOUD_ACCOUNT")?.takeIf { it.isNotBlank() }
        private set

    fun googleAccounts(): List<String> = google.accounts()

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
        ?: File(workspace, "cloud/k3s/ssh.sh").takeIf { it.isFile }?.let {
            Regex("PROJECT_ID=\"([a-z][a-z0-9-]{4,28}[a-z0-9])\"").find(it.readText())?.groupValues?.get(1)
        }
        ?: google.project()

    suspend fun credentials(api: String, account: String? = googleAccount): Map<String, String> = when {
        api == "cloudflare" -> mapOf("Authorization" to "Bearer ${CloudflareLogin.forWorkspace(workspace).token()}")
        api == "supabase" -> mapOf("Authorization" to "Bearer ${supabaseToken() ?: throw CloudApiFailure(
            "Supabase needs a personal access token: set SUPABASE_ACCESS_TOKEN or save it as the first line of config/supabase.token")}")
        api.startsWith("gcp:") -> buildMap {
            put("Authorization", "Bearer ${googleToken(account)}")
            (System.getenv("GOOGLE_CLOUD_QUOTA_PROJECT")?.takeIf(String::isNotBlank) ?: googleProject())?.let { put("x-goog-user-project", it) }
        }
        else -> emptyMap()
    }

    fun signIn(api: String): String = when {
        api == "cloudflare" -> CloudflareLogin.forWorkspace(workspace).source
        api == "docker" -> "Docker context ${dockerContext().name}"
        api == Kubernetes -> kubeconfig().takeIf { it.isFile }?.let { "kubeconfig ${it.path.removePrefix(workspace.path + "/")}" } ?: "none: declare kubernetes.kubeconfig in ${CloudDeclarations.Path}"
        api == "grafana" -> declarations.grafana?.let { site -> site.token?.let { "token ${it.key} from ${it.file}" } ?: site.password?.let { "${site.user} password ${it.key} from ${it.file}" } }
            ?: "none: declare grafana in ${CloudDeclarations.Path}"
        api == "supabase" -> if (supabaseToken() == null) "none: set SUPABASE_ACCESS_TOKEN or save a personal access token as config/supabase.token" else "personal access token"
        api.startsWith("gcp:") -> google.describe(googleAccount)
        else -> "none"
    }

    suspend fun refusal(key: String, response: ApiResponse): String? {
        if (response.status != 401 && response.status != 403) return null
        val operation = operation(key)
        val needs = operation.permissions.takeIf { it.isNotEmpty() }?.let { " It needs one of: ${it.joinToString()}." }.orEmpty()
        return "Refused for ${signIn(operation.api)}.$needs"
    }

    private fun supabaseToken(): String? = SupabaseLogin(workspace).token()

    private suspend fun googleToken(account: String?): String =
        try { google.token(account) } catch (missing: GoogleSignInRequired) { throw CloudApiFailure(missing.message.orEmpty()) }

    companion object {
        const val RefreshMillis: Long = 7 * 86_400_000L
        private const val MaxText = 2_000_000
        val Sources: Map<String, String> = mapOf(
            "cloudflare" to "https://raw.githubusercontent.com/cloudflare/api-schemas/main/openapi.json",
            "supabase" to "https://api.supabase.com/api/v1-json",
            "grafana" to "https://raw.githubusercontent.com/grafana/grafana/main/public/openapi3.json",
            "docker" to "https://docs.docker.com/reference/api/engine/version/v1.51.yaml",
        )
        const val Kubernetes = "kubernetes"
        const val PlanArgument = "plan"
        val Apis: List<String> = Sources.keys.toList() + Kubernetes
        private val VersionFields = listOf("metadata.resourceVersion", "result.etag", "result.modified_on", "etag", "version", "generation", "Id", "updated_at", "updated",
            "result.id", "uid", "name")
        private val DockerDigest = Regex("Digest: (sha256:[0-9a-f]{64})")

        fun remoteVersion(body: JsonElement): String? = VersionFields.firstNotNullOfOrNull { path ->
            path.split('.').fold<String, JsonElement?>(body) { node, key -> (node as? JsonObject)?.get(key) }
                ?.let { it as? JsonPrimitive }?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        const val GoogleDirectory: String = "https://discovery.googleapis.com/discovery/v1/apis"
        val GoogleApis: List<String> = listOf("compute.v1", "run.v2", "pubsub.v1", "logging.v2", "monitoring.v3", "artifactregistry.v1",
            "iam.v1", "cloudresourcemanager.v3", "serviceusage.v1", "storage.v1", "secretmanager.v1", "container.v1")

        suspend fun execute(workspace: String, operation: String, arguments: String): String {
            val apis = CloudApis(File(workspace))
            val response = apis.call(operation, Json.parseToJsonElement(arguments).jsonObject)
            if (!response.ok) throw CloudApiFailure(listOfNotNull("${operation} answered HTTP ${response.status}.", apis.refusal(operation, response),
                response.text.take(600)).joinToString(" "))
            return Json.encodeToString(ApiResponse.serializer(), response.copy(body = response.body.takeIf { response.text.length < MaxText }))
        }

        fun response(output: String): ApiResponse = Json.decodeFromString(ApiResponse.serializer(), output)

        fun surfaceOf(key: String): String = if (key.startsWith("gcp:")) "gcp:" + key.removePrefix("gcp:").substringBefore(':') else key.substringBefore(':')

        private suspend fun download(url: String): String {
            val response = Transport(Transport.Redirecting).send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(120)).GET().build())
            if (response.statusCode() !in 200..299) throw CloudApiFailure("HTTP ${response.statusCode()} from $url")
            return response.body()
        }
    }

    private suspend fun specUrl(name: String): String = when {
        name.startsWith("gcp:") -> name.removePrefix("gcp:").let { api ->
            googleApis().firstOrNull { it.id == api }?.discovery
                ?: "https://${api.substringBefore('.')}.googleapis.com/\$discovery/rest?version=${api.substringAfter('.')}"
        }
        name == Kubernetes -> "${kubeconfig().path}#/openapi/v3"
        else -> sources[name] ?: throw CloudApiFailure("Unknown API $name. Known: ${Apis.joinToString()} and gcp:<api>.<version>")
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
