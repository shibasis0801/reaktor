package dev.shibasis.reaktor.tooling.cloud

import dev.shibasis.reaktor.tooling.infra.InfrastructureOperation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

object CloudInventoryReads {
    private val searchPath = listOf(
        "/opt/homebrew/bin", "/usr/local/bin", "/usr/bin", "/bin",
        "/opt/homebrew/share/google-cloud-sdk/bin", "${System.getProperty("user.home")}/google-cloud-sdk/bin",
    )

    fun tool(name: String): String? =
        (System.getenv("PATH").orEmpty().split(File.pathSeparator) + searchPath)
            .map { File(it, name) }
            .firstOrNull { it.isFile && it.canExecute() }?.absolutePath

    internal fun gcloudEnvironment(): Map<String, String> = System.getenv("CLOUDSDK_PYTHON")?.takeIf { it.isNotBlank() }
        ?.let { mapOf("CLOUDSDK_PYTHON" to it) }
        ?: listOf("python3.13", "python3.12", "python3.11", "python3.10").firstNotNullOfOrNull(::tool)
            ?.let { mapOf("CLOUDSDK_PYTHON" to it) }.orEmpty()

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
                val gcloud = tool("gcloud") ?: "gcloud"
                add(GoogleCloudReader(project, gcloud, accounts = { signedInAccounts(gcloud) }))
            }
            operation.kubeconfig?.let { kubeconfig ->
                add(KubernetesReader(File(kubeconfig), tool("kubectl") ?: "kubectl"))
            }
        }
    }

    internal suspend fun refreshWrangler(workspace: File) {
        val local = File(workspace, "node_modules/.bin/wrangler").takeIf { it.canExecute() }?.absolutePath
        val command = local?.let { listOf(it, "whoami") } ?: listOf(tool("npx") ?: "npx", "--no-install", "wrangler", "whoami")
        val node = tool("node")?.let { File(it).parent }
        val path = listOfNotNull(node, System.getenv("PATH")).joinToString(File.pathSeparator)
        runCatching { CommandLine.run(command, 60, mapOf("PATH" to path), workspace) }
    }

    private suspend fun signedInAccounts(gcloud: String): List<String> = runCatching {
        CommandLine.run(listOf(gcloud, "auth", "list", "--format=value(account)"), 60, gcloudEnvironment())
            .lines().map { it.trim() }.filter { "@" in it }
    }.getOrDefault(emptyList())
}
