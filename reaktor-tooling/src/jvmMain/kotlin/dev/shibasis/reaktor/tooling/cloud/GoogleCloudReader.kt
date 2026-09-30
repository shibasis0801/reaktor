package dev.shibasis.reaktor.tooling.cloud

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

class GoogleCloudReader(
    private val project: String,
    private val gcloud: String = "gcloud",
    private val accounts: suspend () -> List<String> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    override val id: String = "gcp",
) : CloudProvider {
    private val console = "https://console.cloud.google.com"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    private val environment: Map<String, String> = listOf("python3.13", "python3.12", "python3.11", "python3.10")
        .flatMap { listOf("/opt/homebrew/bin/$it", "/usr/local/bin/$it", "/usr/bin/$it") }
        .firstOrNull { File(it).canExecute() }
        ?.let { mapOf("CLOUDSDK_PYTHON" to it) }.orEmpty()

    private suspend fun token(account: String?): String {
        val cached = tokens[account.orEmpty()]
        if (cached != null && cached.second > clock()) return cached.first
        val value = CommandLine.run(listOf(gcloud, "auth", "print-access-token") + listOfNotNull(account?.let { "--account=$it" }), 60, environment).trim()
        require(value.isNotBlank()) { "gcloud returned no access token" }
        tokens[account.orEmpty()] = value to clock() + 45 * 60_000L
        return value
    }

    private suspend fun get(token: String, url: String): JsonElement {
        val request = HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer $token").timeout(Duration.ofSeconds(30)).GET().build()
        val response = http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
        val parsed = runCatching { cloudJson.parseToJsonElement(response.body()) }.getOrNull()
        if (response.statusCode() !in 200..299) {
            throw CloudReadFailure("${response.statusCode()} ${parsed.obj("error").text("status").orEmpty()} ${parsed.obj("error").text("message").orEmpty()}".trim())
        }
        return parsed ?: throw CloudReadFailure("Unreadable response from $url")
    }

    private suspend fun list(token: String, url: String, key: String): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        var page: String? = null
        do {
            val separator = if ('?' in url) '&' else '?'
            val body = get(token, url + (page?.let { "${separator}pageToken=$it" } ?: ""))
            val element = (body as? JsonObject)?.get(key)
            when (element) {
                is JsonArray -> items += element.list()
                is JsonObject -> element.values.forEach { scope -> items += scope.objects(scopeKey(url)) }
                else -> Unit
            }
            page = body.text("nextPageToken")
        } while (page != null && items.size < 5_000)
        return items
    }

    private fun scopeKey(url: String): String = when {
        "/aggregated/instances" in url -> "instances"
        "/aggregated/disks" in url -> "disks"
        "/aggregated/addresses" in url -> "addresses"
        "/aggregated/operations" in url -> "operations"
        else -> "items"
    }

    override suspend fun read(): CloudReading {
        val started = clock()
        val candidates = (listOfNotNull(working[project]) + listOf("") + accounts()).distinct()
        var last: Exception? = null
        for (candidate in candidates) {
            try {
                return readAs(candidate.takeIf { it.isNotBlank() }, started).also { working[project] = candidate }
            } catch (failure: Exception) {
                last = failure
                if (!permission(failure)) break
            }
        }
        throw CloudReadFailure(last?.message ?: "No gcloud account can read project $project")
    }

    private fun permission(failure: Exception): Boolean = failure.message.orEmpty().let { message ->
        "PERMISSION_DENIED" in message || "403" in message || "401" in message || "permission" in message.lowercase()
    }

    private suspend fun readAs(signedIn: String?, started: Long): CloudReading = coroutineScope {
        val token = token(signedIn)
        val compute = "https://compute.googleapis.com/compute/v1/projects/$project"
        val instances = async { list(token, "$compute/aggregated/instances", "items") }
        val disks = async { list(token, "$compute/aggregated/disks", "items") }
        val firewalls = async { list(token, "$compute/global/firewalls", "items") }
        val addresses = async { runCatching { list(token, "$compute/aggregated/addresses", "items") }.getOrDefault(emptyList()) }
        val snapshots = async { runCatching { list(token, "$compute/global/snapshots", "items") }.getOrDefault(emptyList()) }
        val repositories = async {
            val regions = instances.await().mapNotNull { it.text("zone")?.substringAfterLast('/')?.substringBeforeLast('-') }.distinct()
            regions.map { region ->
                async { runCatching { list(token, "https://artifactregistry.googleapis.com/v1/projects/$project/locations/$region/repositories", "repositories") }.getOrDefault(emptyList()) }
            }.flatMap { it.await() }
        }
        val since = Instant.ofEpochMilli(clock() - 7 * 86_400_000L)
        val operations = async {
            runCatching { list(token, "$compute/aggregated/operations?maxResults=200", "items") }.getOrDefault(emptyList())
                .filter { operation -> instantMillis(operation.text("insertTime"))?.let { it >= since.toEpochMilli() } == true }
        }
        val builder = GoogleCloudInventoryBuilder(project, console, clock())
        builder.read(instances.await(), disks.await(), firewalls.await(), addresses.await(), snapshots.await(), repositories.await(), operations.await())
        val finished = clock()
        CloudReading(
            provider = id,
            platform = CloudPlatform.GoogleCloud,
            health = ProviderHealth(id, ResourceStatus.Healthy, "project $project${signedIn?.let { " as $it" }.orEmpty()}"),
            readAtMillis = finished,
            durationMillis = finished - started,
            resources = builder.resources(),
            relations = builder.relations(),
            changes = builder.changes(),
            source = "project $project",
        )
    }
}

private val tokens = java.util.concurrent.ConcurrentHashMap<String, Pair<String, Long>>()
private val working = java.util.concurrent.ConcurrentHashMap<String, String>()

internal class GoogleCloudInventoryBuilder(private val project: String, private val console: String, private val readAt: Long) {
    private val resources = linkedMapOf<String, CloudResource>()
    private val relations = mutableListOf<CloudRelation>()
    private val changes = mutableListOf<CloudChange>()

    fun resources() = resources.values.toList()
    fun relations() = relations.distinctBy { it.id }
    fun changes() = changes.toList()

    private fun evidence(detail: String) = CloudEvidence(CloudSource.GoogleCloudApi, detail, readAt)
    private fun relate(source: String, target: String, kind: CloudRelationKind, label: String? = null, detail: String) {
        relations += CloudRelation(source, target, kind, label, evidence = listOf(evidence(detail)))
    }

    fun read(
        instances: List<JsonObject>, disks: List<JsonObject>, firewalls: List<JsonObject>,
        addresses: List<JsonObject>, snapshots: List<JsonObject>, repositories: List<JsonObject>, operations: List<JsonObject>,
    ) {
        val snapshotsByDisk = snapshots.groupBy { it.text("sourceDisk")?.substringAfterLast('/').orEmpty() }
        val deviceNames = instances.flatMap { instance ->
            instance.objects("disks").map { disk -> disk.text("source")?.substringAfterLast('/').orEmpty() to (disk.text("deviceName") to disk.flag("boot")) }
        }.toMap()
        instances.forEach(::instance)
        disks.forEach { disk(it, deviceNames, snapshotsByDisk) }
        firewalls.forEach { firewall(it, instances) }
        addresses.forEach(::address)
        repositories.forEach(::repository)
        operations.forEach(::operation)
    }

    private fun instance(instance: JsonObject) {
        val name = instance.text("name") ?: return
        val zone = instance.text("zone")?.substringAfterLast('/')
        val status = instance.text("status")
        val network = instance.objects("networkInterfaces").firstOrNull()
        val external = network.objects("accessConfigs").firstNotNullOfOrNull { it.text("natIP") }
        val serviceAccount = instance.objects("serviceAccounts").firstOrNull()
        resources["machine:$name"] = CloudResource(
            id = "machine:$name", kind = CloudKind.Machine, platform = CloudPlatform.GoogleCloud, name = name,
            status = when (status) {
                "RUNNING" -> ResourceStatus.Healthy
                "TERMINATED", "STOPPED", "SUSPENDED" -> ResourceStatus.Down
                else -> ResourceStatus.Degraded
            },
            statusDetail = status?.lowercase(),
            region = zone,
            attributes = mapOfNotNull(
                "project" to project,
                "zone" to zone,
                "machineType" to instance.text("machineType")?.substringAfterLast('/'),
                "cpuPlatform" to instance.text("cpuPlatform"),
                "internalIP" to network?.text("networkIP"),
                "externalIP" to external,
                "network" to network?.text("network")?.substringAfterLast('/'),
                "networkTags" to instance.obj("tags").strings("items").joinToString(",").ifBlank { null },
                "serviceAccount" to serviceAccount?.text("email"),
                "scopes" to serviceAccount?.strings("scopes")?.joinToString { it.substringAfterLast('/') }?.ifBlank { null },
                "provisioning" to instance.obj("scheduling").text("provisioningModel"),
                "preemptible" to instance.obj("scheduling").flag("preemptible")?.toString(),
                "deletionProtection" to instance.flag("deletionProtection")?.toString(),
                "created" to instance.text("creationTimestamp"),
                "lastStart" to instance.text("lastStartTimestamp"),
            ),
            evidence = listOf(evidence("compute instances")),
            consoleUrl = "$console/compute/instancesDetail/zones/$zone/instances/$name?project=$project",
            createdAtMillis = instance.text("creationTimestamp")?.let(::instantMillis),
        )
        instance.text("lastStartTimestamp")?.let(::instantMillis)?.let { at ->
            if (readAt - at < 30 * 86_400_000L) changes += CloudChange(at, "machine:$name", CloudChangeKind.Started, "Started $name")
        }
    }

    private fun disk(disk: JsonObject, deviceNames: Map<String, Pair<String?, Boolean?>>, snapshots: Map<String, List<JsonObject>>) {
        val name = disk.text("name") ?: return
        val zone = disk.text("zone")?.substringAfterLast('/')
        val users = disk.strings("users").map { it.substringAfterLast('/') }
        val taken = snapshots[name].orEmpty()
        val latest = taken.maxByOrNull { instantMillis(it.text("creationTimestamp")) ?: 0L }
        val id = "gcp:disk:$name"
        val (device, boot) = deviceNames[name] ?: (null to null)
        resources[id] = CloudResource(
            id = id, kind = CloudKind.Disk, platform = CloudPlatform.GoogleCloud, name = name,
            status = if (disk.text("status") == "READY") ResourceStatus.Healthy else ResourceStatus.Degraded,
            statusDetail = disk.text("status")?.lowercase(),
            region = zone,
            attributes = mapOfNotNull(
                "type" to disk.text("type")?.substringAfterLast('/'),
                "sizeGb" to disk.text("sizeGb"),
                "deviceName" to device,
                "boot" to boot?.toString(),
                "users" to users.joinToString(),
                "snapshots" to taken.size.toString(),
                "lastSnapshot" to latest?.text("creationTimestamp"),
                "snapshotSchedule" to disk.strings("resourcePolicies").joinToString { it.substringAfterLast('/') }.ifBlank { null },
                "image" to disk.text("sourceImage")?.substringAfterLast('/'),
                "created" to disk.text("creationTimestamp"),
            ),
            metrics = listOfNotNull(disk.number("sizeGb")?.let { CloudMetric("size", "Size", it * 1024 * 1024 * 1024, CloudUnit.Bytes) }),
            evidence = listOf(evidence("compute disks")),
            consoleUrl = "$console/compute/disksDetail/zones/$zone/disks/$name?project=$project",
            createdAtMillis = disk.text("creationTimestamp")?.let(::instantMillis),
        )
        users.forEach { relate(id, "machine:$it", CloudRelationKind.Attached, device, "compute disks") }
    }

    private fun firewall(rule: JsonObject, instances: List<JsonObject>) {
        val name = rule.text("name") ?: return
        val targets = rule.strings("targetTags")
        val network = rule.text("network")?.substringAfterLast('/')
        val allowed = rule.objects("allowed").flatMap { permission ->
            val protocol = permission.text("IPProtocol").orEmpty()
            val ports = permission.strings("ports")
            if (ports.isEmpty()) listOf("$protocol:all") else ports.map { "$protocol:$it" }
        }
        val id = "gcp:firewall:$name"
        val sources = rule.strings("sourceRanges")
        resources[id] = CloudResource(
            id = id, kind = CloudKind.FirewallRule, platform = CloudPlatform.GoogleCloud, name = name,
            status = if (rule.flag("disabled") == true) ResourceStatus.Idle else ResourceStatus.Healthy,
            statusDetail = if (rule.flag("disabled") == true) "disabled" else null,
            attributes = mapOfNotNull(
                "direction" to rule.text("direction"),
                "sourceRanges" to sources.joinToString(",").ifBlank { null },
                "allowed" to allowed.joinToString(",").ifBlank { null },
                "denied" to rule.objects("denied").joinToString(",") { "${it.text("IPProtocol")}:${it.strings("ports").joinToString(";")}" }.ifBlank { null },
                "targetTags" to targets.joinToString(",").ifBlank { null },
                "network" to network,
                "priority" to rule.text("priority"),
                "disabled" to (rule.flag("disabled") ?: false).toString(),
                "internet" to ("0.0.0.0/0" in sources).toString(),
            ),
            evidence = listOf(evidence("compute firewall-rules")),
            consoleUrl = "$console/net-security/firewall-manager/firewall-policies/details/$name?project=$project",
        )
        instances.filter { instance ->
            instance.objects("networkInterfaces").any { it.text("network")?.substringAfterLast('/') == network } &&
                (targets.isEmpty() || instance.obj("tags").strings("items").any { it in targets })
        }.forEach { instance -> relate(id, "machine:${instance.text("name")}", CloudRelationKind.Exposes, allowed.joinToString(), "compute firewall-rules") }
    }

    private fun address(address: JsonObject) {
        val name = address.text("name") ?: return
        val id = "gcp:address:$name"
        resources[id] = CloudResource(
            id = id, kind = CloudKind.Address, platform = CloudPlatform.GoogleCloud, name = name,
            status = if (address.text("status") == "IN_USE") ResourceStatus.Healthy else ResourceStatus.Idle,
            statusDetail = address.text("status")?.lowercase()?.replace('_', ' '),
            region = address.text("region")?.substringAfterLast('/'),
            attributes = mapOfNotNull("address" to address.text("address"), "type" to address.text("addressType"), "tier" to address.text("networkTier")),
            evidence = listOf(evidence("compute addresses")),
        )
        address.strings("users").map { it.substringAfterLast('/') }.forEach { relate(id, "machine:$it", CloudRelationKind.Attached, detail = "compute addresses") }
    }

    private fun repository(repository: JsonObject) {
        val path = repository.text("name") ?: return
        val name = path.substringAfterLast('/')
        val location = path.substringAfter("/locations/", "").substringBefore('/')
        val format = repository.text("format")
        val id = "gcp:registry:$name"
        resources[id] = CloudResource(
            id = id, kind = CloudKind.ImageRepository, platform = CloudPlatform.GoogleCloud, name = name,
            status = ResourceStatus.Healthy,
            region = location,
            attributes = mapOfNotNull(
                "format" to format,
                "location" to location,
                "prefix" to if (format == "DOCKER") "$location-docker.pkg.dev/$project/$name" else null,
                "cleanupPolicies" to repository.obj("cleanupPolicies").keys.joinToString().ifBlank { null },
                "created" to repository.text("createTime"),
            ),
            metrics = listOfNotNull(repository.number("sizeBytes")?.let { CloudMetric("size", "Stored images", it, CloudUnit.Bytes) }),
            evidence = listOf(evidence("artifacts repositories")),
            consoleUrl = "$console/artifacts/docker/$project/$location/$name?project=$project",
        )
    }

    private fun operation(operation: JsonObject) {
        val at = instantMillis(operation.text("insertTime")) ?: return
        val target = operation.text("targetLink")?.substringAfterLast("/projects/$project/")?.split('/') ?: return
        val resource = when {
            "instances" in target -> "machine:${target.last()}"
            "disks" in target -> "gcp:disk:${target.last()}"
            "firewalls" in target -> "gcp:firewall:${target.last()}"
            else -> return
        }
        val type = operation.text("operationType").orEmpty()
        if (type in setOf("compute.instances.getSerialPortOutput", "compute.instances.setMetadata") && operation.text("user")?.contains("gserviceaccount") == true) return
        changes += CloudChange(at, resource, CloudChangeKind.Operation,
            "${type.replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().substringAfterLast('.')} · ${target.last()}",
            actor = operation.text("user"),
            detail = operation.text("status")?.lowercase()?.let { status -> operation.obj("error").objects("errors").firstOrNull()?.text("message")?.let { "$status · $it" } ?: status })
    }
}
