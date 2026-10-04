package dev.shibasis.reaktor.tooling.api

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CloudApisLiveTest {
    @Test fun theRealDescriptionsLoadAndAReadSucceedsOnEachProvider() = runBlocking {
        val workspace = System.getenv("REAKTOR_API_LIVE")?.let(::File)?.takeIf(File::isDirectory) ?: return@runBlocking
        val apis = CloudApis(workspace)
        val names = listOf("cloudflare", "supabase", "gcp:compute.v1", "gcp:pubsub.v1", "gcp:run.v2")
        names.forEach { name ->
            val surface = apis.surface(name)
            val effects = surface.operations.groupingBy { it.effect }.eachCount()
            println("$name · ${surface.title} ${surface.version} · ${surface.operations.size} operations · $effects · ids unique ${surface.operations.map { it.id }.toSet().size == surface.operations.size}")
            assertTrue(surface.operations.isNotEmpty())
        }
        println("search 'list workers': " + apis.surface("cloudflare").search("list workers", 3).map { it.key })
        listOf("cloudflare:worker-script-list-workers", "gcp:compute.v1:compute.instances.aggregatedList").forEach { key ->
            val response = apis.call(key, JsonObject(emptyMap()))
            println("$key → HTTP ${response.status} in ${response.millis} ms · ${response.text.take(160).replace('\n', ' ')}")
            assertTrue(response.ok, "$key answered ${response.status}")
        }
        if (File(workspace, dev.shibasis.reaktor.tooling.cloud.CloudflareLogin.TokenFile).isFile && System.getenv("CLOUDFLARE_API_TOKEN").isNullOrBlank()) {
            kotlin.test.assertEquals(dev.shibasis.reaktor.tooling.cloud.CloudflareLogin.TokenFile, apis.signIn("cloudflare"))
            listOf("account-api-tokens-verify-token", "zones-get", "aig-config-list-gateway", "workers-ai-search-model", "d1-list-databases",
                "cloudflare-tunnel-list-cloudflare-tunnels").forEach { id ->
                val response = apis.call("cloudflare:$id", JsonObject(emptyMap()))
                val result = (response.body as? JsonObject)?.get("result")
                val shown = when (result) {
                    is kotlinx.serialization.json.JsonArray -> "${result.size} · " + result.take(6).joinToString { (it as? JsonObject)?.let { item -> item["name"] ?: item["id"] }.toString() }
                    is JsonObject -> result.filterKeys { it in setOf("status", "expires_on", "not_before") }.toString()
                    else -> response.text.take(120)
                }
                println("cloudflare:$id with the token → HTTP ${response.status} in ${response.millis} ms · $shown")
                assertTrue(response.ok, "cloudflare:$id answered ${response.status}: ${apis.refusal("cloudflare:$id", response)}")
            }
            val account = requireNotNull(dev.shibasis.reaktor.tooling.cloud.CloudflareAccounts.of(workspace))
            val reading = dev.shibasis.reaktor.tooling.cloud.CloudflareAiCalls.reading(dev.shibasis.reaktor.tooling.cloud.CloudflareAiCalls.execute(
                dev.shibasis.reaktor.tooling.infra.InfrastructureOperation.CloudflareAiCall(workspace.path, account, "read")))
            println("cloudflare AI pane read with the token → ${reading.models.size} models · ${reading.usage.size} usage rows · " +
                "${reading.traffic.size} traffic rows · ${reading.gateways.size} gateways · failures ${reading.failures}")
            assertTrue(reading.failures.isEmpty(), "the AI pane's reads fail with the token: ${reading.failures}")
        }
        if (!System.getenv("SUPABASE_ACCESS_TOKEN").isNullOrBlank() || File(workspace, "config/supabase.token").isFile) {
            val projects = apis.call("supabase:v1-list-all-projects", JsonObject(emptyMap()))
            val visible = (projects.body as? kotlinx.serialization.json.JsonArray).orEmpty().map { it as JsonObject }
                .map { "${it["name"]}:${it["ref"] ?: it["id"]}:${it["organization_id"]}" }
            println("supabase projects → HTTP ${projects.status} in ${projects.millis} ms · ${visible.size} visible · $visible")
            assertTrue(projects.ok, "supabase:v1-list-all-projects answered ${projects.status}: ${apis.refusal("supabase:v1-list-all-projects", projects)}")
        }
    }
}
