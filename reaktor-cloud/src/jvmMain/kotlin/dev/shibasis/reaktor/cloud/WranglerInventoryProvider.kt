package dev.shibasis.reaktor.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * A [CloudProvider] that builds a Cloudflare inventory from the repo's actual `wrangler.json`
 * config — no live API calls, no credentials. The root `wrangler.json` holds the shared bindings
 * (R2, Hyperdrive, account); each `targets/<x>/wrangler.json` is a Worker.
 *
 * This is the first "real data from the codebase" provider behind the reaktorDesktop Cloud pane.
 */
class WranglerInventoryProvider(private val repoRoot: Path) : CloudProvider {
    override val id = "cloudflare"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun inventory(): List<CloudResource> = withContext(Dispatchers.IO) {
        buildList {
            val root = readConfig(repoRoot.resolve("wrangler.json"))
            val account = root?.account_id
            fun dash(path: String) = account?.let { "https://dash.cloudflare.com/$it/$path" }

            // Shared bindings live in the root config (per AGENTS.md §4.3).
            root?.r2_buckets?.forEach {
                add(CloudResource("cloudflare", "R2", it.bucket_name, it.bucket_name, ResourceStatus.Unknown,
                    metrics = mapOf("binding" to it.binding), consoleUrl = dash("r2/default/buckets/${it.bucket_name}")))
            }
            root?.hyperdrive?.forEach {
                add(CloudResource("cloudflare", "Hyperdrive", it.id, it.binding, ResourceStatus.Unknown,
                    metrics = mapOf("id" to it.id), consoleUrl = dash("workers/hyperdrive")))
            }

            // One Worker per targets/<x>/wrangler.json that declares a name or entrypoint.
            val targets = repoRoot.resolve("targets")
            if (Files.isDirectory(targets)) {
                Files.list(targets).use { stream ->
                    stream.sorted().forEach { dir ->
                        val cfg = readConfig(dir.resolve("wrangler.json")) ?: return@forEach
                        if (cfg.name == null && cfg.main == null) return@forEach // shared/base, not a worker
                        val name = cfg.name ?: dir.fileName.toString()
                        add(CloudResource("cloudflare", "Worker", name, name, ResourceStatus.Unknown,
                            metrics = mapOf("dir" to dir.fileName.toString()), consoleUrl = dash("workers/services/view/$name/production")))
                    }
                }
            }
        }
    }

    override suspend fun health(): ProviderHealth =
        ProviderHealth(id, ResourceStatus.Unknown, "static inventory from wrangler.json (no live API)")

    private fun readConfig(path: Path): WranglerJson? =
        if (Files.isRegularFile(path)) runCatching { json.decodeFromString<WranglerJson>(Files.readString(path)) }.getOrNull() else null

    companion object {
        /** Walk up from [start] (default: process working dir) to the nearest `wrangler.json`. */
        fun locate(start: Path = Paths.get(System.getProperty("user.dir"))): WranglerInventoryProvider? {
            var dir: Path? = start.toAbsolutePath()
            while (dir != null) {
                if (Files.isRegularFile(dir.resolve("wrangler.json"))) return WranglerInventoryProvider(dir)
                dir = dir.parent
            }
            return null
        }
    }
}

@Serializable
private data class WranglerJson(
    val name: String? = null,
    val main: String? = null,
    val account_id: String? = null,
    val r2_buckets: List<WranglerR2> = emptyList(),
    val hyperdrive: List<WranglerHyperdrive> = emptyList(),
)

@Serializable private data class WranglerR2(val binding: String, val bucket_name: String)
@Serializable private data class WranglerHyperdrive(val binding: String, val id: String)
