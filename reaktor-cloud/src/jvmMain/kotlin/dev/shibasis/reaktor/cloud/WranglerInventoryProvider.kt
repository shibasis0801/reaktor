package dev.shibasis.reaktor.cloud

import dev.shibasis.reaktor.tooling.cloud.*
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

    override suspend fun read(): CloudReading = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val root = readConfig(repoRoot.resolve("wrangler.json"))
        val account = root?.account_id
        fun dash(path: String) = account?.let { "https://dash.cloudflare.com/$it/$path" }
        fun evidence(path: Path) = listOf(CloudEvidence(CloudSource.Repository, repoRoot.relativize(path).toString()))
        val resources = buildList {
            root?.r2_buckets?.forEach {
                add(CloudResource("cf:r2:${it.bucket_name}", CloudKind.R2, CloudPlatform.Cloudflare, it.bucket_name,
                    attributes = mapOf("binding" to it.binding), evidence = evidence(repoRoot.resolve("wrangler.json")),
                    consoleUrl = dash("r2/default/buckets/${it.bucket_name}")))
            }
            root?.hyperdrive?.forEach {
                add(CloudResource("cf:hyperdrive:${it.id}", CloudKind.Hyperdrive, CloudPlatform.Cloudflare, it.binding,
                    attributes = mapOf("id" to it.id), evidence = evidence(repoRoot.resolve("wrangler.json")),
                    consoleUrl = dash("workers/hyperdrive")))
            }
            val targets = repoRoot.resolve("targets")
            if (Files.isDirectory(targets)) {
                Files.list(targets).use { stream ->
                    stream.sorted().forEach { dir ->
                        val config = dir.resolve("wrangler.json")
                        val cfg = readConfig(config) ?: return@forEach
                        if (cfg.name == null && cfg.main == null) return@forEach
                        val name = cfg.name ?: dir.fileName.toString()
                        add(CloudResource("cf:worker:$name", CloudKind.Worker, CloudPlatform.Cloudflare, name,
                            attributes = mapOf("workspace" to dir.fileName.toString()), evidence = evidence(config),
                            consoleUrl = dash("workers/services/view/$name/production")))
                    }
                }
            }
        }
        val finished = System.currentTimeMillis()
        CloudReading(
            provider = id,
            platform = CloudPlatform.Cloudflare,
            health = ProviderHealth(id, ResourceStatus.Unknown, "static inventory from wrangler.json (no live API)"),
            readAtMillis = finished,
            durationMillis = finished - started,
            resources = resources,
            source = repoRoot.toString(),
        )
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
