package dev.shibasis.reaktor.tooling.cloud

import dev.shibasis.reaktor.tooling.infra.InfrastructureOperation
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CloudInventoryLiveTest {
    @Test
    fun readsTheAccountsThisWorkspaceNames() {
        val workspace = System.getenv("REAKTOR_CLOUD_LIVE_WORKSPACE") ?: return
        val operation = InfrastructureOperation.CloudInventoryRead(
            workspace = workspace,
            cloudflareAccount = System.getenv("REAKTOR_CLOUD_LIVE_CLOUDFLARE"),
            kubeconfig = File(workspace, "cloud/k3s/kubeconfig").takeIf { it.isFile }?.absolutePath,
            googleProject = System.getenv("REAKTOR_CLOUD_LIVE_GCP"),
        )
        val output = runBlocking { CloudInventoryReads.execute(operation) }
        val readings = CloudInventoryReads.decode(output)
        val snapshot = CloudInventory(emptyList(), System::currentTimeMillis).assemble(readings)
        println("output ${output.length / 1024} KiB")
        readings.forEach { println("reading ${it.provider}: ${it.health.status} ${it.health.detail} · ${it.resources.size} resources · ${it.relations.size} relations · ${it.references.size} references · ${it.changes.size} changes · ${it.durationMillis} ms") }
        println("snapshot: ${snapshot.resources.size} resources, ${snapshot.relations.size} relations, ${snapshot.changes.size} changes, ${snapshot.findings.size} findings")
        snapshot.resources.groupBy { it.kind }.toSortedMap(compareBy { it.ordinal }).forEach { (kind, list) -> println("  ${kind.label}: ${list.size}") }
        println("inferred relations: " + snapshot.relations.filter { it.inferred }.joinToString { "${snapshot[it.source]?.name}→${snapshot[it.target]?.name}(${it.kind})" })
        snapshot.findings.forEach { println("finding ${it.severity} ${it.category}: ${it.title}") }
        snapshot.resources.filter { !it.declared && !it.observed }.forEach { dangling ->
            println("dangling ${dangling.id} <- " + snapshot.incoming(dangling.id).joinToString { "${it.source}:${it.label}" })
        }
        snapshot.findings.firstOrNull { it.id == "unbound" }?.resourceIds?.forEach { println("unbound $it") }
        snapshot.resources.filter { it.kind == CloudKind.Tunnel }.forEach { tunnel ->
            println("tunnel ${tunnel.name} ${tunnel.status} in=" + snapshot.incoming(tunnel.id).joinToString { snapshot[it.source]?.name.orEmpty() } + " out=" + snapshot.outgoing(tunnel.id).joinToString { snapshot[it.target]?.name.orEmpty() })
        }
        readings.flatMap { it.references }.forEach { reference ->
            val resolved = snapshot.relations.any { it.source == reference.from && it.evidence.any { e -> e == reference.evidence } }
            if (!resolved) println("unresolved $reference")
        }
        assertTrue(snapshot.resources.isNotEmpty())
    }
}
