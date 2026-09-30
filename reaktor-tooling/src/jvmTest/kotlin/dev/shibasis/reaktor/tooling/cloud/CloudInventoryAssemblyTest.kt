package dev.shibasis.reaktor.tooling.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
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
    }
}
