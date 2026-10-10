package dev.shibasis.reaktor.tooling.cloud

import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CloudInventoryAssemblyTest {
    private val observed = CloudEvidence(CloudSource.CloudflareApi, "test")
    private val cluster = CloudEvidence(CloudSource.KubernetesApi, "test")
    private val google = CloudEvidence(CloudSource.GoogleCloudApi, "test")
    private val repository = CloudEvidence(CloudSource.Repository, "targets/api/wrangler.json")
    private val inferred = CloudEvidence(CloudSource.Inferred, "test")

    private fun reading(provider: String, platform: CloudPlatform?, resources: List<CloudResource>, relations: List<CloudRelation> = emptyList(), references: List<CloudReference> = emptyList()) =
        CloudReading(provider, platform, ProviderHealth(provider, ResourceStatus.Healthy), 1_000L, 10L, resources, relations, references)

    private val snapshot = CloudInventory(emptyList(), { 1_000L }).assemble(listOf(
        reading("cloudflare", CloudPlatform.Cloudflare, listOf(
            CloudResource("cf:worker:api", CloudKind.Worker, CloudPlatform.Cloudflare, "api", ResourceStatus.Healthy, evidence = listOf(observed)),
            CloudResource("cf:tunnel:t1", CloudKind.Tunnel, CloudPlatform.Cloudflare, "core", ResourceStatus.Healthy, evidence = listOf(observed)),
            CloudResource("cf:vpc:v1", CloudKind.VpcService, CloudPlatform.Cloudflare, "db-http", ResourceStatus.Healthy, evidence = listOf(observed)),
            CloudResource("host:db.example.com", CloudKind.Hostname, CloudPlatform.Cloudflare, "db.example.com", ResourceStatus.Healthy,
                attributes = mapOf("origin" to "tcp://graph:7687"), evidence = listOf(observed)),
        ), listOf(
            CloudRelation("host:db.example.com", "cf:tunnel:t1", CloudRelationKind.Tunnels, "tcp://graph:7687", evidence = listOf(observed)),
            CloudRelation("cf:worker:api", "cf:hyperdrive:gone", CloudRelationKind.Binds, "DB",
                mapOf("targetKind" to CloudKind.Hyperdrive.name, "targetName" to "gone"), listOf(observed)),
        ), listOf(
            CloudReference.ServiceHost("cf:tunnel:t1", "graph", 7687, null, CloudRelationKind.Tunnels, "db.example.com", inferred),
            CloudReference.ServiceHost("cf:tunnel:t1", "node_exporter", 9100, null, CloudRelationKind.Tunnels, "metrics.example.com", inferred),
            CloudReference.ClusterAddress("cf:vpc:v1", "10.43.0.10", CloudRelationKind.Connects, "db-http", inferred),
        )),
        reading("kubernetes", CloudPlatform.Kubernetes, listOf(
            CloudResource("k8s:data/Service/graph", CloudKind.KubernetesService, CloudPlatform.Kubernetes, "graph", ResourceStatus.Healthy,
                attributes = mapOf("namespace" to "data", "clusterIP" to "10.43.0.10"), evidence = listOf(cluster)),
            CloudResource("k8s:data/Volume/graph-pvc", CloudKind.Volume, CloudPlatform.Kubernetes, "graph-pvc", ResourceStatus.Healthy, evidence = listOf(cluster)),
            CloudResource("machine:vm", CloudKind.Machine, CloudPlatform.Kubernetes, "vm", ResourceStatus.Healthy,
                attributes = mapOf("kubernetesNode" to "true"), evidence = listOf(cluster)),
        ), references = listOf(
            CloudReference.HostPath("k8s:data/Volume/graph-pvc", "vm", "/mnt/disks/data/graph", CloudRelationKind.StoresOn, null, inferred),
        )),
        reading("gcp", CloudPlatform.GoogleCloud, listOf(
            CloudResource("machine:vm", CloudKind.Machine, CloudPlatform.GoogleCloud, "vm", ResourceStatus.Healthy, evidence = listOf(google)),
            CloudResource("gcp:disk:vm-data", CloudKind.Disk, CloudPlatform.GoogleCloud, "vm-data", ResourceStatus.Healthy,
                attributes = mapOf("deviceName" to "vm-data", "snapshots" to "0"), evidence = listOf(google)),
            CloudResource("gcp:firewall:open", CloudKind.FirewallRule, CloudPlatform.GoogleCloud, "open", ResourceStatus.Healthy,
                attributes = mapOf("direction" to "INGRESS", "sourceRanges" to "0.0.0.0/0", "allowed" to "tcp:6443,tcp:22"), evidence = listOf(google)),
        ), listOf(
            CloudRelation("gcp:disk:vm-data", "machine:vm", CloudRelationKind.Attached, "vm-data", evidence = listOf(google)),
            CloudRelation("gcp:firewall:open", "machine:vm", CloudRelationKind.Exposes, evidence = listOf(google)),
        )),
        reading("repository", null, listOf(
            CloudResource("cf:worker:api", CloudKind.Worker, CloudPlatform.Cloudflare, "api", evidence = listOf(repository)),
            CloudResource("cf:worker:mail", CloudKind.Worker, CloudPlatform.Cloudflare, "mail", evidence = listOf(repository)),
        )),
    ))

    @Test
    fun oneMachineSeenByTwoPlatformsIsOneResource() {
        val machine = snapshot["machine:vm"]
        assertNotNull(machine)
        assertEquals(CloudPlatform.GoogleCloud, machine.platform)
        assertEquals("true", machine.attributes["kubernetesNode"])
        assertEquals(1, snapshot.resources.count { it.kind == CloudKind.Machine })
    }

    @Test
    fun crossPlatformReferencesBecomeRelations() {
        assertTrue(snapshot.relations.any { it.source == "cf:tunnel:t1" && it.target == "k8s:data/Service/graph" }, "a tunnel route reaches the cluster service by name")
        assertTrue(snapshot.relations.any { it.source == "cf:vpc:v1" && it.target == "k8s:data/Service/graph" }, "a VPC service reaches the service by cluster IP")
        assertTrue(snapshot.relations.any { it.source == "k8s:data/Volume/graph-pvc" && it.target == "gcp:disk:vm-data" }, "a volume path lands on the disk whose device name it contains")
        assertEquals(listOf("node_exporter"), snapshot.unresolved.filterIsInstance<CloudReference.ServiceHost>().map { it.host })
    }

    @Test
    fun findingsNameTheirEvidence() {
        val byId = snapshot.findings.associateBy { it.id }
        assertEquals(CloudSeverity.High, byId.getValue("open-port-6443").severity)
        assertTrue("gcp:firewall:open" in byId.getValue("open-port-6443").resourceIds)
        assertEquals(CloudSeverity.Medium, byId.getValue("open-port-22").severity)
        assertEquals(CloudSeverity.High, byId.getValue("exposed-host-db.example.com").severity)
        assertEquals(CloudSeverity.High, byId.getValue("no-snapshots").severity)
        assertEquals(CloudSeverity.High, byId.getValue("dangling").severity)
        assertTrue("cf:hyperdrive:gone" in byId.getValue("dangling").resourceIds)
        assertTrue("cf:worker:mail" in byId.getValue("not-deployed").resourceIds)
        assertTrue(byId.keys.any { it.startsWith("broken-routes-") })
        assertEquals("Database disks have no disk snapshots", byId.getValue("no-snapshots").title)
        assertTrue("Application-level backup and restore coverage needs separate verification" in byId.getValue("no-snapshots").detail)
    }

    @Test
    fun unreadableSnapshotsRemainUnknownAndDoNotCreateBackupFindings() {
        val disk = cloudJson.parseToJsonElement("""{"name":"data","status":"READY","zone":"zones/test-a","sizeGb":"20"}""").jsonObject
        val taken = cloudJson.parseToJsonElement("""{"sourceDisk":"disks/data","creationTimestamp":"2026-10-09T00:00:00Z"}""").jsonObject
        val results = listOf(null, emptyList(), listOf(taken)).map { snapshots ->
            val builder = GoogleCloudInventoryBuilder("test", "https://console.cloud.google.com", 1_000L)
            builder.read(emptyList(), listOf(disk), emptyList(), emptyList(), snapshots, emptyList(), emptyList())
            builder.resources().single()
        }
        assertEquals(listOf(null, "0", "1"), results.map { it.attributes["snapshots"] })
        assertTrue(results.first().evidence.any { "snapshot inventory unreadable" in it.detail })
        assertEquals(listOf(false, true, false), results.map { resource ->
            CloudAudit.findings(CloudSnapshot.Empty.copy(resources = listOf(resource))).any { it.id == "no-snapshots" }
        })
        val scheduled = results[1].copy(attributes = results[1].attributes + ("snapshotSchedule" to "daily"))
        assertTrue(CloudAudit.findings(CloudSnapshot.Empty.copy(resources = listOf(scheduled))).none { it.id == "no-snapshots" })
    }

    @Test
    fun partialWorkerTrafficDoesNotInventCountsOrHealth() {
        val scripts = listOf("missing", "zero", "requests-only", "errors-only", "failing").map { name ->
            cloudJson.parseToJsonElement("""{"id":"$name"}""").jsonObject
        }
        val builder = CloudflareInventoryBuilder("test", "https://dash.cloudflare.com/test", "test", 1_000L)
        builder.workers(scripts, emptyMap(), mapOf(
            "zero" to WorkerTraffic(0.0, 0.0, 0.0, null, null, emptyList()),
            "requests-only" to WorkerTraffic(100.0, null, null, null, null, emptyList()),
            "errors-only" to WorkerTraffic(null, 5.0, null, null, null, emptyList()),
            "failing" to WorkerTraffic(100.0, 10.0, 0.0, null, null, emptyList()),
        ))
        val workers = builder.resources().associateBy { it.name }
        assertEquals(ResourceStatus.Unknown, workers.getValue("missing").status)
        assertEquals("traffic not read", workers.getValue("missing").statusDetail)
        assertTrue(workers.getValue("missing").metrics.isEmpty())
        assertEquals(ResourceStatus.Idle, workers.getValue("zero").status)
        assertEquals(0.0, workers.getValue("zero").metric("requests")?.value)
        val requests = workers.getValue("requests-only")
        assertEquals(ResourceStatus.Unknown, requests.status)
        assertEquals(100.0, requests.metric("requests")?.value)
        assertEquals(null, requests.metric("errors"))
        assertEquals(null, requests.metric("subrequests"))
        assertTrue("errors not read" in requests.statusDetail.orEmpty())
        val errors = workers.getValue("errors-only")
        assertEquals(ResourceStatus.Unknown, errors.status)
        assertEquals(null, errors.metric("requests"))
        assertEquals(5.0, errors.metric("errors")?.value)
        assertEquals(ResourceStatus.Degraded, workers.getValue("failing").status)
    }

    @Test
    fun inventoryCancellationIsDistinctFromItsReadDeadline() = runTest {
        val provider = object : CloudProvider {
            override val id = "slow"
            override suspend fun read(): CloudReading { delay(10_000); return reading(id, null, emptyList()) }
        }
        val inventory = CloudInventory(listOf(provider), { testScheduler.currentTime })
        val timedOut = inventory.readSafely(provider, 1)
        assertEquals(ResourceStatus.Down, timedOut.health.status)
        assertTrue(timedOut.health.detail.orEmpty().startsWith("No answer within"))
        assertFailsWith<TimeoutCancellationException> { withTimeout(1) { inventory.readSafely(provider, 10_000) } }
        val cancelled = object : CloudProvider {
            override val id = "cancelled"
            override suspend fun read(): CloudReading = throw CancellationException("fixture cancellation")
        }
        assertFailsWith<CancellationException> { inventory.readSafely(cancelled, 10_000) }
    }

    @Test
    fun cloudflareTransportPreservesUnknownAndPartialCoverage() = runBlocking {
        val failAnalytics = AtomicBoolean(true)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            val denied = path.endsWith("/workers/routes") || path.endsWith("/configurations")
            val body = when {
                denied -> """{"errors":[{"message":"fixture read denied"}]}"""
                path == "/graphql" && failAnalytics.get() -> """{"data":null,"errors":[{"message":"fixture analytics denied"}]}"""
                path == "/graphql" -> """{"data":{"viewer":{"accounts":[{"totals":[
                    {"dimensions":{"scriptName":"zero"},"sum":{"requests":0,"errors":0,"subrequests":0}},
                    {"dimensions":{"scriptName":"partial"},"sum":{"errors":5}},
                    {"dimensions":{"scriptName":"invalid"},"sum":{"requests":-1,"errors":0,"subrequests":-2}}
                ],"hourly":[{"dimensions":{"scriptName":"partial","datetimeHour":"2026-10-09T00:00:00Z"},"sum":{"requests":null,"errors":5}}]}]}}}"""
                path.endsWith("/workers/scripts") -> """{"result":[{"id":"zero"},{"id":"partial"},{"id":"invalid"}]}"""
                path == "/zones" -> """{"result":[{"id":"zone-test","name":"example.test","status":"active"}]}"""
                path.endsWith("/cfd_tunnel") -> """{"result":[{"id":"tunnel-test","name":"fixture","status":"healthy"}]}"""
                path.endsWith("/r2/buckets") -> """{"result":{"buckets":[]}}"""
                path.endsWith("/settings") -> """{"result":{}}"""
                path.endsWith("/deployments") -> """{"result":{"deployments":[]}}"""
                else -> """{"result":[]}"""
            }.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (denied) 403 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val reader = CloudflareReader("test", CloudflareApiToken("fixture-token"), { 1_791_504_000_000L })
            CloudflareReader::class.java.getDeclaredField("base").apply { isAccessible = true }
                .set(reader, "http://127.0.0.1:${server.address.port}")
            val unreadable = withTimeout(10_000) { reader.read() }
            assertEquals(ResourceStatus.Degraded, unreadable.health.status)
            assertTrue(listOf("analytics", "worker routes", "tunnel ingress").all { it in unreadable.health.detail.orEmpty() })
            val unseen = unreadable.resources.filter { it.kind == CloudKind.Worker }
            assertEquals(3, unseen.size)
            assertTrue(unseen.all { it.status == ResourceStatus.Unknown && it.metrics.isEmpty() })
            failAnalytics.set(false)
            val partial = withTimeout(10_000) { reader.read() }
            assertEquals(ResourceStatus.Degraded, partial.health.status)
            assertTrue("analytics" !in partial.health.detail.orEmpty())
            val workers = partial.resources.filter { it.kind == CloudKind.Worker }.associateBy { it.name }
            assertEquals(ResourceStatus.Idle, workers.getValue("zero").status)
            assertEquals(0.0, workers.getValue("zero").metric("requests")?.value)
            assertEquals(ResourceStatus.Unknown, workers.getValue("partial").status)
            assertEquals(null, workers.getValue("partial").metric("requests"))
            assertEquals(5.0, workers.getValue("partial").metric("errors")?.value)
            assertEquals(null, workers.getValue("partial").attributes["hourlyRequests"])
            assertEquals(ResourceStatus.Unknown, workers.getValue("invalid").status)
            assertEquals(null, workers.getValue("invalid").metric("requests"))
            assertEquals(null, workers.getValue("invalid").metric("subrequests"))
        } finally {
            server.stop(0)
        }
    }
}
