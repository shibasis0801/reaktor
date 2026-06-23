package dev.shibasis.reaktor.cloud

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow

/** A direct, in-process provider that reports inventory + health (Cloudflare, Supabase, GCP, …). */
interface CloudProvider {
    val id: String
    suspend fun inventory(): List<CloudResource>
    suspend fun health(): ProviderHealth
}

/** A tool provider that exposes runnable operations with streamed events (Dagger, Pulumi, …). */
interface CloudToolProvider {
    val id: String
    suspend fun operations(): List<CloudOperation>
    suspend fun run(command: CloudCommand): CloudRun
    fun events(runId: String): Flow<CloudEvent>
}

/**
 * Fans out across providers concurrently, tolerating per-provider failure (a dead provider yields
 * an empty list instead of blanking the whole pane).
 */
class CloudInventory(private val providers: List<CloudProvider>) {
    suspend fun refresh(): List<CloudResource> = coroutineScope {
        providers
            .map { provider -> async { runCatching { provider.inventory() }.getOrElse { emptyList() } } }
            .awaitAll()
            .flatten()
    }

    suspend fun health(): List<ProviderHealth> = coroutineScope {
        providers
            .map { provider ->
                async {
                    runCatching { provider.health() }
                        .getOrElse { ProviderHealth(provider.id, ResourceStatus.Unknown, it.message) }
                }
            }
            .awaitAll()
    }
}
