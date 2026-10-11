package dev.shibasis.reaktor.tooling.cloud

import dev.shibasis.reaktor.tooling.api.CloudApis
import dev.shibasis.reaktor.tooling.infra.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.*

class HangarProvidersLiveTest {
    @Test fun existingSignInsReadCloudflareGoogleCloudAndPubSub() = runBlocking {
        val root = System.getenv("REAKTOR_HANGAR_LIVE")?.let(::File)?.takeIf { it.isDirectory } ?: return@runBlocking
        val apis = CloudApis(root)
        for (key in listOf("cloudflare:worker-script-list-workers", "gcp:compute.v1:compute.instances.aggregatedList")) {
            val response = apis.call(key, JsonObject(emptyMap()))
            println("$key: HTTP ${response.status}")
            assertTrue(response.ok, "$key: HTTP ${response.status}; ${apis.refusal(key, response)}")
        }
        InfrastructureSession().use { session ->
            val project = requireNotNull(apis.googleProject())
            val receipt = PubSubJvmClient(session).read(DatabaseConnection.GoogleProject(project, File(root, "config/absent-live-test-credential.json").path),
                PubSubQuery(action = PubSubReadAction.Topics), 1)
            assertEquals("PubSub", receipt.provider)
            println("Pub/Sub: authenticated catalog read succeeded")
        }
    }
}
