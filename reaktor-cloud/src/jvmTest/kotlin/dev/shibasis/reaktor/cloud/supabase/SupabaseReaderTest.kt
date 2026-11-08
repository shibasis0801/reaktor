package dev.shibasis.reaktor.cloud.supabase

import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.reaktor.cloud.http.MissingCredential
import dev.shibasis.reaktor.cloud.inventory.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SupabaseReaderTest {
    private val production = "aaaaaaaaaaaaaaaaaaaa"
    private val projects = """[
        {"id":"$production","ref":"$production","organization_id":"o","organization_slug":"acme","name":"production","region":"ap-south-1",
         "created_at":"2025-01-02T03:04:05Z","status":"ACTIVE_HEALTHY","database":{"host":"db.$production.supabase.co","version":"15.8.1","postgres_engine":"15","release_channel":"ga"}},
        {"id":"bbbbbbbbbbbbbbbbbbbb","ref":"bbbbbbbbbbbbbbbbbbbb","organization_id":"o","organization_slug":"acme","name":"staging","region":"ap-south-1",
         "created_at":"2025-01-02T03:04:05Z","status":"INACTIVE","database":{"host":"db.bbbbbbbbbbbbbbbbbbbb.supabase.co","version":"15.8.1","postgres_engine":"15","release_channel":"ga"}}
    ]"""
    private val healthy = """[{"name":"db","healthy":true,"status":"ACTIVE_HEALTHY"},{"name":"auth","healthy":true,"status":"ACTIVE_HEALTHY"}]"""

    private fun serving(health: MockResponse, check: suspend (MockWebServer) -> Unit) = MockWebServer().use { server ->
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.getHeader("Authorization") != "Bearer fixture-token" -> MockResponse().setResponseCode(401)
                request.path == "/v1/projects" -> MockResponse().setBody(projects)
                request.path.orEmpty().startsWith("/v1/projects/$production/health?services=") -> health
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        runBlocking { check(server) }
    }

    private fun reader(server: MockWebServer, token: String? = "fixture-token") = SupabaseReader({ token }, server.url("/").toString().trimEnd('/'))

    @Test fun readsEachProjectAsItsPostgres() = serving(MockResponse().setBody(healthy)) { server ->
        val reading = reader(server).read()
        assertEquals(ResourceStatus.Healthy, reading.health.status)
        val byId = reading.resources.associateBy { it.id }
        assertEquals("production", byId.getValue("pg:$production").name)
        assertEquals(ResourceStatus.Healthy, byId.getValue("pg:$production").status)
        assertEquals("ap-south-1", byId.getValue("pg:$production").region)
        assertEquals(ResourceStatus.Idle, byId.getValue("pg:bbbbbbbbbbbbbbbbbbbb").status)
        assertEquals(2, server.requestCount, "a paused project is not asked for its health")
    }

    @Test fun anUnhealthyServiceDegradesItsProject() = serving(MockResponse().setBody(
        """[{"name":"db","healthy":true,"status":"ACTIVE_HEALTHY"},{"name":"realtime","healthy":false,"status":"UNHEALTHY","error":"down"}]""")) { server ->
        val project = reader(server).read().resources.single { it.name == "production" }
        assertEquals(ResourceStatus.Degraded, project.status)
        assertTrue("unhealthy: realtime" in project.statusDetail.orEmpty(), project.statusDetail)
    }

    @Test fun anUnreadableHealthDegradesTheReadingOnly() = serving(MockResponse().setResponseCode(403).setBody("""{"message":"forbidden"}""")) { server ->
        val reading = reader(server).read()
        assertEquals(ResourceStatus.Degraded, reading.health.status)
        assertEquals(ResourceStatus.Healthy, reading.resources.single { it.name == "production" }.status)
        assertNull(reading.resources.single { it.name == "production" }.attributes["services"])
    }

    @Test fun replacesWhatHyperdriveSawOfTheSameDatabase() = serving(MockResponse().setBody(healthy)) { server ->
        val seen = CloudResource("pg:$production", CloudKind.Postgres, CloudPlatform.Supabase, "Supabase $production",
            statusDetail = "seen through Hyperdrive; the database itself is not read here", attributes = mapOf("database" to "postgres"),
            evidence = listOf(CloudEvidence(CloudSource.CloudflareApi, "origin of Hyperdrive main")))
        val cloudflare = CloudReading("cloudflare", CloudPlatform.Cloudflare, ProviderHealth("cloudflare", ResourceStatus.Healthy), 0, 0, resources = listOf(seen))
        val merged = CloudInventory(emptyList(), { 0L }).assemble(listOf(cloudflare, reader(server).read()))["pg:$production"]!!
        assertEquals("production", merged.name)
        assertEquals(ResourceStatus.Healthy, merged.status)
        assertEquals("postgres", merged.attributes["database"])
    }

    @Test fun needsAToken() = serving(MockResponse().setBody(healthy)) { server ->
        assertFailsWith<MissingCredential> { reader(server, token = null).read() }
        assertEquals(0, server.requestCount)
    }

    @Test fun readsTheTokenFromTheWorkspace() {
        val workspace = Files.createTempDirectory("supabase-login").toFile()
        try {
            File(workspace, "config").mkdirs()
            File(workspace, "config/supabase.token").writeText("# personal access token\n\nfixture-token\n")
            assertEquals("fixture-token", SupabaseLogin(workspace) { null }.token())
            assertEquals("from-environment", SupabaseLogin(workspace) { "from-environment" }.token())
            assertNull(SupabaseLogin(File(workspace, "missing")) { null }.token())
        } finally {
            workspace.deleteTreeSafely(within = File(System.getProperty("java.io.tmpdir")))
        }
    }
}
