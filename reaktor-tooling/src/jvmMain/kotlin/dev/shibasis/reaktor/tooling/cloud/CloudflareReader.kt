package dev.shibasis.reaktor.tooling.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

fun interface CloudflareCredentials {
    suspend fun token(): String
}

class CloudflareApiToken(private val value: String) : CloudflareCredentials {
    override suspend fun token(): String = value
}

class WranglerLogin(
    private val config: File = defaultConfig(),
    private val refresh: suspend () -> Unit,
) : CloudflareCredentials {
    override suspend fun token(): String {
        val current = read()
        if (current != null && current.second > System.currentTimeMillis() + 60_000) return current.first
        refresh()
        return read()?.first ?: throw CloudflareFailure("Wrangler is not signed in. Run `npx wrangler login` once.")
    }

    private suspend fun read(): Pair<String, Long>? = withContext(Dispatchers.IO) {
        if (!config.isFile) return@withContext null
        val text = config.readText()
        val token = Regex("""oauth_token\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1) ?: return@withContext null
        val expires = Regex("""expiration_time\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)?.let(::instantMillis) ?: 0L
        token to expires
    }

    companion object {
        fun defaultConfig(): File {
            val home = System.getProperty("user.home")
            return listOf(
                File(home, "Library/Preferences/.wrangler/config/default.toml"),
                File(System.getenv("XDG_CONFIG_HOME") ?: "$home/.config", ".wrangler/config/default.toml"),
                File(home, ".wrangler/config/default.toml"),
            ).firstOrNull { it.isFile } ?: File(home, "Library/Preferences/.wrangler/config/default.toml")
        }
    }
}

class CloudflareFailure(message: String) : Exception(message)

class CloudflareReader(
    private val accountId: String,
    private val credentials: CloudflareCredentials,
    private val clock: () -> Long = System::currentTimeMillis,
    private val analyticsHours: Int = 24,
    private val parallelism: Int = 16,
    override val id: String = "cloudflare",
) : CloudProvider {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val base = "https://api.cloudflare.com/client/v4"
    private val dash = "https://dash.cloudflare.com/$accountId"
    private val source = "Cloudflare account $accountId"

    override suspend fun read(): CloudReading = coroutineScope {
        val started = clock()
        val token = credentials.token()
        val gate = Semaphore(parallelism)
        suspend fun get(path: String): JsonElement? = gate.withPermit { call(token, path) }
        val scripts = async { get("/accounts/$accountId/workers/scripts").list("result") }
        val domains = async { get("/accounts/$accountId/workers/domains").list("result") }
        val databases = async { get("/accounts/$accountId/d1/database?per_page=100").list("result") }
        val buckets = async { get("/accounts/$accountId/r2/buckets").obj("result").objects("buckets") }
        val namespaces = async { get("/accounts/$accountId/storage/kv/namespaces?per_page=100").list("result") }
        val queues = async { get("/accounts/$accountId/queues").list("result") }
        val hyperdrives = async { get("/accounts/$accountId/hyperdrive/configs").list("result") }
        val tunnels = async { get("/accounts/$accountId/cfd_tunnel?is_deleted=false&per_page=100").list("result") }
        val zones = async { get("/zones?account.id=$accountId&per_page=50").list("result") }
        val objects = async { get("/accounts/$accountId/workers/durable_objects/namespaces?per_page=100").list("result") }
        val vpcs = async { get("/accounts/$accountId/connectivity/directory/services").list("result") }
        val analytics = async { runCatching { gate.withPermit { analytics(token) } }.getOrDefault(emptyMap()) }

        val scriptNames = scripts.await().mapNotNull { it.text("id") }
        val settings = scriptNames.map { name -> async { name to get("/accounts/$accountId/workers/scripts/$name/settings").obj("result") } }
        val deployments = scriptNames.map { name -> async { name to get("/accounts/$accountId/workers/scripts/$name/deployments").obj("result").objects("deployments") } }
        val routes = zones.await().map { zone -> async { zone to runCatching { get("/zones/${zone.text("id")}/workers/routes").list("result") }.getOrDefault(emptyList()) } }
        val ingress = tunnels.await().filter { it.text("status") != "deleted" }.map { tunnel ->
            async { tunnel to runCatching { get("/accounts/$accountId/cfd_tunnel/${tunnel.text("id")}/configurations").obj("result").obj("config").objects("ingress") }.getOrDefault(emptyList()) }
        }

        val builder = CloudflareInventoryBuilder(accountId, dash, source, clock())
        builder.zones(zones.await())
        builder.workers(scripts.await(), settings.awaitAll().toMap(), analytics.await())
        builder.deployments(deployments.awaitAll().toMap())
        builder.domains(domains.await())
        routes.awaitAll().forEach { (zone, list) -> builder.routes(zone, list) }
        builder.databases(databases.await())
        builder.buckets(buckets.await())
        builder.namespaces(namespaces.await())
        builder.queues(queues.await())
        builder.hyperdrives(hyperdrives.await())
        builder.durableObjects(objects.await())
        builder.tunnels(tunnels.await(), ingress.awaitAll().toMap())
        builder.vpcServices(vpcs.await())
        val resources = builder.resources()
        val finished = clock()
        CloudReading(
            provider = id,
            platform = CloudPlatform.Cloudflare,
            health = ProviderHealth(id, ResourceStatus.Healthy, "${resources.size} resources · ${scriptNames.size} workers"),
            readAtMillis = finished,
            durationMillis = finished - started,
            resources = resources,
            relations = builder.relations(),
            references = builder.references(),
            changes = builder.changes(),
            source = source,
        )
    }

    private suspend fun call(token: String, path: String, body: String? = null): JsonElement {
        val request = HttpRequest.newBuilder(URI.create(base + path))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .let { if (body == null) it.GET() else it.POST(HttpRequest.BodyPublishers.ofString(body)) }
            .build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        val parsed = runCatching { cloudJson.parseToJsonElement(response.body()) }.getOrNull()
        if (response.statusCode() !in 200..299) {
            val reason = parsed.objects("errors").firstOrNull()?.text("message") ?: "HTTP ${response.statusCode()}"
            throw CloudflareFailure("${path.substringBefore('?').substringAfterLast('/')}: $reason")
        }
        return parsed ?: throw CloudflareFailure("${path.substringBefore('?')}: unreadable response")
    }

    private suspend fun analytics(token: String): Map<String, WorkerTraffic> {
        val until = Instant.ofEpochMilli(clock())
        val since = until.minus(Duration.ofHours(analyticsHours.toLong()))
        val query = """
            query(${'$'}account: String!, ${'$'}since: Time!, ${'$'}until: Time!) {
              viewer { accounts(filter: { accountTag: ${'$'}account }) {
                totals: workersInvocationsAdaptive(limit: 1000, filter: { datetime_geq: ${'$'}since, datetime_leq: ${'$'}until }) {
                  sum { requests errors subrequests }
                  quantiles { cpuTimeP50 cpuTimeP99 }
                  dimensions { scriptName }
                }
                hourly: workersInvocationsAdaptive(limit: 10000, filter: { datetime_geq: ${'$'}since, datetime_leq: ${'$'}until }) {
                  sum { requests errors }
                  dimensions { scriptName datetimeHour }
                }
              } }
            }
        """.trimIndent()
        val body = buildJsonObject {
            put("query", query)
            put("variables", buildJsonObject {
                put("account", accountId)
                put("since", since.toString())
                put("until", until.toString())
            })
        }.toString()
        val account = call(token, "/graphql", body).obj("data").obj("viewer").objects("accounts").firstOrNull() ?: return emptyMap()
        val hourly = account.objects("hourly").groupBy { it.obj("dimensions").text("scriptName").orEmpty() }
        return account.objects("totals").associate { row ->
            val name = row.obj("dimensions").text("scriptName").orEmpty()
            name to WorkerTraffic(
                requests = row.obj("sum").number("requests") ?: 0.0,
                errors = row.obj("sum").number("errors") ?: 0.0,
                subrequests = row.obj("sum").number("subrequests") ?: 0.0,
                cpuP50 = row.obj("quantiles").number("cpuTimeP50"),
                cpuP99 = row.obj("quantiles").number("cpuTimeP99"),
                hourly = hourly[name].orEmpty().mapNotNull { hour ->
                    val at = instantMillis(hour.obj("dimensions").text("datetimeHour")) ?: return@mapNotNull null
                    Triple(at, hour.obj("sum").number("requests") ?: 0.0, hour.obj("sum").number("errors") ?: 0.0)
                }.sortedBy { it.first },
            )
        }
    }
}

internal data class WorkerTraffic(
    val requests: Double,
    val errors: Double,
    val subrequests: Double,
    val cpuP50: Double?,
    val cpuP99: Double?,
    val hourly: List<Triple<Long, Double, Double>>,
)

private fun JsonElement?.list(key: String): List<JsonObject> = objects(key)
