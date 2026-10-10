package dev.shibasis.reaktor.tooling.cloud

object CloudAudit {
    private val DayMillis = 86_400_000L

    private data class SensitivePort(val port: Int, val label: String, val severity: CloudSeverity)

    private val sensitivePorts = listOf(
        SensitivePort(6443, "the Kubernetes API", CloudSeverity.High),
        SensitivePort(10250, "the kubelet API", CloudSeverity.High),
        SensitivePort(2379, "etcd", CloudSeverity.Critical),
        SensitivePort(22, "SSH", CloudSeverity.Medium),
        SensitivePort(3389, "Remote Desktop", CloudSeverity.Medium),
        SensitivePort(5432, "Postgres", CloudSeverity.High),
        SensitivePort(7687, "a Bolt graph database port", CloudSeverity.High),
        SensitivePort(7474, "a Neo4j browser", CloudSeverity.Medium),
        SensitivePort(7444, "a Memgraph log stream", CloudSeverity.Medium),
        SensitivePort(3000, "a web console", CloudSeverity.Medium),
        SensitivePort(8123, "ClickHouse over HTTP", CloudSeverity.High),
        SensitivePort(9000, "ClickHouse's native port", CloudSeverity.High),
        SensitivePort(9100, "node metrics", CloudSeverity.Low),
        SensitivePort(9091, "database metrics", CloudSeverity.Low),
    ).associateBy { it.port }

    private val internalOrigins = mapOf(
        7687 to "a graph database's Bolt port",
        9000 to "ClickHouse's native port",
        8123 to "ClickHouse's HTTP interface",
        5432 to "Postgres",
        3000 to "a web console",
        9100 to "node metrics",
        9091 to "database metrics",
        9363 to "database metrics",
    )

    fun findings(snapshot: CloudSnapshot): List<CloudFinding> = buildList {
        addAll(openPorts(snapshot))
        addAll(exposedHostnames(snapshot))
        addAll(unprotectedDisks(snapshot))
        addAll(pods(snapshot))
        addAll(tunnels(snapshot))
        addAll(machines(snapshot))
        addAll(singleReplicas(snapshot))
        addAll(workerHealth(snapshot))
        addAll(drift(snapshot))
        addAll(unused(snapshot))
        addAll(observability(snapshot))
        addAll(brokenRoutes(snapshot))
        addAll(floatingImages(snapshot))
    }

    private fun brokenRoutes(snapshot: CloudSnapshot): List<CloudFinding> {
        val kubernetesRead = snapshot.readings.any { it.platform == CloudPlatform.Kubernetes && it.health.status != ResourceStatus.Down }
        if (!kubernetesRead) return emptyList()
        return snapshot.unresolved.filterIsInstance<CloudReference.ServiceHost>().groupBy { it.from }.map { (from, missing) ->
            val source = snapshot[from]
            CloudFinding(
                "broken-routes-$from", CloudFindingCategory.Health, CloudSeverity.Medium,
                "${missing.mapNotNull { it.label }.joinToString()} ${if (missing.size == 1) "points" else "point"} at a host the cluster does not have",
                missing.joinToString("; ") { "${it.label} → ${it.host}${it.port?.let { port -> ":$port" }.orEmpty()}" } +
                    ". No Service by that name exists in the cluster reading${if (missing.any { '_' in it.host }) ", and a Kubernetes name cannot contain an underscore" else ""}, so ${source?.name ?: "the route"} cannot reach it.",
                listOfNotNull(source?.id) + missing.mapNotNull { reference -> reference.label?.let { "host:$it" } },
                "Point the route at the Service's real name, or remove it.",
            )
        }
    }

    private fun floatingImages(snapshot: CloudSnapshot): List<CloudFinding> {
        val floating = snapshot.resources.filter { resource ->
            resource.kind.workload && resource.observed && resource.list("images").let { images ->
                images.isNotEmpty() && images.any { image -> image.substringAfterLast('/').let { !it.contains(':') || it.endsWith(":latest") } && '@' !in image }
            }
        }
        if (floating.isEmpty()) return emptyList()
        return listOf(CloudFinding(
            "floating-images", CloudFindingCategory.Reliability, CloudSeverity.Low,
            "${floating.size} workload${plural(floating.size)} run an unpinned image",
            "${names(floating)} use a :latest or untagged image, so a restart can silently start a different version than the one you tested.",
            floating.map { it.id },
            "Pin each image to a version tag or a digest.",
        ))
    }

    private fun CloudResource.list(key: String): List<String> =
        attributes[key].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun names(resources: Collection<CloudResource>) = resources.joinToString { it.name }

    private fun openPorts(snapshot: CloudSnapshot): List<CloudFinding> {
        val openings = snapshot.resources.filter { rule ->
            rule.kind == CloudKind.FirewallRule && rule.attributes["disabled"] != "true" &&
                rule.attributes["direction"] != "EGRESS" && "0.0.0.0/0" in rule.list("sourceRanges")
        }.flatMap { rule ->
            val machines = snapshot.outgoing(rule.id).filter { it.kind == CloudRelationKind.Exposes }
                .mapNotNull { snapshot[it.target] }.filter { it.status != ResourceStatus.Down }
            rule.list("allowed").flatMap { allowed -> ports(allowed) }
                .mapNotNull { sensitivePorts[it] }
                .flatMap { port -> machines.map { Triple(port, rule, it) } }
        }
        return openings.groupBy { it.first }.map { (port, hits) ->
            val rules = hits.map { it.second }.distinctBy { it.id }
            val machines = hits.map { it.third }.distinctBy { it.id }
            val listening = machines.filter { machine ->
                port.port.toString() in machine.list("hostPorts") ||
                    (port.port == 6443 && machine.attributes["kubernetesNode"] == "true") ||
                    (port.port == 22 && machine.platform == CloudPlatform.GoogleCloud)
            }
            val severity = if (listening.isNotEmpty()) port.severity else CloudSeverity.Low
            CloudFinding(
                id = "open-port-${port.port}",
                category = CloudFindingCategory.Security,
                severity = severity,
                title = if (listening.isNotEmpty()) "${port.label.replaceFirstChar(Char::uppercase)} is open to the whole internet"
                    else "Port ${port.port} is open to the whole internet",
                detail = buildString {
                    append("${names(rules)} let${if (rules.size == 1) "s" else ""} any address reach port ${port.port} on ${names(machines)}. ")
                    append(if (listening.isNotEmpty()) "Something answers there: ${port.label}."
                        else "Nothing in the cluster listens on it, so the rule is only waiting for a mistake.")
                },
                resourceIds = rules.map { it.id } + machines.map { it.id },
                fix = "Narrow the rule's source ranges to the addresses that need it, or reach the port through the Cloudflare tunnel instead.",
            )
        }
    }

    private fun ports(allowed: String): List<Int> {
        val spec = allowed.substringAfter(':', "")
        if (spec.isBlank()) return emptyList()
        return spec.split(';').flatMap { part ->
            val range = part.split('-').mapNotNull { it.trim().toIntOrNull() }
            when (range.size) {
                1 -> listOf(range[0])
                2 -> if (range[1] - range[0] <= 64) (range[0]..range[1]).toList() else sensitivePorts.keys.filter { it in range[0]..range[1] }
                else -> emptyList()
            }
        }
    }

    private fun exposedHostnames(snapshot: CloudSnapshot): List<CloudFinding> =
        snapshot.resources.filter { it.kind == CloudKind.Hostname }.mapNotNull { host ->
            val origin = host.attributes["origin"] ?: return@mapNotNull null
            val port = origin.substringAfterLast(':').toIntOrNull() ?: return@mapNotNull null
            val label = internalOrigins[port] ?: return@mapNotNull null
            val tunnel = snapshot.outgoing(host.id).firstOrNull { it.kind == CloudRelationKind.Tunnels }?.let { snapshot[it.target] }
            CloudFinding(
                id = "exposed-host-${host.name}",
                category = CloudFindingCategory.Security,
                severity = if (port in setOf(7687, 9000, 8123, 5432)) CloudSeverity.High else CloudSeverity.Medium,
                title = "${host.name} is a public door to $label",
                detail = "Traffic to ${host.name} goes${tunnel?.let { " through the ${it.name} tunnel" }.orEmpty()} to $origin. " +
                    "Unless a Cloudflare Access policy guards this hostname, anyone who finds it reaches the service directly. " +
                    "Access policies are not readable with the current login, so this is not confirmed either way.",
                resourceIds = listOfNotNull(host.id, tunnel?.id) +
                    snapshot.outgoing(tunnel?.id.orEmpty()).map { it.target }.filter { target ->
                        snapshot[target]?.name?.let { origin.contains("//$it:") } == true
                    },
                fix = "Put the hostname behind Cloudflare Access, or drop the public route and reach the service through a Workers VPC service.",
            )
        }

    private fun unprotectedDisks(snapshot: CloudSnapshot): List<CloudFinding> {
        val bare = snapshot.resources.filter { disk ->
            disk.kind == CloudKind.Disk && disk.observed && disk.attributes["snapshots"] == "0" &&
                disk.attributes["snapshotSchedule"].isNullOrBlank()
        }
        if (bare.isEmpty()) return emptyList()
        val holding = bare.filter { disk -> snapshot.incoming(disk.id).any { it.kind == CloudRelationKind.StoresOn } }
        val volumes = holding.flatMap { disk -> snapshot.incoming(disk.id).filter { it.kind == CloudRelationKind.StoresOn }.mapNotNull { snapshot[it.source] } }
        val owners = volumes.flatMap { volume -> snapshot.incoming(volume.id).filter { it.kind == CloudRelationKind.Mounts }.mapNotNull { snapshot[it.source] } }
            .map { it.attributes["owner"] ?: it.name }.distinct()
        return listOf(CloudFinding(
            id = "no-snapshots",
            category = CloudFindingCategory.Reliability,
            severity = if (holding.isNotEmpty()) CloudSeverity.High else CloudSeverity.Medium,
            title = if (holding.isNotEmpty()) "Database disks have no disk snapshots" else "Disks have no snapshots",
            detail = buildString {
                append("${names(bare)} ${if (bare.size == 1) "has" else "have"} no snapshots and no snapshot schedule attached. ")
                if (owners.isNotEmpty()) append("They hold the data of ${owners.joinToString()}. ")
                append("This read covers disk snapshots. Application-level backup and restore coverage needs separate verification.")
            },
            resourceIds = bare.map { it.id } + volumes.map { it.id },
            fix = "Attach a daily snapshot schedule to these disks, and keep at least a week of snapshots.",
        ))
    }

    private fun pods(snapshot: CloudSnapshot): List<CloudFinding> = buildList {
        val now = snapshot.takenAtMillis
        val pods = snapshot.resources.filter { it.kind == CloudKind.Pod && it.observed }
        val failing = pods.filter { it.attributes["phase"] !in setOf("Running", "Succeeded") || it.attributes["ready"]?.let { ready -> ready.substringBefore('/') != ready.substringAfter('/') && it.attributes["phase"] == "Running" } == true }
        if (failing.isNotEmpty()) add(CloudFinding(
            "pods-not-ready", CloudFindingCategory.Health, CloudSeverity.High,
            "${failing.size} pod${plural(failing.size)} not ready",
            failing.joinToString("; ") { "${it.name}: ${it.attributes["phase"]} ${it.attributes["ready"].orEmpty()} ${it.statusDetail.orEmpty()}".trim() },
            failing.map { it.id },
            "Read the pod's events and logs in the Kubernetes tab.",
        ))
        val killed = pods.filter { it.attributes["lastTermination"] == "OOMKilled" }
        if (killed.isNotEmpty()) add(CloudFinding(
            "oom-killed", CloudFindingCategory.Reliability, CloudSeverity.High,
            "${names(killed)} ran out of memory",
            "The last container exit was OOMKilled: the process outgrew its memory limit and the kernel ended it.",
            killed.map { it.id },
            "Raise the memory limit, or find what grows; the Kubernetes tab shows usage against the limit.",
        ))
        val restarting = pods.filter { pod ->
            (pod.attributes["restarts"]?.toIntOrNull() ?: 0) > 0 &&
                (pod.attributes["lastRestartAtMillis"]?.toLongOrNull()?.let { now - it < DayMillis } ?: false) && pod !in killed
        }
        if (restarting.isNotEmpty()) add(CloudFinding(
            "restarts", CloudFindingCategory.Reliability, CloudSeverity.Medium,
            "${restarting.size} pod${plural(restarting.size)} restarted in the last day",
            restarting.joinToString("; ") { "${it.name}: ${it.attributes["restarts"]} restarts, last exit ${it.attributes["lastTermination"] ?: "unknown"}" },
            restarting.map { it.id },
            "Open the pod's previous logs to see why the container exited.",
        ))
    }

    private fun tunnels(snapshot: CloudSnapshot): List<CloudFinding> = buildList {
        val tunnels = snapshot.resources.filter { it.kind == CloudKind.Tunnel && it.observed }
        val (used, unused) = tunnels.partition { tunnel -> snapshot.incoming(tunnel.id).isNotEmpty() || snapshot.outgoing(tunnel.id).isNotEmpty() }
        used.filter { it.status == ResourceStatus.Down }.forEach { tunnel ->
            val routed = snapshot.incoming(tunnel.id).mapNotNull { snapshot[it.source] }
            add(CloudFinding(
                "tunnel-down-${tunnel.name}", CloudFindingCategory.Health, CloudSeverity.High,
                "${names(routed)} ${if (routed.size == 1) "does" else "do"} not answer: the ${tunnel.name} tunnel has no connector online",
                "Everything routed through ${tunnel.name} fails until a cloudflared connector for it comes back." +
                    (tunnel.attributes["offlineSince"] ?: tunnel.attributes["lastActive"])?.let { " Offline since ${it.take(10)}." }.orEmpty(),
                listOf(tunnel.id) + routed.map { it.id },
                "Start the connector, or delete the tunnel and its routes if that machine is retired.",
            ))
        }
        val stale = unused.filter { it.status == ResourceStatus.Down }
        if (stale.isNotEmpty()) add(CloudFinding(
            "stale-tunnels", CloudFindingCategory.Cost, CloudSeverity.Note,
            "${stale.size} tunnel${plural(stale.size)} with no connector and nothing routed",
            "${names(stale)} ${if (stale.size == 1) "has" else "have"} no active connection and no hostname or VPC service uses ${if (stale.size == 1) "it" else "them"}.",
            stale.map { it.id },
            "Delete the tunnels you no longer run.",
        ))
    }

    private fun machines(snapshot: CloudSnapshot): List<CloudFinding> = buildList {
        val machines = snapshot.resources.filter { it.kind == CloudKind.Machine && it.observed }
        machines.forEach { machine ->
            val memory = machine.metric("memoryPercent")?.value
            val allocatable = machine.metric("memoryAllocatable")?.value
            val limits = snapshot.incoming(machine.id).filter { it.kind == CloudRelationKind.RunsOn }
                .mapNotNull { snapshot[it.source]?.attributes?.get("memoryLimit")?.toDoubleOrNull() }.sum()
            if (allocatable != null && limits > allocatable * 1.05) add(CloudFinding(
                "overcommit-${machine.name}", CloudFindingCategory.Reliability, CloudSeverity.Medium,
                "Memory limits on ${machine.name} add up to ${(limits / allocatable * 100).toInt()}% of what it can give",
                "The pods may use up to ${formatBytes(limits)} between them, and the machine has ${formatBytes(allocatable)} for pods. " +
                    "If several grow at once, the kernel kills one instead of the kubelet evicting it gracefully.",
                listOf(machine.id), "Lower the limits of the workloads that never reach them, or give the machine more memory.",
            ))
            if (memory != null && memory >= 85) add(CloudFinding(
                "memory-${machine.name}", CloudFindingCategory.Reliability,
                if (memory >= 95) CloudSeverity.High else CloudSeverity.Medium,
                "${machine.name} is using ${memory.toInt()}% of its memory",
                "Close to the limit, the kubelet starts evicting the lowest-priority pods.",
                listOf(machine.id), "Move a workload off this machine or give it more memory.",
            ))
        }
        val hosting = machines.filter { machine -> snapshot.incoming(machine.id).count { it.kind == CloudRelationKind.RunsOn } > 0 }
        if (hosting.size == 1) {
            val machine = hosting.single()
            val pods = snapshot.incoming(machine.id).count { it.kind == CloudRelationKind.RunsOn }
            add(CloudFinding(
                "single-machine", CloudFindingCategory.Reliability, CloudSeverity.Low,
                "The whole cluster is one machine",
                "All $pods pods run on ${machine.name}. A reboot or a zone outage takes every one of them down at once.",
                listOf(machine.id), "Accept it knowingly, or add a second node for the workloads that serve users.",
            ))
        }
    }

    private fun singleReplicas(snapshot: CloudSnapshot): List<CloudFinding> {
        val reached = snapshot.resources.filter { workload ->
            workload.kind.workload && workload.attributes["replicas"] == "1" &&
                snapshot.incoming(workload.id).any { selector ->
                    selector.kind == CloudRelationKind.Selects &&
                        snapshot.incoming(selector.source).any { it.kind == CloudRelationKind.Tunnels || it.kind == CloudRelationKind.Connects }
                }
        }
        if (reached.isEmpty()) return emptyList()
        return listOf(CloudFinding(
            "single-replica", CloudFindingCategory.Reliability, CloudSeverity.Low,
            "${names(reached)} ${if (reached.size == 1) "runs" else "run"} a single replica behind public traffic",
            "Every restart or rollout of ${if (reached.size == 1) "it" else "them"} is a short outage for the callers that reach ${if (reached.size == 1) "it" else "them"} through the edge.",
            reached.map { it.id },
            "Run two replicas, or accept the gap during rollouts.",
        ))
    }

    private fun workerHealth(snapshot: CloudSnapshot): List<CloudFinding> = snapshot.resources
        .filter { it.kind == CloudKind.Worker }
        .mapNotNull { worker ->
            val requests = worker.metric("requests")?.value ?: return@mapNotNull null
            val errors = worker.metric("errors")?.value ?: return@mapNotNull null
            if (requests < 50 || errors / requests < 0.01) return@mapNotNull null
            val rate = errors / requests * 100
            CloudFinding(
                "worker-errors-${worker.name}", CloudFindingCategory.Health,
                if (rate >= 5) CloudSeverity.High else CloudSeverity.Medium,
                "${worker.name} fails ${formatPercent(rate)} of requests",
                "${errors.toLong()} of ${requests.toLong()} invocations ended in an error in the last 24 hours.",
                listOf(worker.id), "Open the worker in the Cloudflare tab and read its recent errors.",
            )
        }

    private fun drift(snapshot: CloudSnapshot): List<CloudFinding> = buildList {
        val cloudflareRead = snapshot.readings.any { it.platform == CloudPlatform.Cloudflare && it.health.status != ResourceStatus.Down }
        val kubernetesRead = snapshot.readings.any { it.platform == CloudPlatform.Kubernetes && it.health.status != ResourceStatus.Down }
        if (cloudflareRead) {
            val missing = snapshot.resources.filter { it.kind == CloudKind.Worker && it.declared && !it.observed }
            if (missing.isNotEmpty()) add(CloudFinding(
                "not-deployed", CloudFindingCategory.Drift, CloudSeverity.Medium,
                "${missing.size} worker${plural(missing.size)} declared but not deployed",
                "${names(missing)} ${if (missing.size == 1) "has" else "have"} a configuration in the repository and no script in the Cloudflare account.",
                missing.map { it.id }, "Deploy ${if (missing.size == 1) "it" else "them"}, or delete the configuration.",
            ))
            snapshot.resources.filter { it.kind == CloudKind.Worker && it.declared && it.observed }.forEach { worker ->
                val bindings = snapshot.outgoing(worker.id).filter { it.kind == CloudRelationKind.Binds || it.kind == CloudRelationKind.Calls }
                val deployedTargets = bindings.filter { it.observed }.mapTo(mutableSetOf()) { it.kind to it.target }
                val declaredTargets = bindings.filter { it.declared }.mapTo(mutableSetOf()) { it.kind to it.target }
                val declaredOnly = bindings.filter { it.declared && !it.observed && (it.kind to it.target) !in deployedTargets }
                val deployedOnly = bindings.filter { it.observed && !it.declared && (it.kind to it.target) !in declaredTargets }
                if (declaredOnly.isEmpty() && deployedOnly.isEmpty()) return@forEach
                add(CloudFinding(
                    "bindings-${worker.name}", CloudFindingCategory.Drift, CloudSeverity.Medium,
                    "${worker.name}'s deployed bindings differ from the repository",
                    buildString {
                        if (declaredOnly.isNotEmpty()) append("Only in the repository: ${declaredOnly.joinToString { binding(snapshot, it) }}. ")
                        if (deployedOnly.isNotEmpty()) append("Only in the deployed script: ${deployedOnly.joinToString { binding(snapshot, it) }}.")
                    }.trim(),
                    listOf(worker.id) + (declaredOnly + deployedOnly).map { it.target },
                    "Deploy the worker from the repository, or bring the configuration in line with what runs.",
                ))
            }
            val undeclared = snapshot.resources.filter { resource ->
                resource.kind == CloudKind.Worker && resource.observed && !resource.declared &&
                    snapshot.incoming(resource.id).any { it.declared }
            }
            if (undeclared.isNotEmpty()) add(CloudFinding(
                "undeclared-dependency", CloudFindingCategory.Drift, CloudSeverity.Low,
                "Declared workers depend on ${names(undeclared)}, which no configuration here declares",
                undeclared.joinToString("; ") { resource ->
                    "${snapshot.incoming(resource.id).filter { it.declared }.mapNotNull { snapshot[it.source]?.name }.distinct().joinToString()} bind ${resource.name}, " +
                        "which is deployed from somewhere else"
                },
                undeclared.map { it.id } + undeclared.flatMap { resource -> snapshot.incoming(resource.id).filter { it.declared }.map { it.source } },
                "Bring its configuration into the repository so a deploy from here can rebuild it.",
            ))
        }
        val dangling = snapshot.resources.filter { it.placeholder }
        if (dangling.isNotEmpty()) {
            val live = dangling.filter { target -> snapshot.incoming(target.id).any { it.observed } }
            add(CloudFinding(
                "dangling", CloudFindingCategory.Drift, if (live.isNotEmpty()) CloudSeverity.High else CloudSeverity.Medium,
                if (live.isNotEmpty()) "Deployed workers bind ${live.size} ${if (live.size == 1) "thing" else "things"} that no longer exist"
                else "${dangling.size} binding target${plural(dangling.size)} exist nowhere",
                dangling.joinToString("; ") { target ->
                    val binders = snapshot.incoming(target.id)
                    "${binders.mapNotNull { it.label }.distinct().joinToString("/").ifBlank { target.kind.label }} (${target.kind.label} ${target.name.take(12)}) is bound by " +
                        binders.mapNotNull { snapshot[it.source]?.name }.distinct().joinToString()
                } + ". Neither the repository nor the account has ${if (dangling.size == 1) "it" else "them"}, so the call fails at runtime.",
                dangling.map { it.id } + dangling.flatMap { resource -> snapshot.incoming(resource.id).map { it.source } },
                "Remove the stale bindings and redeploy, or recreate what they point at.",
            ))
        }
        if (kubernetesRead) {
            val unapplied = snapshot.resources.filter { it.platform == CloudPlatform.Kubernetes && it.kind.workload && it.declared && !it.observed }
            if (unapplied.isNotEmpty()) add(CloudFinding(
                "not-applied", CloudFindingCategory.Drift, CloudSeverity.Medium,
                "${unapplied.size} manifest${plural(unapplied.size)} not applied to the cluster",
                "${names(unapplied)} ${if (unapplied.size == 1) "is" else "are"} declared in the repository's manifests and missing from the cluster.",
                unapplied.map { it.id }, "Apply the manifests, or delete the ones you retired.",
            ))
        }
    }

    private fun binding(snapshot: CloudSnapshot, relation: CloudRelation): String =
        "${relation.label ?: "binding"} → ${snapshot[relation.target]?.name ?: relation.target.substringAfterLast(':')}"

    private fun unused(snapshot: CloudSnapshot): List<CloudFinding> = buildList {
        val stores = setOf(CloudKind.R2, CloudKind.D1, CloudKind.Kv, CloudKind.Queue, CloudKind.Hyperdrive, CloudKind.VpcService)
        val orphans = snapshot.resources.filter { resource ->
            resource.kind in stores && resource.observed && snapshot.incoming(resource.id).none { it.kind == CloudRelationKind.Binds }
        }
        if (orphans.isNotEmpty()) add(CloudFinding(
            "unbound", CloudFindingCategory.Cost, CloudSeverity.Note,
            "${orphans.size} store${plural(orphans.size)} no worker binds",
            "${names(orphans)}. Something outside the account may still use ${if (orphans.size == 1) "it" else "them"}, such as a backup job or a script.",
            orphans.map { it.id }, "Delete what nothing uses; check the bucket or database contents first.",
        ))
        val idle = snapshot.resources.filter { worker ->
            worker.kind == CloudKind.Worker && worker.observed && worker.metric("requests")?.value == 0.0 &&
                worker.attributes["cron"].isNullOrBlank()
        }
        if (idle.isNotEmpty()) add(CloudFinding(
            "idle-workers", CloudFindingCategory.Cost, CloudSeverity.Note,
            "${idle.size} worker${plural(idle.size)} served nothing in the last day",
            names(idle), idle.map { it.id }, "Retire the scripts nobody calls.",
        ))
        snapshot.resources.filter { it.kind == CloudKind.ImageRepository }.forEach { repository ->
            val bytes = repository.metric("size")?.value ?: return@forEach
            if (bytes < 5e9) return@forEach
            add(CloudFinding(
                "registry-${repository.name}", CloudFindingCategory.Cost, CloudSeverity.Low,
                "${repository.name} holds ${formatBytes(bytes)} of images",
                "Every pushed tag stays until something deletes it, and registry storage is billed by the gigabyte.",
                listOf(repository.id), "Add a cleanup policy that keeps the last few tags per image.",
            ))
        }
    }

    private fun observability(snapshot: CloudSnapshot): List<CloudFinding> {
        val silent = snapshot.resources.filter { it.kind == CloudKind.Worker && it.attributes["logs"] == "false" }
        if (silent.isEmpty()) return emptyList()
        return listOf(CloudFinding(
            "no-logs", CloudFindingCategory.Observability, CloudSeverity.Low,
            "${silent.size} worker${plural(silent.size)} keep no logs",
            "Workers Logs is off for ${names(silent)}, so a failure there leaves no record to read later.",
            silent.map { it.id }, "Set observability.enabled in their wrangler configuration.",
        ))
    }

    private fun plural(count: Int) = if (count == 1) "" else "s"

    fun formatPercent(value: Double): String = if (value >= 10) "${value.toInt()}%" else "${(value * 10).toInt() / 10.0}%"

    fun formatBytes(bytes: Double): String = when {
        bytes >= 1024.0 * 1024 * 1024 -> "${(bytes / (1024.0 * 1024 * 1024) * 10).toInt() / 10.0} GiB"
        bytes >= 1024.0 * 1024 -> "${(bytes / (1024.0 * 1024) * 10).toInt() / 10.0} MiB"
        bytes >= 1024 -> "${(bytes / 1024 * 10).toInt() / 10.0} KiB"
        else -> "${bytes.toInt()} B"
    }
}
