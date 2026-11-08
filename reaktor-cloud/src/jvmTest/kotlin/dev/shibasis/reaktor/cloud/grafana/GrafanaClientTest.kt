package dev.shibasis.reaktor.cloud.grafana

import dev.shibasis.reaktor.cloud.api.FieldChange
import dev.shibasis.reaktor.cloud.api.GrafanaSite
import dev.shibasis.reaktor.cloud.http.CredentialName
import dev.shibasis.dependeasy.files.deleteTreeSafely
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GrafanaClientTest {
    private val live = """{"dashboard":{"id":7,"uid":"ops","title":"Operations","version":3,"panels":[]},"meta":{"version":3}}"""
    private val saved = mutableListOf<String>()

    private fun serving(check: suspend (GrafanaClient) -> Unit) = MockWebServer().use { server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.getHeader("Authorization") != "Bearer fixture-token" -> MockResponse().setResponseCode(401).setBody("""{"message":"Unauthorized"}""")
                request.path == "/api/dashboards/uid/ops" -> MockResponse().setBody(live)
                request.path == "/api/dashboards/uid/new" -> MockResponse().setResponseCode(404).setBody("""{"message":"Dashboard not found"}""")
                request.path == "/api/folders/dev" -> MockResponse().setResponseCode(404).setBody("""{"message":"folder not found"}""")
                request.path == "/api/dashboards/db" && request.method == "POST" -> {
                    saved += request.body.readUtf8()
                    MockResponse().setBody("""{"id":7,"uid":"ops","status":"success","version":4,"url":"/d/ops"}""")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        runBlocking { check(GrafanaClient(server.url("/").toString().trimEnd('/'), { "Bearer fixture-token" })) }
    }

    @Test fun plansADashboardAgainstTheLiveVersion() = serving { grafana ->
        val plan = grafana.planDashboard(JsonObject(mapOf("uid" to JsonPrimitive("ops"), "title" to JsonPrimitive("Operations 2"),
            "panels" to Json.parseToJsonElement("[]"))), "dev", "retitle")
        assertTrue(plan.exists)
        assertEquals(listOf(FieldChange("title", JsonPrimitive("Operations"), JsonPrimitive("Operations 2"))), plan.changes)
        assertEquals("3", plan.liveVersion)
        val dashboard = plan.request.body!!.jsonObject.getValue("dashboard").jsonObject
        assertEquals("3", dashboard.getValue("version").jsonPrimitive.content, "the write carries the version it was planned against")
        assertEquals("7", dashboard.getValue("id").jsonPrimitive.content)
    }

    @Test fun appliesExactlyThePlannedRequest() = serving { grafana ->
        val plan = grafana.planDashboard(JsonObject(mapOf("uid" to JsonPrimitive("ops"), "title" to JsonPrimitive("Operations 2"))), null, "retitle")
        val receipt = grafana.apply(plan, plan.digest)
        assertEquals("4", receipt.remoteVersion)
        assertEquals(plan.request.body, Json.parseToJsonElement(saved.single()))
    }

    @Test fun refusesARequestThatChangedAfterApproval() = serving { grafana ->
        val plan = grafana.planDashboard(JsonObject(mapOf("uid" to JsonPrimitive("ops"), "title" to JsonPrimitive("Operations 2"))), null, "retitle")
        val tampered = plan.copy(request = plan.request.copy(url = plan.request.url.replace("/db", "/db?overwrite=true")))
        assertFailsWith<IllegalStateException> { grafana.apply(tampered, plan.digest) }
        assertTrue(saved.isEmpty())
    }

    @Test fun plansCreationWhenNothingIsLive() = serving { grafana ->
        val dashboard = grafana.planDashboard(JsonObject(mapOf("uid" to JsonPrimitive("new"), "title" to JsonPrimitive("New"))), null, "create")
        assertFalse(dashboard.exists)
        val folder = grafana.planFolder("dev", "Development")
        assertEquals("POST", folder.request.method)
        assertFalse(folder.exists)
    }

    @Test fun resolvesItsCredentialByName() {
        val workspace = Files.createTempDirectory("grafana-site").toFile()
        try {
            File(workspace, "config").mkdirs()
            File(workspace, "config/telemetry.json").writeText("""{"GRAFANA_TOKEN":"fixture-token"}""")
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody("""{"database":"ok"}"""))
                server.start()
                val site = GrafanaSite(server.url("/").toString(), token = CredentialName("config/telemetry.json", "GRAFANA_TOKEN"))
                runBlocking { GrafanaClient.of(workspace, site).health() }
                assertEquals("Bearer fixture-token", server.takeRequest().getHeader("Authorization"))
            }
        } finally {
            workspace.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
