package dev.shibasis.reaktor.cloud.supabase

import dev.shibasis.reaktor.core.framework.lenientJson
import dev.shibasis.reaktor.core.truth.TruthClass
import dev.shibasis.reaktor.cloud.http.*
import dev.shibasis.reaktor.cloud.inventory.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.net.http.HttpRequest
import java.time.Duration

class SupabaseReader(
    private val token: () -> String?,
    private val api: String = "https://api.supabase.com",
    private val clock: () -> Long = System::currentTimeMillis,
    private val transport: Transport = Transport(),
    override val id: String = "supabase",
) : CloudProvider {
    override val truth = TruthClass.Live

    override suspend fun read(): CloudReading = coroutineScope {
        val started = clock()
        val projects = get("/v1/projects").list()
        val health = projects.filter { it.active }.map { project -> async { project.ref to services(project.ref) } }.awaitAll().toMap()
        val readAt = clock()
        val unreadable = health.filterValues { it == null }.keys
        CloudReading(
            provider = id,
            platform = CloudPlatform.Supabase,
            truth = truth,
            health = ProviderHealth(id, if (unreadable.isEmpty()) ResourceStatus.Healthy else ResourceStatus.Degraded,
                "${projects.size} projects" + if (unreadable.isEmpty()) "" else " · health unreadable: ${unreadable.joinToString()}"),
            readAtMillis = readAt,
            durationMillis = readAt - started,
            resources = projects.map { resource(it, health[it.ref], readAt) },
            source = "Supabase Management API",
        )
    }

    private suspend fun services(ref: String): List<JsonObject>? = try {
        get("/v1/projects/$ref/health?services=${Services.joinToString(",")}").list()
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private fun resource(project: JsonObject, services: List<JsonObject>?, readAt: Long): CloudResource {
        val ref = project.ref
        val database = project.obj("database")
        val sick = services.orEmpty().filter { it.flag("healthy") == false }.mapNotNull { it.text("name") }
        val stated = status(project.text("status"))
        return CloudResource(
            id = "pg:$ref",
            kind = CloudKind.Postgres,
            platform = CloudPlatform.Supabase,
            name = project.text("name") ?: ref,
            status = if (stated == ResourceStatus.Healthy && sick.isNotEmpty()) ResourceStatus.Degraded else stated,
            statusDetail = listOfNotNull(project.text("status")?.lowercase()?.replace('_', ' '),
                sick.takeIf { it.isNotEmpty() }?.let { "unhealthy: ${it.joinToString()}" }).joinToString(" · "),
            region = project.text("region"),
            attributes = mapOfNotNull("project" to ref, "organization" to project.text("organization_slug"), "host" to database.text("host"),
                "postgres" to database.text("version"), "channel" to database.text("release_channel"),
                "services" to services?.joinToString { "${it.text("name")}=${it.text("status")?.lowercase()}" }),
            evidence = listOfNotNull(CloudEvidence(CloudSource.SupabaseApi, "v1/projects", readAt),
                services?.let { CloudEvidence(CloudSource.SupabaseApi, "v1/projects/$ref/health", readAt) }),
            consoleUrl = "https://supabase.com/dashboard/project/$ref",
            createdAtMillis = instantMillis(project.text("created_at")),
        )
    }

    private suspend fun get(path: String): JsonElement {
        val secret = token() ?: throw MissingCredential(SupabaseLogin.TokenFiles.first(), setOf("SUPABASE_ACCESS_TOKEN"))
        val request = HttpRequest.newBuilder(URI.create(api + path)).header("Authorization", "Bearer $secret")
            .header("Accept", "application/json").timeout(Duration.ofSeconds(30)).GET().build()
        val response = transport.send(request)
        val parsed = runCatching { lenientJson.parseToJsonElement(response.body()) }.getOrNull()
        if (response.statusCode() !in 200..299) throw CloudReadFailure("${response.statusCode()} ${parsed.text("message").orEmpty()}".trim())
        return parsed ?: throw CloudReadFailure("Unreadable response from $path")
    }

    private val JsonObject.ref: String get() = text("ref") ?: text("id").orEmpty()
    private val JsonObject.active: Boolean get() = text("status")?.startsWith("ACTIVE") == true

    companion object {
        val Services = listOf("db", "pooler", "auth", "rest", "realtime", "storage")

        fun status(project: String?): ResourceStatus = when (project) {
            "ACTIVE_HEALTHY" -> ResourceStatus.Healthy
            "INACTIVE", "PAUSING" -> ResourceStatus.Idle
            "ACTIVE_UNHEALTHY", "COMING_UP", "RESTORING", "UPGRADING", "RESTARTING", "RESIZING", "PROMOTING" -> ResourceStatus.Degraded
            "GOING_DOWN", "INIT_FAILED", "REMOVED", "RESTORE_FAILED", "PAUSE_FAILED" -> ResourceStatus.Down
            else -> ResourceStatus.Unknown
        }
    }
}
