package dev.shibasis.reaktor.cloud.inventory

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import dev.shibasis.reaktor.core.framework.lenientJson
import dev.shibasis.reaktor.cloud.cloudflare.CloudflareLogin
import dev.shibasis.reaktor.cloud.cloudflare.CloudflareReader
import dev.shibasis.reaktor.cloud.google.GoogleCloudReader
import dev.shibasis.reaktor.cloud.kubernetes.KubernetesReader
import dev.shibasis.reaktor.cloud.supabase.SupabaseLogin
import dev.shibasis.reaktor.cloud.supabase.SupabaseReader

object CloudInventoryReads {
    suspend fun execute(workspace: String, cloudflareAccount: String?, kubeconfig: String?, googleProject: String?): String {
        val readings = read(workspace, cloudflareAccount, kubeconfig, googleProject)
        return lenientJson.encodeToString(ListSerializer(CloudReading.serializer()), readings)
    }

    fun decode(output: String): List<CloudReading> =
        lenientJson.decodeFromString(ListSerializer(CloudReading.serializer()), output)

    suspend fun read(workspace: String, cloudflareAccount: String?, kubeconfig: String?, googleProject: String?): List<CloudReading> = coroutineScope {
        val providers = providers(workspace, cloudflareAccount, kubeconfig, googleProject)
        val inventory = CloudInventory(providers, System::currentTimeMillis)
        providers.map { provider -> async { inventory.readSafely(provider, 150_000) } }.awaitAll()
    }

    private fun providers(workspace: String, cloudflareAccount: String?, kubeconfig: String?, googleProject: String?): List<CloudProvider> {
        val root = File(workspace)
        return buildList {
            cloudflareAccount?.let { account ->
                add(CloudflareReader(account, CloudflareLogin.forWorkspace(root)))
            }
            googleProject?.let { project ->
                add(GoogleCloudReader(project))
            }
            kubeconfig?.let { kubeconfig ->
                add(KubernetesReader(File(kubeconfig)))
            }
            SupabaseLogin(root).takeIf { it.token() != null }?.let { login ->
                add(SupabaseReader(login::token))
            }
        }
    }
}
