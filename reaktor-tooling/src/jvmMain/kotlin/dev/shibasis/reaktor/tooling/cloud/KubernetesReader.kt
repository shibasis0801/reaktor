package dev.shibasis.reaktor.tooling.cloud

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.File

class KubernetesReader(
    private val kubeconfig: File,
    private val kubectl: String = "kubectl",
    private val clock: () -> Long = System::currentTimeMillis,
    override val id: String = "kubernetes",
) : CloudProvider {
    private val kinds = "nodes,pods,deployments,statefulsets,daemonsets,cronjobs,jobs,replicasets,services,persistentvolumeclaims,persistentvolumes"

    override suspend fun read(): CloudReading = coroutineScope {
        val started = clock()
        require(kubeconfig.isFile) { "The cluster configuration ${kubeconfig.path} is missing" }
        fun command(vararg args: String) = listOf(kubectl, "--kubeconfig", kubeconfig.absolutePath, "--request-timeout=20s") + args
        val objects = async { CommandLine.json(command("get", kinds, "--all-namespaces", "-o", "json"), 45) }
        val podUsage = async { runCatching { CommandLine.json(command("get", "--raw", "/apis/metrics.k8s.io/v1beta1/pods"), 30) }.getOrNull() }
        val nodeUsage = async { runCatching { CommandLine.json(command("get", "--raw", "/apis/metrics.k8s.io/v1beta1/nodes"), 30) }.getOrNull() }
        val events = async { runCatching { CommandLine.json(command("get", "events", "--all-namespaces", "-o", "json"), 30) }.getOrNull() }
        val builder = KubernetesInventoryBuilder(clock(), kubeconfig.name)
        builder.read(objects.await(), podUsage.await(), nodeUsage.await(), events.await())
        val finished = clock()
        CloudReading(
            provider = id,
            platform = CloudPlatform.Kubernetes,
            health = ProviderHealth(id, ResourceStatus.Healthy, builder.summary()),
            readAtMillis = finished,
            durationMillis = finished - started,
            resources = builder.resources(),
            relations = builder.relations(),
            references = builder.references(),
            changes = builder.changes(),
            source = "cluster in ${kubeconfig.path}",
        )
    }
}

internal class KubernetesInventoryBuilder(private val readAt: Long, private val sourceName: String) {
    private val resources = linkedMapOf<String, CloudResource>()
    private val relations = mutableListOf<CloudRelation>()
    private val references = mutableListOf<CloudReference>()
    private val changes = mutableListOf<CloudChange>()

    fun resources() = resources.values.toList()
    fun relations() = relations.distinctBy { it.id }
    fun references() = references.toList()
    fun changes() = changes.toList()
    fun summary() = "${resources.values.count { it.kind == CloudKind.Pod }} pods on ${resources.values.count { it.kind == CloudKind.Machine }} node(s)"

    private fun evidence(detail: String) = CloudEvidence(CloudSource.KubernetesApi, detail, readAt)
    private fun relate(source: String, target: String, kind: CloudRelationKind, label: String? = null, detail: String) {
        relations += CloudRelation(source, target, kind, label, evidence = listOf(evidence(detail)))
    }

    private fun key(namespace: String?, kind: String, name: String) = "k8s:${namespace.orEmpty()}/$kind/$name"

    fun read(objects: JsonElement, podUsage: JsonElement?, nodeUsage: JsonElement?, events: JsonElement?) {
        val items = objects.objects("items")
        val byKind = items.groupBy { it.text("kind").orEmpty() }
        val podMetrics = podUsage.objects("items").associate { item ->
            "${item.obj("metadata").text("namespace")}/${item.obj("metadata").text("name")}" to item.objects("containers").fold(0.0 to 0.0) { (cpu, memory), container ->
                cpu + millicores(container.obj("usage").text("cpu")) to memory + bytes(container.obj("usage").text("memory"))
            }
        }
        val nodeMetrics = nodeUsage.objects("items").associate { item ->
            item.obj("metadata").text("name").orEmpty() to (millicores(item.obj("usage").text("cpu")) to bytes(item.obj("usage").text("memory")))
        }
        val replicaSetOwners = byKind["ReplicaSet"].orEmpty().associate { set ->
            "${set.obj("metadata").text("namespace")}/${set.obj("metadata").text("name")}" to set.obj("metadata").objects("ownerReferences").firstOrNull()
        }
        val jobOwners = byKind["Job"].orEmpty().associate { job ->
            "${job.obj("metadata").text("namespace")}/${job.obj("metadata").text("name")}" to job.obj("metadata").objects("ownerReferences").firstOrNull()
        }
        val pods = byKind["Pod"].orEmpty()
        val hostPorts = hostPorts(pods, byKind["Service"].orEmpty())
        byKind["Node"].orEmpty().forEach { node(it, nodeMetrics, hostPorts) }
        listOf("Deployment" to CloudKind.Deployment, "StatefulSet" to CloudKind.StatefulSet, "DaemonSet" to CloudKind.DaemonSet, "CronJob" to CloudKind.CronJob)
            .forEach { (kind, cloudKind) -> byKind[kind].orEmpty().forEach { workload(it, kind, cloudKind) } }
        pods.forEach { pod(it, podMetrics, replicaSetOwners, jobOwners) }
        byKind["Service"].orEmpty().forEach { service(it, pods, replicaSetOwners, jobOwners) }
        val volumes = byKind["PersistentVolume"].orEmpty().associateBy { it.obj("metadata").text("name").orEmpty() }
        val claimed = mutableSetOf<String>()
        byKind["PersistentVolumeClaim"].orEmpty().forEach { claim ->
            val volumeName = claim.obj("spec").text("volumeName")
            volumeName?.let(claimed::add)
            volume(claim, volumes[volumeName], pods)
        }
        volumes.filterKeys { it !in claimed }.values.forEach { volume(null, it, pods) }
        byKind["ReplicaSet"].orEmpty().forEach(::rollout)
        events.objects("items").forEach(::event)
    }

    private fun node(node: JsonObject, usage: Map<String, Pair<Double, Double>>, hostPorts: Map<String, Set<Int>>) {
        val metadata = node.obj("metadata")
        val name = metadata.text("name") ?: return
        val status = node.obj("status")
        val conditions = status.objects("conditions").associate { it.text("type").orEmpty() to it.text("status").orEmpty() }
        val pressure = conditions.filterKeys { it.endsWith("Pressure") || it == "NetworkUnavailable" }.filterValues { it == "True" }.keys
        val allocatableCpu = millicores(status.obj("allocatable").text("cpu"))
        val allocatableMemory = bytes(status.obj("allocatable").text("memory"))
        val capacityMemory = bytes(status.obj("capacity").text("memory"))
        val (cpu, memory) = usage[name] ?: (null to null)
        val info = status.obj("nodeInfo")
        resources["machine:$name"] = CloudResource(
            id = "machine:$name", kind = CloudKind.Machine, platform = CloudPlatform.Kubernetes, name = name,
            status = when {
                conditions["Ready"] != "True" -> ResourceStatus.Down
                pressure.isNotEmpty() -> ResourceStatus.Degraded
                else -> ResourceStatus.Healthy
            },
            statusDetail = if (pressure.isNotEmpty()) pressure.joinToString() else if (conditions["Ready"] == "True") "Ready" else "Not ready",
            attributes = mapOfNotNull(
                "kubernetesNode" to "true",
                "kubelet" to info.text("kubeletVersion"),
                "os" to info.text("osImage"),
                "kernel" to info.text("kernelVersion"),
                "runtime" to info.text("containerRuntimeVersion"),
                "internalIP" to status.objects("addresses").firstOrNull { it.text("type") == "InternalIP" }?.text("address"),
                "allocatableCpu" to "${allocatableCpu.toInt()}m",
                "allocatableMemory" to allocatableMemory.toLong().toString(),
                "roles" to metadata.obj("labels").keys.filter { it.startsWith("node-role.kubernetes.io/") }.joinToString { it.substringAfter('/') }.ifBlank { null },
                "hostPorts" to hostPorts[name]?.sorted()?.joinToString(",")?.ifBlank { null },
            ),
            metrics = buildList {
                cpu?.let {
                    add(CloudMetric("cpu", "CPU", it, CloudUnit.Millicores))
                    if (allocatableCpu > 0) add(CloudMetric("cpuPercent", "CPU", it / allocatableCpu * 100, CloudUnit.Percent))
                }
                memory?.let {
                    add(CloudMetric("memory", "Memory", it, CloudUnit.Bytes))
                    val total = if (capacityMemory > 0) capacityMemory else allocatableMemory
                    if (total > 0) add(CloudMetric("memoryPercent", "Memory", it / total * 100, CloudUnit.Percent))
                }
                if (allocatableMemory > 0) add(CloudMetric("memoryAllocatable", "Allocatable memory", allocatableMemory, CloudUnit.Bytes))
                if (allocatableCpu > 0) add(CloudMetric("cpuAllocatable", "Allocatable CPU", allocatableCpu, CloudUnit.Millicores))
            },
            evidence = listOf(evidence("nodes")),
            createdAtMillis = instantMillis(metadata.text("creationTimestamp")),
        )
    }

    private fun workload(item: JsonObject, kind: String, cloudKind: CloudKind) {
        val metadata = item.obj("metadata")
        val name = metadata.text("name") ?: return
        val namespace = metadata.text("namespace")
        val spec = item.obj("spec")
        val status = item.obj("status")
        val template = if (kind == "CronJob") spec.obj("jobTemplate").obj("spec").obj("template") else spec.obj("template")
        val containers = template.obj("spec").objects("containers")
        val desired = when (kind) {
            "DaemonSet" -> status.long("desiredNumberScheduled")
            "CronJob" -> null
            else -> spec.long("replicas") ?: 1L
        }
        val ready = when (kind) {
            "DaemonSet" -> status.long("numberReady") ?: 0L
            "CronJob" -> null
            else -> status.long("readyReplicas") ?: 0L
        }
        val id = key(namespace, kind, name)
        resources[id] = CloudResource(
            id = id, kind = cloudKind, platform = CloudPlatform.Kubernetes, name = name,
            status = when {
                kind == "CronJob" -> if (spec.flag("suspend") == true) ResourceStatus.Idle else ResourceStatus.Healthy
                desired == 0L -> ResourceStatus.Idle
                ready == desired -> ResourceStatus.Healthy
                (ready ?: 0L) == 0L -> ResourceStatus.Down
                else -> ResourceStatus.Degraded
            },
            statusDetail = if (kind == "CronJob") "schedule ${spec.text("schedule")}${status.text("lastScheduleTime")?.let { " · last run $it" }.orEmpty()}"
                else "$ready/$desired ready",
            attributes = mapOfNotNull(
                "namespace" to namespace,
                "replicas" to desired?.toString(),
                "ready" to ready?.toString(),
                "images" to containers.mapNotNull { it.text("image") }.joinToString().ifBlank { null },
                "schedule" to spec.text("schedule"),
                "strategy" to (spec.obj("strategy").text("type") ?: spec.obj("updateStrategy").text("type")),
                "selector" to spec.obj("selector").obj("matchLabels").entries.joinToString { "${it.key}=${text(it.value)}" }.ifBlank { null },
                "priorityClass" to template.obj("spec").text("priorityClassName"),
                "readinessProbe" to containers.all { it.containsKey("readinessProbe") }.toString(),
                "livenessProbe" to containers.all { it.containsKey("livenessProbe") }.toString(),
                "cpuRequest" to sum(containers) { it.obj("resources").obj("requests").text("cpu")?.let(::millicores) }?.let { "${it.toInt()}m" },
                "memoryRequest" to sum(containers) { it.obj("resources").obj("requests").text("memory")?.let(::bytes) }?.toLong()?.toString(),
                "memoryLimit" to sum(containers) { it.obj("resources").obj("limits").text("memory")?.let(::bytes) }?.toLong()?.toString(),
                "cpuLimit" to sum(containers) { it.obj("resources").obj("limits").text("cpu")?.let(::millicores) }?.let { "${it.toInt()}m" },
                "env" to containers.flatMap { container -> container.objects("env").mapNotNull { it.text("name") } }.distinct().joinToString().ifBlank { null },
                "secrets" to (containers.flatMap { container ->
                    container.objects("env").mapNotNull { it.obj("valueFrom").obj("secretKeyRef").text("name") } +
                        container.objects("envFrom").mapNotNull { it.obj("secretRef").text("name") }
                } + template.obj("spec").objects("volumes").mapNotNull { it.obj("secret").text("secretName") }).distinct().joinToString().ifBlank { null },
                "hostNetwork" to template.obj("spec").flag("hostNetwork")?.toString(),
                "labels" to template.obj("metadata").obj("labels").entries.joinToString { "${it.key}=${text(it.value)}" }.ifBlank { null },
            ),
            evidence = listOf(evidence(kind.lowercase() + "s")),
            createdAtMillis = instantMillis(metadata.text("creationTimestamp")),
        )
        containers.mapNotNull { it.text("image") }.distinct().forEach { image ->
            references += CloudReference.Image(id, image, CloudRelationKind.Pulls, image.substringAfterLast('/'),
                CloudEvidence(CloudSource.Inferred, "container image $image", readAt))
        }
    }

    private fun owner(pod: JsonObject, replicaSets: Map<String, JsonObject?>, jobs: Map<String, JsonObject?>): String? {
        val metadata = pod.obj("metadata")
        val namespace = metadata.text("namespace")
        val reference = metadata.objects("ownerReferences").firstOrNull() ?: return null
        val kind = reference.text("kind") ?: return null
        val name = reference.text("name") ?: return null
        return when (kind) {
            "ReplicaSet" -> replicaSets["$namespace/$name"]?.let { key(namespace, it.text("kind").orEmpty(), it.text("name").orEmpty()) }
            "Job" -> jobs["$namespace/$name"]?.let { key(namespace, it.text("kind").orEmpty(), it.text("name").orEmpty()) }
            else -> key(namespace, kind, name)
        }
    }

    private fun pod(pod: JsonObject, usage: Map<String, Pair<Double, Double>>, replicaSets: Map<String, JsonObject?>, jobs: Map<String, JsonObject?>) {
        val metadata = pod.obj("metadata")
        val name = metadata.text("name") ?: return
        val namespace = metadata.text("namespace")
        val spec = pod.obj("spec")
        val status = pod.obj("status")
        val containers = status.objects("containerStatuses")
        val ready = containers.count { it.flag("ready") == true }
        val phase = status.text("phase")
        val waiting = containers.firstNotNullOfOrNull { it.obj("state").obj("waiting").text("reason") }
        val lastTermination = containers.mapNotNull { it.obj("lastState").obj("terminated") }.filter { it.isNotEmpty() }
            .maxByOrNull { instantMillis(it.text("finishedAt")) ?: 0L }
        val restarts = containers.sumOf { it.long("restartCount") ?: 0L }
        val ownerId = owner(pod, replicaSets, jobs)
        val node = spec.text("nodeName")
        val (cpu, memory) = usage["$namespace/$name"] ?: (null to null)
        val id = key(namespace, "Pod", name)
        resources[id] = CloudResource(
            id = id, kind = CloudKind.Pod, platform = CloudPlatform.Kubernetes, name = name,
            status = when {
                phase == "Succeeded" -> ResourceStatus.Idle
                waiting != null && waiting != "ContainerCreating" -> ResourceStatus.Down
                phase == "Running" && ready == containers.size -> ResourceStatus.Healthy
                phase == "Running" -> ResourceStatus.Degraded
                else -> ResourceStatus.Down
            },
            statusDetail = waiting ?: phase,
            attributes = mapOfNotNull(
                "namespace" to namespace,
                "phase" to phase,
                "ready" to "$ready/${containers.size}",
                "restarts" to restarts.toString(),
                "lastTermination" to lastTermination?.text("reason"),
                "lastExitCode" to lastTermination?.text("exitCode"),
                "lastRestartAtMillis" to lastTermination?.text("finishedAt")?.let(::instantMillis)?.toString(),
                "node" to node,
                "podIP" to status.text("podIP"),
                "owner" to ownerId?.substringAfterLast('/'),
                "ownerId" to ownerId,
                "qos" to status.text("qosClass"),
                "started" to status.text("startTime"),
                "images" to spec.objects("containers").mapNotNull { it.text("image") }.joinToString().ifBlank { null },
                "containers" to spec.objects("containers").mapNotNull { it.text("name") }.joinToString().ifBlank { null },
                "hostNetwork" to spec.flag("hostNetwork")?.toString(),
                "memoryLimit" to sum(spec.objects("containers")) { it.obj("resources").obj("limits").text("memory")?.let(::bytes) }?.toLong()?.toString(),
                "memoryRequest" to sum(spec.objects("containers")) { it.obj("resources").obj("requests").text("memory")?.let(::bytes) }?.toLong()?.toString(),
                "cpuRequest" to sum(spec.objects("containers")) { it.obj("resources").obj("requests").text("cpu")?.let(::millicores) }?.let { "${it.toInt()}m" },
            ),
            metrics = listOfNotNull(
                cpu?.let { CloudMetric("cpu", "CPU", it, CloudUnit.Millicores) },
                memory?.let { CloudMetric("memory", "Memory", it, CloudUnit.Bytes) },
            ),
            evidence = listOf(evidence("pods")),
            createdAtMillis = instantMillis(metadata.text("creationTimestamp")),
        )
        ownerId?.let { relate(it, id, CloudRelationKind.Owns, detail = "ownerReferences") }
        node?.let { relate(id, "machine:$it", CloudRelationKind.RunsOn, detail = "spec.nodeName") }
        spec.objects("volumes").mapNotNull { volume -> volume.obj("persistentVolumeClaim").text("claimName")?.let { volume.text("name") to it } }
            .forEach { (volumeName, claim) -> relate(id, key(namespace, "Volume", claim), CloudRelationKind.Mounts, volumeName, "spec.volumes") }
        status.text("startTime")?.let(::instantMillis)?.let { started ->
            if (readAt - started < 7 * 86_400_000L) changes += CloudChange(started, id, CloudChangeKind.Started, "Started $name", detail = ownerId?.substringAfterLast('/')?.let { "for $it" })
        }
        lastTermination?.let { terminated ->
            instantMillis(terminated.text("finishedAt"))?.let { at ->
                changes += CloudChange(at, id, CloudChangeKind.Restarted, "Restarted a container in $name",
                    detail = listOfNotNull(terminated.text("reason"), terminated.text("exitCode")?.let { "exit $it" }).joinToString(" · ").ifBlank { null })
            }
        }
    }

    private fun service(item: JsonObject, pods: List<JsonObject>, replicaSets: Map<String, JsonObject?>, jobs: Map<String, JsonObject?>) {
        val metadata = item.obj("metadata")
        val name = metadata.text("name") ?: return
        val namespace = metadata.text("namespace")
        if (namespace == "default" && name == "kubernetes") return
        val spec = item.obj("spec")
        val selector = spec.obj("selector").entries.associate { it.key to text(it.value) }
        val selected = if (selector.isEmpty()) emptyList() else pods.filter { pod ->
            pod.obj("metadata").text("namespace") == namespace &&
                selector.all { (label, value) -> pod.obj("metadata").obj("labels").text(label) == value }
        }
        val readyPods = selected.count { pod -> pod.obj("status").objects("containerStatuses").all { it.flag("ready") == true } && pod.obj("status").text("phase") == "Running" }
        val id = key(namespace, "Service", name)
        resources[id] = CloudResource(
            id = id, kind = CloudKind.KubernetesService, platform = CloudPlatform.Kubernetes, name = name,
            status = when {
                selector.isEmpty() -> ResourceStatus.Unknown
                readyPods > 0 -> ResourceStatus.Healthy
                else -> ResourceStatus.Down
            },
            statusDetail = if (selector.isEmpty()) "no selector" else "$readyPods ready endpoint${if (readyPods == 1) "" else "s"}",
            attributes = mapOfNotNull(
                "namespace" to namespace,
                "type" to spec.text("type"),
                "clusterIP" to spec.text("clusterIP")?.takeIf { it != "None" },
                "ports" to spec.objects("ports").joinToString { port ->
                    "${port.text("name")?.let { "$it " }.orEmpty()}${port.text("port")}${port.text("targetPort")?.let { "→$it" }.orEmpty()}${port.text("nodePort")?.let { " node $it" }.orEmpty()}"
                }.ifBlank { null },
                "selector" to selector.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { null },
            ),
            evidence = listOf(evidence("services")),
            createdAtMillis = instantMillis(metadata.text("creationTimestamp")),
        )
        selected.mapNotNull { owner(it, replicaSets, jobs) }.distinct().forEach { workload ->
            relate(id, workload, CloudRelationKind.Selects, detail = "spec.selector")
        }
    }

    private fun volume(claim: JsonObject?, volume: JsonObject?, pods: List<JsonObject>) {
        val claimMetadata = claim?.obj("metadata")
        val namespace = claimMetadata?.text("namespace")
        val name = claimMetadata?.text("name") ?: volume?.obj("metadata")?.text("name") ?: return
        val volumeSpec = volume?.obj("spec")
        val path = volumeSpec?.obj("local")?.text("path") ?: volumeSpec?.obj("hostPath")?.text("path")
        val id = if (claim != null) key(namespace, "Volume", name) else "k8s:pv/$name"
        val nodeAffinity = volumeSpec?.obj("nodeAffinity")?.obj("required")?.objects("nodeSelectorTerms")
            ?.flatMap { it.objects("matchExpressions") }?.firstOrNull { it.text("key") == "kubernetes.io/hostname" }?.strings("values")?.firstOrNull()
        val machine = nodeAffinity ?: pods.firstOrNull { pod ->
            pod.obj("metadata").text("namespace") == namespace &&
                pod.obj("spec").objects("volumes").any { it.obj("persistentVolumeClaim").text("claimName") == name }
        }?.obj("spec")?.text("nodeName")
        val capacity = claim?.obj("status")?.obj("capacity")?.text("storage") ?: volumeSpec?.obj("capacity")?.text("storage")
        resources[id] = CloudResource(
            id = id, kind = CloudKind.Volume, platform = CloudPlatform.Kubernetes, name = name,
            status = if ((claim?.obj("status")?.text("phase") ?: volume?.obj("status")?.text("phase")) == "Bound") ResourceStatus.Healthy else ResourceStatus.Degraded,
            statusDetail = claim?.obj("status")?.text("phase") ?: volume?.obj("status")?.text("phase"),
            attributes = mapOfNotNull(
                "namespace" to namespace,
                "capacity" to capacity,
                "storageClass" to (claim?.obj("spec")?.text("storageClassName") ?: volumeSpec?.text("storageClassName")),
                "volume" to volume?.obj("metadata")?.text("name"),
                "path" to path,
                "machine" to machine,
                "reclaimPolicy" to volumeSpec?.text("persistentVolumeReclaimPolicy"),
            ),
            metrics = listOfNotNull(capacity?.let { CloudMetric("capacity", "Capacity", bytes(it), CloudUnit.Bytes) }),
            evidence = listOf(evidence("persistentvolumeclaims")),
        )
        if (path != null && machine != null) {
            references += CloudReference.HostPath(id, machine, path, CloudRelationKind.StoresOn, path,
                CloudEvidence(CloudSource.Inferred, "volume path $path on $machine", readAt))
        }
    }

    private fun rollout(set: JsonObject) {
        val metadata = set.obj("metadata")
        val owner = metadata.objects("ownerReferences").firstOrNull() ?: return
        if (owner.text("kind") != "Deployment") return
        val created = instantMillis(metadata.text("creationTimestamp")) ?: return
        if (readAt - created > 30 * 86_400_000L) return
        val revision = metadata.obj("annotations").text("deployment.kubernetes.io/revision")
        val image = set.obj("spec").obj("template").obj("spec").objects("containers").firstOrNull()?.text("image")
        changes += CloudChange(created, key(metadata.text("namespace"), "Deployment", owner.text("name").orEmpty()), CloudChangeKind.Deployed,
            "Rolled out ${owner.text("name")}${revision?.let { " revision $it" }.orEmpty()}", detail = image?.substringAfterLast('/'))
    }

    private fun event(event: JsonObject) {
        val involved = event.obj("involvedObject")
        val at = instantMillis(event.text("lastTimestamp") ?: event.text("eventTime") ?: event.obj("metadata").text("creationTimestamp")) ?: return
        val warning = event.text("type") == "Warning"
        val reason = event.text("reason").orEmpty()
        if (!warning && reason !in setOf("Killing", "ScalingReplicaSet", "Evicted", "Preempted", "NodeNotReady", "Rebooted")) return
        val kind = involved.text("kind").orEmpty()
        val target = when (kind) {
            "Node" -> "machine:${involved.text("name")}"
            "PersistentVolumeClaim" -> key(involved.text("namespace"), "Volume", involved.text("name").orEmpty())
            else -> key(involved.text("namespace"), kind, involved.text("name").orEmpty())
        }
        changes += CloudChange(at, target, if (warning) CloudChangeKind.Warning else CloudChangeKind.Scaled,
            "$reason · ${involved.text("name")}", detail = listOfNotNull(event.text("message"), event.long("count")?.takeIf { it > 1 }?.let { "$it times" }).joinToString(" · "))
    }

    private fun hostPorts(pods: List<JsonObject>, services: List<JsonObject>): Map<String, Set<Int>> {
        val byNode = mutableMapOf<String, MutableSet<Int>>()
        pods.forEach { pod ->
            val node = pod.obj("spec").text("nodeName") ?: return@forEach
            val hostNetwork = pod.obj("spec").flag("hostNetwork") == true
            pod.obj("spec").objects("containers").flatMap { it.objects("ports") }.forEach { port ->
                val exposed = port.long("hostPort")?.toInt() ?: if (hostNetwork) port.long("containerPort")?.toInt() else null
                exposed?.let { byNode.getOrPut(node) { mutableSetOf() }.add(it) }
            }
        }
        val nodePorts = services.filter { it.obj("spec").text("type") in setOf("NodePort", "LoadBalancer") }.flatMap { service ->
            service.obj("spec").objects("ports").flatMap { port ->
                listOfNotNull(port.long("nodePort")?.toInt(), port.long("port")?.toInt().takeIf { service.obj("spec").text("type") == "LoadBalancer" })
            }
        }
        val nodes = pods.mapNotNull { it.obj("spec").text("nodeName") }.toSet()
        nodes.forEach { node -> byNode.getOrPut(node) { mutableSetOf() }.addAll(nodePorts) }
        return byNode
    }

    private fun sum(containers: List<JsonObject>, value: (JsonObject) -> Double?): Double? =
        containers.mapNotNull(value).takeIf { it.isNotEmpty() }?.sum()

    private fun text(element: JsonElement): String = (element as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
}

internal fun millicores(value: String?): Double {
    if (value.isNullOrBlank()) return 0.0
    return when {
        value.endsWith("n") -> value.dropLast(1).toDoubleOrNull()?.div(1_000_000) ?: 0.0
        value.endsWith("u") -> value.dropLast(1).toDoubleOrNull()?.div(1_000) ?: 0.0
        value.endsWith("m") -> value.dropLast(1).toDoubleOrNull() ?: 0.0
        else -> value.toDoubleOrNull()?.times(1000) ?: 0.0
    }
}

internal fun bytes(value: String?): Double {
    if (value.isNullOrBlank()) return 0.0
    val units = listOf("Ki" to 1024.0, "Mi" to 1024.0 * 1024, "Gi" to 1024.0 * 1024 * 1024, "Ti" to 1024.0 * 1024 * 1024 * 1024,
        "k" to 1e3, "K" to 1e3, "M" to 1e6, "G" to 1e9, "T" to 1e12)
    units.forEach { (suffix, scale) -> if (value.endsWith(suffix)) return (value.removeSuffix(suffix).toDoubleOrNull() ?: 0.0) * scale }
    return value.toDoubleOrNull() ?: 0.0
}
