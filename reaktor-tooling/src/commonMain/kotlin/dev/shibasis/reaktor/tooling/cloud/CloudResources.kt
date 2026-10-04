package dev.shibasis.reaktor.tooling.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
enum class ResourceStatus(val rank: Int) {
    Healthy(0),
    Idle(1),
    Unknown(2),
    Degraded(3),
    Down(4),
}

interface CloudProvider {
    val id: String
    suspend fun read(): CloudReading
    suspend fun health(): ProviderHealth = read().health
}

@Serializable
data class ProviderHealth(
    val provider: String,
    val status: ResourceStatus,
    val detail: String? = null,
)

@Serializable
enum class CloudPlatform(val label: String) {
    Cloudflare("Cloudflare"),
    GoogleCloud("Google Cloud"),
    Kubernetes("Kubernetes"),
    Supabase("Supabase"),
    Firebase("Firebase"),
    Grafana("Grafana Cloud"),
}

@Serializable
enum class CloudPlane(val label: String) {
    Internet("Internet"),
    Edge("Edge"),
    Network("Network"),
    Cluster("Cluster"),
    Machine("Machines"),
    Data("Data"),
    Services("Services"),
}

@Serializable
enum class CloudKind(val label: String, val plane: CloudPlane) {
    Zone("Zone", CloudPlane.Internet),
    Hostname("Hostname", CloudPlane.Internet),
    Worker("Worker", CloudPlane.Edge),
    DurableObject("Durable Object", CloudPlane.Edge),
    Workflow("Workflow", CloudPlane.Edge),
    Queue("Queue", CloudPlane.Edge),
    StaticAssets("Static assets", CloudPlane.Edge),
    D1("D1 database", CloudPlane.Data),
    R2("R2 bucket", CloudPlane.Data),
    Kv("KV namespace", CloudPlane.Data),
    Postgres("Postgres", CloudPlane.Data),
    Hyperdrive("Hyperdrive", CloudPlane.Network),
    VpcService("VPC service", CloudPlane.Network),
    Tunnel("Tunnel", CloudPlane.Network),
    KubernetesService("Service", CloudPlane.Cluster),
    Deployment("Deployment", CloudPlane.Cluster),
    StatefulSet("StatefulSet", CloudPlane.Cluster),
    DaemonSet("DaemonSet", CloudPlane.Cluster),
    CronJob("CronJob", CloudPlane.Cluster),
    Pod("Pod", CloudPlane.Cluster),
    Volume("Volume", CloudPlane.Cluster),
    Machine("VM", CloudPlane.Machine),
    Disk("Disk", CloudPlane.Machine),
    FirewallRule("Firewall rule", CloudPlane.Machine),
    Address("IP address", CloudPlane.Machine),
    WorkersAi("Workers AI", CloudPlane.Services),
    BrowserRendering("Browser Rendering", CloudPlane.Services),
    ImageRepository("Image repository", CloudPlane.Services),
    Messaging("Messaging", CloudPlane.Services),
    Observability("Observability", CloudPlane.Services),
}

val CloudKind.workload: Boolean
    get() = this == CloudKind.Deployment || this == CloudKind.StatefulSet ||
        this == CloudKind.DaemonSet || this == CloudKind.CronJob

@Serializable
enum class CloudSource(val label: String, val observed: Boolean) {
    Repository("repository", false),
    CloudflareApi("Cloudflare API", true),
    GoogleCloudApi("gcloud", true),
    KubernetesApi("kubectl", true),
    Inferred("inferred", false),
}

@Serializable
data class CloudEvidence(
    val source: CloudSource,
    val detail: String,
    val atMillis: Long? = null,
)

@Serializable
enum class CloudUnit { Count, Percent, Bytes, Millis, Micros, Millicores }

@Serializable
data class CloudMetric(
    val key: String,
    val label: String,
    val value: Double,
    val unit: CloudUnit,
    val window: String? = null,
)

@Serializable
data class CloudResource(
    val id: String,
    val kind: CloudKind,
    val platform: CloudPlatform,
    val name: String,
    val status: ResourceStatus = ResourceStatus.Unknown,
    val statusDetail: String? = null,
    val app: String? = null,
    val region: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val metrics: List<CloudMetric> = emptyList(),
    val evidence: List<CloudEvidence> = emptyList(),
    val consoleUrl: String? = null,
    val createdAtMillis: Long? = null,
) {
    val declared: Boolean get() = evidence.any { it.source == CloudSource.Repository }
    val observed: Boolean get() = evidence.any { it.source.observed }
    val placeholder: Boolean get() = attributes["placeholder"] == "true"
    fun metric(key: String): CloudMetric? = metrics.firstOrNull { it.key == key }
}

@Serializable
enum class CloudRelationKind(val label: String, val verb: String) {
    Contains("Contains", "contains"),
    Routes("Route", "routes to"),
    Binds("Binding", "binds"),
    Calls("Service binding", "calls"),
    Connects("Connection", "connects to"),
    Tunnels("Tunnel", "reaches through"),
    Selects("Selector", "sends traffic to"),
    Owns("Owner", "runs"),
    Mounts("Mount", "mounts"),
    StoresOn("Storage", "is stored on"),
    RunsOn("Placement", "runs on"),
    Attached("Attachment", "is attached to"),
    Exposes("Firewall", "opens"),
    Pulls("Image", "pulls images from"),
    Sends("Delivery", "sends to"),
}

@Serializable
data class CloudRelation(
    val source: String,
    val target: String,
    val kind: CloudRelationKind,
    val label: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val evidence: List<CloudEvidence> = emptyList(),
) {
    val id: String get() = "$source|${kind.name}|$target|${label.orEmpty()}"
    val declared: Boolean get() = evidence.any { it.source == CloudSource.Repository }
    val observed: Boolean get() = evidence.any { it.source.observed }
    val inferred: Boolean get() = evidence.isNotEmpty() && evidence.all { it.source == CloudSource.Inferred }
}

@Serializable
sealed interface CloudReference {
    val from: String
    val kind: CloudRelationKind
    val label: String?
    val evidence: CloudEvidence

    @Serializable
    data class ServiceHost(
        override val from: String,
        val host: String,
        val port: Int?,
        val namespaceHint: String?,
        override val kind: CloudRelationKind,
        override val label: String?,
        override val evidence: CloudEvidence,
    ) : CloudReference

    @Serializable
    data class ClusterAddress(
        override val from: String,
        val address: String,
        override val kind: CloudRelationKind,
        override val label: String?,
        override val evidence: CloudEvidence,
    ) : CloudReference

    @Serializable
    data class HostPath(
        override val from: String,
        val machine: String,
        val path: String,
        override val kind: CloudRelationKind,
        override val label: String?,
        override val evidence: CloudEvidence,
    ) : CloudReference

    @Serializable
    data class Image(
        override val from: String,
        val image: String,
        override val kind: CloudRelationKind,
        override val label: String?,
        override val evidence: CloudEvidence,
    ) : CloudReference
}

@Serializable
enum class CloudChangeKind(val label: String) {
    Deployed("Deployed"),
    Created("Created"),
    Started("Started"),
    Restarted("Restarted"),
    Scaled("Scaled"),
    Warning("Warning"),
    Operation("Operation"),
}

@Serializable
data class CloudChange(
    val atMillis: Long,
    val resourceId: String,
    val kind: CloudChangeKind,
    val title: String,
    val actor: String? = null,
    val detail: String? = null,
)

@Serializable
data class CloudReading(
    val provider: String,
    val platform: CloudPlatform?,
    val health: ProviderHealth,
    val readAtMillis: Long,
    val durationMillis: Long,
    val resources: List<CloudResource> = emptyList(),
    val relations: List<CloudRelation> = emptyList(),
    val references: List<CloudReference> = emptyList(),
    val changes: List<CloudChange> = emptyList(),
    val source: String = "",
)

@Serializable
enum class CloudFindingCategory(val label: String) {
    Security("Security"),
    Reliability("Reliability"),
    Health("Health"),
    Drift("Drift"),
    Cost("Cost"),
    Observability("Observability"),
}

@Serializable
enum class CloudSeverity(val label: String, val rank: Int) {
    Critical("Critical", 0),
    High("High", 1),
    Medium("Medium", 2),
    Low("Low", 3),
    Note("Note", 4),
}

@Serializable
data class CloudFinding(
    val id: String,
    val category: CloudFindingCategory,
    val severity: CloudSeverity,
    val title: String,
    val detail: String,
    val resourceIds: List<String>,
    val fix: String? = null,
)

@Serializable
data class CloudSnapshot(
    val resources: List<CloudResource>,
    val relations: List<CloudRelation>,
    val changes: List<CloudChange>,
    val readings: List<CloudReading>,
    val findings: List<CloudFinding>,
    val takenAtMillis: Long,
    val unresolved: List<CloudReference> = emptyList(),
) {
    @Transient
    val byId: Map<String, CloudResource> = resources.associateBy { it.id }
    @Transient
    private val outgoing: Map<String, List<CloudRelation>> = relations.groupBy { it.source }
    @Transient
    private val incoming: Map<String, List<CloudRelation>> = relations.groupBy { it.target }

    operator fun get(id: String): CloudResource? = byId[id]
    fun outgoing(id: String): List<CloudRelation> = outgoing[id].orEmpty()
    fun incoming(id: String): List<CloudRelation> = incoming[id].orEmpty()
    fun findingsFor(id: String): List<CloudFinding> = findings.filter { id in it.resourceIds }

    companion object {
        val Empty = CloudSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), 0L)
    }
}
