package dev.shibasis.reaktor.cloud.grafana

import dev.shibasis.reaktor.cloud.api.GrafanaSite
import dev.shibasis.reaktor.cloud.api.PlannedRequest
import dev.shibasis.reaktor.cloud.api.WritePlan
import dev.shibasis.reaktor.cloud.api.WritePlans
import dev.shibasis.reaktor.cloud.api.WriteReceipt
import dev.shibasis.reaktor.cloud.http.*
import dev.shibasis.reaktor.core.framework.lenientJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.time.Duration
import java.util.Base64

class GrafanaFailure(val status: Int, message: String) : Exception(message)

class GrafanaClient(
    private val site: String,
    private val authorization: () -> String,
    private val wake: (suspend () -> Unit)? = null,
    private val transport: Transport = Transport(),
) {
    private var awake = wake == null

    suspend fun health(): JsonObject = get("/api/health") as JsonObject
    suspend fun dashboards(): List<JsonObject> = get("/api/search?type=dash-db&limit=5000").list()
    suspend fun dashboard(uid: String): JsonObject? = find("/api/dashboards/uid/${encode(uid)}")
    suspend fun folders(): List<JsonObject> = get("/api/folders?limit=1000").list()
    suspend fun dataSources(): List<JsonObject> = get("/api/datasources").list()
    suspend fun queryDataSources(body: JsonObject): JsonObject =
        send("POST", "$site/api/ds/query", body).second as JsonObject
    suspend fun alertRules(): List<JsonObject> = get("/api/v1/provisioning/alert-rules").list()
    suspend fun alertRule(uid: String): JsonObject? = find("/api/v1/provisioning/alert-rules/${encode(uid)}")

    suspend fun planDashboard(dashboard: JsonObject, folderUid: String?, message: String): WritePlan {
        val uid = requireNotNull(dashboard.text("uid")) { "A dashboard needs a uid to be planned" }
        val live = dashboard(uid)
        val current = live.obj("dashboard")
        val body = JsonObject(mapOf(
            "dashboard" to JsonObject(dashboard + mapOf("id" to (current["id"] ?: JsonNull), "version" to (current["version"] ?: JsonNull))),
            "folderUid" to (folderUid?.let(::JsonPrimitive) ?: JsonNull),
            "message" to JsonPrimitive(message),
            "overwrite" to JsonPrimitive(false),
        ))
        return WritePlans.plan("grafana:dashboard:$uid", PlannedRequest("POST", "$site/api/dashboards/db", body),
            live?.let { current }, dashboard, DashboardBookkeeping, current.text("version"))
    }

    suspend fun planFolder(uid: String, title: String): WritePlan {
        val live = find("/api/folders/${encode(uid)}")
        val request = if (live == null) PlannedRequest("POST", "$site/api/folders", JsonObject(mapOf("uid" to JsonPrimitive(uid), "title" to JsonPrimitive(title))))
        else PlannedRequest("PUT", "$site/api/folders/${encode(uid)}", JsonObject(mapOf("title" to JsonPrimitive(title), "version" to (live["version"] ?: JsonNull))))
        return WritePlans.plan("grafana:folder:$uid", request, live?.let { JsonObject(mapOf("title" to (it["title"] ?: JsonNull))) },
            JsonObject(mapOf("title" to JsonPrimitive(title))), liveVersion = live.text("version"))
    }

    suspend fun planAlertRule(rule: JsonObject): WritePlan {
        val uid = requireNotNull(rule.text("uid")) { "An alert rule needs a uid to be planned" }
        val live = alertRule(uid)
        val request = if (live == null) PlannedRequest("POST", "$site/api/v1/provisioning/alert-rules", rule)
        else PlannedRequest("PUT", "$site/api/v1/provisioning/alert-rules/${encode(uid)}", rule)
        return WritePlans.plan("grafana:alert-rule:$uid", request, live, rule, RuleBookkeeping, live.text("updated"))
    }

    suspend fun apply(plan: WritePlan, approved: String): WriteReceipt {
        WritePlans.confirm(plan, approved)
        val answer = send(plan.request.method, plan.request.url, plan.request.body)
        return WriteReceipt(plan.target, approved, answer.first, answer.second.text("version") ?: answer.second.text("updated") ?: answer.second.text("uid"))
    }

    private suspend fun find(path: String): JsonObject? = try { get(path) as? JsonObject } catch (missing: GrafanaFailure) {
        if (missing.status == 404) null else throw missing
    }

    private suspend fun get(path: String): JsonElement = send("GET", site + path, null).second

    private suspend fun send(method: String, url: String, body: JsonElement?): Pair<Int, JsonElement> {
        if (!awake) wake?.invoke().also { awake = true }
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(150))
            .header("Authorization", authorization()).header("Accept", "application/json").header("X-Disable-Provenance", "true")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) } ?: HttpRequest.BodyPublishers.noBody())
        if (body != null) request.header("Content-Type", "application/json")
        val response = transport.send(request.build())
        val parsed = runCatching { lenientJson.parseToJsonElement(response.body()) }.getOrNull() ?: JsonNull
        if (response.statusCode() !in 200..299) throw GrafanaFailure(response.statusCode(),
            "Grafana answered HTTP ${response.statusCode()} for $method ${url.removePrefix(site)}: ${parsed.text("message").orEmpty()}".trim())
        return response.statusCode() to parsed
    }

    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    companion object {
        val DashboardBookkeeping = setOf("id", "version", "iteration")
        val RuleBookkeeping = setOf("id", "updated", "provenance")

        fun of(workspace: File, site: GrafanaSite): GrafanaClient = GrafanaClient(site.url.trimEnd('/'), authorization(workspace, site), waker(workspace, site))

        fun authorization(workspace: File, site: GrafanaSite): () -> String {
            val credentials = Credentials(workspace)
            return {
                site.token?.let { "Bearer ${credentials.resolve(it)}" }
                    ?: site.password?.let { "Basic " + Base64.getEncoder().encodeToString("${site.user}:${credentials.resolve(it)}".toByteArray()) }
                    ?: throw MissingCredential(null, setOf("grafana.token", "grafana.password"))
            }
        }

        fun waker(workspace: File, site: GrafanaSite): (suspend () -> Unit)? = site.wake?.let { wake ->
            suspend {
                val request = HttpRequest.newBuilder(URI.create(site.url.trimEnd('/') + wake.path)).timeout(Duration.ofSeconds(150))
                    .header("Authorization", "Bearer ${Credentials(workspace).resolve(wake.token)}").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build()
                val status = Transport().send(request).statusCode()
                if (status !in 200..299) throw GrafanaFailure(status, "Grafana did not wake: HTTP $status from ${wake.path}")
            }
        }
    }
}
