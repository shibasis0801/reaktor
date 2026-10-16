package dev.shibasis.reaktor.tooling.cloud

import dev.shibasis.reaktor.tooling.infra.InfrastructureOperation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

object CloudInventoryReads {
    suspend fun execute(operation: InfrastructureOperation.CloudInventoryRead): String {
        val readings = read(operation)
        return cloudJson.encodeToString(ListSerializer(CloudReading.serializer()), readings)
    }

    fun decode(output: String): List<CloudReading> =
        cloudJson.decodeFromString(ListSerializer(CloudReading.serializer()), output)

    suspend fun read(operation: InfrastructureOperation.CloudInventoryRead): List<CloudReading> = coroutineScope {
        val providers = providers(operation)
        val inventory = CloudInventory(providers, System::currentTimeMillis)
        providers.map { provider -> async { inventory.readSafely(provider, 150_000) } }.awaitAll()
    }

    private fun providers(operation: InfrastructureOperation.CloudInventoryRead): List<CloudProvider> {
        val workspace = File(operation.workspace)
        return buildList {
            operation.cloudflareAccount?.let { account ->
                add(CloudflareReader(account, CloudflareLogin.forWorkspace(workspace)))
            }
            operation.googleProject?.let { project ->
                add(GoogleCloudReader(project))
            }
            operation.kubeconfig?.let { kubeconfig ->
                add(KubernetesReader(File(kubeconfig)))
            }
        }
    }
}
