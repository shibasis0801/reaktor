package dev.shibasis.reaktor.tooling.cloud

import kotlinx.serialization.json.JsonObject

internal class CloudflareInventoryBuilder(
    private val accountId: String,
    private val dash: String,
    private val source: String,
    private val readAt: Long,
) {
    private val resources = linkedMapOf<String, CloudResource>()
    private val relations = mutableListOf<CloudRelation>()
    private val references = mutableListOf<CloudReference>()
    private val changes = mutableListOf<CloudChange>()
    private val zoneNames = mutableListOf<String>()

    private fun evidence(detail: String) = CloudEvidence(CloudSource.CloudflareApi, detail, readAt)

    fun resources(): List<CloudResource> = resources.values.toList()
    fun relations(): List<CloudRelation> = relations.distinctBy { it.id }
    fun references(): List<CloudReference> = references
    fun changes(): List<CloudChange> = changes

    private fun add(resource: CloudResource) {
        resources[resource.id] = resources[resource.id]?.let { existing ->
            existing.copy(attributes = existing.attributes + resource.attributes, evidence = (existing.evidence + resource.evidence).distinct())
        } ?: resource
    }

    private fun relate(source: String, target: String, kind: CloudRelationKind, label: String? = null, attributes: Map<String, String> = emptyMap(), detail: String) {
        relations += CloudRelation(source, target, kind, label, attributes, listOf(evidence(detail)))
    }

    fun zones(zones: List<JsonObject>) = zones.forEach { zone ->
        val name = zone.text("name") ?: return@forEach
        zoneNames += name
        add(CloudResource(
            id = "cf:zone:$name", kind = CloudKind.Zone, platform = CloudPlatform.Cloudflare, name = name,
            status = if (zone.text("status") == "active") ResourceStatus.Healthy else ResourceStatus.Degraded,
            statusDetail = zone.text("status"),
            attributes = mapOfNotNull("plan" to zone.obj("plan").text("name"), "status" to zone.text("status")),
            evidence = listOf(evidence("zones")),
            consoleUrl = "$dash/$name",
        ))
    }

    private fun hostname(host: String, via: String, origin: String? = null, status: ResourceStatus = ResourceStatus.Healthy) {
        val clean = host.trim().removeSuffix("/*").removeSuffix("*").removeSuffix("/")
        val id = "host:$clean"
        add(CloudResource(
            id = id, kind = CloudKind.Hostname, platform = CloudPlatform.Cloudflare, name = clean,
            status = status,
            attributes = mapOfNotNull("via" to via, "origin" to origin),
            evidence = listOf(evidence(via)),
        ))
        zoneNames.filter { clean == it || clean.endsWith(".$it") || clean.substringBefore('/').endsWith(".$it") || clean.substringBefore('/') == it }
            .maxByOrNull { it.length }
            ?.let { relate("cf:zone:$it", id, CloudRelationKind.Contains, detail = "zones") }
    }

    fun workers(scripts: List<JsonObject>, settings: Map<String, JsonObject>, traffic: Map<String, WorkerTraffic>) = scripts.forEach { script ->
        val name = script.text("id") ?: return@forEach
        val config = settings[name] ?: JsonObject(emptyMap())
        val usage = traffic[name]
        val workerId = "cf:worker:$name"
        val secrets = config.objects("bindings").filter { it.text("type") == "secret_text" }.mapNotNull { it.text("name") }
        val vars = config.objects("bindings").filter { it.text("type") in setOf("plain_text", "json") }.mapNotNull { it.text("name") }
        val status = when {
            usage?.requests == null || usage.errors == null -> ResourceStatus.Unknown
            usage.requests == 0.0 -> ResourceStatus.Idle
            usage.requests >= 50 && usage.errors / usage.requests >= 0.05 -> ResourceStatus.Degraded
            else -> ResourceStatus.Healthy
        }
        add(CloudResource(
            id = workerId, kind = CloudKind.Worker, platform = CloudPlatform.Cloudflare, name = name,
            status = status,
            statusDetail = usage?.let { "${it.requests?.let { value -> "${value.toLong()} requests" } ?: "requests not read"} · ${it.errors?.let { value -> "${value.toLong()} errors" } ?: "errors not read"} in 24 h" } ?: "traffic not read",
            attributes = mapOfNotNull(
                "created" to script.text("created_on"),
                "modified" to script.text("modified_on"),
                "usageModel" to (config.text("usage_model") ?: script.text("usage_model")),
                "compatibilityDate" to config.text("compatibility_date"),
                "compatibilityFlags" to config.strings("compatibility_flags").joinToString().ifBlank { null },
                "logs" to (config.obj("observability").flag("enabled") ?: false).toString(),
                "logpush" to config.flag("logpush")?.toString(),
                "placement" to config.obj("placement").text("mode"),
                "handlers" to script.strings("handlers").joinToString().ifBlank { null },
                "secrets" to secrets.joinToString().ifBlank { null },
                "vars" to vars.joinToString().ifBlank { null },
                "hasAssets" to script.flag("has_assets")?.toString(),
                "tailConsumers" to script.objects("tail_consumers").mapNotNull { it.text("service") }.joinToString().ifBlank { null },
                "hourlyRequests" to usage?.hourly?.joinToString(",") { "${it.first}:${it.second.toLong()}:${it.third.toLong()}" },
            ),
            metrics = buildList {
                usage?.let {
                    it.requests?.let { value -> add(CloudMetric("requests", "Requests", value, CloudUnit.Count, "24 h")) }
                    it.errors?.let { value -> add(CloudMetric("errors", "Errors", value, CloudUnit.Count, "24 h")) }
                    it.subrequests?.let { value -> add(CloudMetric("subrequests", "Subrequests", value, CloudUnit.Count, "24 h")) }
                    it.cpuP50?.let { value -> add(CloudMetric("cpuP50", "CPU p50", value, CloudUnit.Micros, "24 h")) }
                    it.cpuP99?.let { value -> add(CloudMetric("cpuP99", "CPU p99", value, CloudUnit.Micros, "24 h")) }
                }
            },
            evidence = listOf(evidence("workers/scripts")),
            consoleUrl = "$dash/workers/services/view/$name/production",
            createdAtMillis = instantMillis(script.text("created_on")),
        ))
        config.objects("bindings").forEach { binding -> bind(workerId, name, binding) }
        if (script.flag("has_assets") == true && config.objects("bindings").none { it.text("type") == "assets" }) {
            add(CloudResource("cf:assets:$name", CloudKind.StaticAssets, CloudPlatform.Cloudflare, "$name assets", ResourceStatus.Healthy,
                evidence = listOf(evidence("workers/scripts has_assets"))))
            relate(workerId, "cf:assets:$name", CloudRelationKind.Binds, detail = "workers/scripts has_assets")
        }
        script.objects("tail_consumers").mapNotNull { it.text("service") }.forEach { consumer ->
            relate(workerId, "cf:worker:$consumer", CloudRelationKind.Sends, "tail", targetHint(CloudKind.Worker, consumer), "tail consumers")
        }
    }

    private fun targetHint(kind: CloudKind, name: String) = mapOf("targetKind" to kind.name, "targetName" to name, "targetPlatform" to CloudPlatform.Cloudflare.name)

    private fun bind(workerId: String, workerName: String, binding: JsonObject) {
        val label = binding.text("name")
        val detail = "workers/scripts/$workerName/settings"
        when (binding.text("type")) {
            "d1" -> binding.text("id")?.let { relate(workerId, "cf:d1:$it", CloudRelationKind.Binds, label, targetHint(CloudKind.D1, it), detail) }
            "r2_bucket" -> binding.text("bucket_name")?.let { relate(workerId, "cf:r2:$it", CloudRelationKind.Binds, label, targetHint(CloudKind.R2, it), detail) }
            "kv_namespace" -> binding.text("namespace_id")?.let { relate(workerId, "cf:kv:$it", CloudRelationKind.Binds, label, targetHint(CloudKind.Kv, it), detail) }
            "queue" -> binding.text("queue_name")?.let { relate(workerId, "cf:queue:$it", CloudRelationKind.Binds, label, targetHint(CloudKind.Queue, it), detail) }
            "hyperdrive" -> binding.text("id")?.let { relate(workerId, "cf:hyperdrive:$it", CloudRelationKind.Binds, label, targetHint(CloudKind.Hyperdrive, it), detail) }
            "vpc_service" -> binding.text("service_id")?.let { relate(workerId, "cf:vpc:$it", CloudRelationKind.Binds, label, targetHint(CloudKind.VpcService, it), detail) }
            "service" -> binding.text("service")?.let { relate(workerId, "cf:worker:$it", CloudRelationKind.Calls, label, targetHint(CloudKind.Worker, it) + mapOfNotNull("entrypoint" to binding.text("entrypoint")), detail) }
            "durable_object_namespace" -> binding.text("class_name")?.let { className ->
                val host = binding.text("script_name") ?: workerName
                relate(workerId, "cf:do:$className@$host", CloudRelationKind.Binds, label, targetHint(CloudKind.DurableObject, className), detail)
            }
            "ai" -> {
                add(CloudResource("cf:ai", CloudKind.WorkersAi, CloudPlatform.Cloudflare, "Workers AI", ResourceStatus.Healthy, evidence = listOf(evidence(detail))))
                relate(workerId, "cf:ai", CloudRelationKind.Binds, label, detail = detail)
            }
            "browser", "browser_rendering" -> {
                add(CloudResource("cf:browser", CloudKind.BrowserRendering, CloudPlatform.Cloudflare, "Browser Rendering", ResourceStatus.Healthy, evidence = listOf(evidence(detail))))
                relate(workerId, "cf:browser", CloudRelationKind.Binds, label, detail = detail)
            }
            "workflow" -> binding.text("workflow_name")?.let { workflow ->
                add(CloudResource("cf:workflow:$workflow", CloudKind.Workflow, CloudPlatform.Cloudflare, workflow, ResourceStatus.Healthy,
                    attributes = mapOfNotNull("class" to binding.text("class_name"), "script" to binding.text("script_name")),
                    evidence = listOf(evidence(detail))))
                relate(workerId, "cf:workflow:$workflow", CloudRelationKind.Binds, label, detail = detail)
            }
            "assets" -> {
                add(CloudResource("cf:assets:$workerName", CloudKind.StaticAssets, CloudPlatform.Cloudflare, "$workerName assets", ResourceStatus.Healthy, evidence = listOf(evidence(detail))))
                relate(workerId, "cf:assets:$workerName", CloudRelationKind.Binds, label, detail = detail)
            }
        }
    }

    fun deployments(byWorker: Map<String, List<JsonObject>>) = byWorker.forEach { (name, list) ->
        list.forEach { deployment ->
            val at = instantMillis(deployment.text("created_on")) ?: return@forEach
            val versions = deployment.objects("versions")
            changes += CloudChange(
                atMillis = at,
                resourceId = "cf:worker:$name",
                kind = CloudChangeKind.Deployed,
                title = if (versions.size > 1) "Deployed $name as a gradual rollout" else "Deployed $name",
                actor = deployment.text("author_email"),
                detail = listOfNotNull(
                    deployment.text("source")?.let { "from $it" },
                    deployment.obj("annotations").text("workers/message"),
                    versions.takeIf { it.size > 1 }?.joinToString { "${it.number("percentage")?.toInt()}% ${it.text("version_id")?.take(8)}" },
                ).joinToString(" · ").ifBlank { null },
            )
        }
        list.maxByOrNull { instantMillis(it.text("created_on")) ?: 0L }?.let { latest ->
            resources["cf:worker:$name"]?.let { worker ->
                resources[worker.id] = worker.copy(attributes = worker.attributes + mapOfNotNull(
                    "deployedAt" to latest.text("created_on"),
                    "deployedBy" to latest.text("author_email"),
                    "deploySource" to latest.text("source"),
                    "deployments" to list.size.toString(),
                    "activeVersion" to latest.objects("versions").singleOrNull()?.takeIf { it.number("percentage") == 100.0 }?.text("version_id"),
                ))
            }
        }
    }

    fun domains(domains: List<JsonObject>) = domains.forEach { domain ->
        val host = domain.text("hostname") ?: return@forEach
        val service = domain.text("service") ?: return@forEach
        hostname(host, "custom domain")
        relate("host:$host", "cf:worker:$service", CloudRelationKind.Routes, "custom domain", targetHint(CloudKind.Worker, service), "workers/domains")
    }

    fun routes(zone: JsonObject, routes: List<JsonObject>) = routes.forEach { route ->
        val pattern = route.text("pattern") ?: return@forEach
        val script = route.text("script") ?: return@forEach
        val host = pattern.substringBefore('/').removePrefix("*.").removePrefix("*")
        if (host.isBlank()) return@forEach
        hostname(host, "route")
        relate("host:$host", "cf:worker:$script", CloudRelationKind.Routes, pattern, targetHint(CloudKind.Worker, script), "zones/${zone.text("name")}/workers/routes")
    }

    fun databases(databases: List<JsonObject>) = databases.forEach { database ->
        val uuid = database.text("uuid") ?: return@forEach
        val name = database.text("name") ?: uuid
        add(CloudResource(
            id = "cf:d1:$uuid", kind = CloudKind.D1, platform = CloudPlatform.Cloudflare, name = name,
            status = ResourceStatus.Healthy,
            attributes = mapOfNotNull("uuid" to uuid, "created" to database.text("created_at"), "region" to database.text("running_in_region")),
            metrics = listOfNotNull(database.number("file_size")?.let { CloudMetric("size", "Size", it, CloudUnit.Bytes) }),
            evidence = listOf(evidence("d1/database")),
            consoleUrl = "$dash/workers/d1/databases/$uuid",
            createdAtMillis = instantMillis(database.text("created_at")),
        ))
    }

    fun buckets(buckets: List<JsonObject>) = buckets.forEach { bucket ->
        val name = bucket.text("name") ?: return@forEach
        add(CloudResource(
            id = "cf:r2:$name", kind = CloudKind.R2, platform = CloudPlatform.Cloudflare, name = name,
            status = ResourceStatus.Healthy,
            attributes = mapOfNotNull("location" to bucket.text("location"), "storageClass" to bucket.text("storage_class"), "created" to bucket.text("creation_date")),
            evidence = listOf(evidence("r2/buckets")),
            consoleUrl = "$dash/r2/default/buckets/$name",
            createdAtMillis = instantMillis(bucket.text("creation_date")),
        ))
    }

    fun namespaces(namespaces: List<JsonObject>) = namespaces.forEach { namespace ->
        val id = namespace.text("id") ?: return@forEach
        add(CloudResource(
            id = "cf:kv:$id", kind = CloudKind.Kv, platform = CloudPlatform.Cloudflare, name = namespace.text("title") ?: id,
            status = ResourceStatus.Healthy,
            attributes = mapOf("id" to id),
            evidence = listOf(evidence("storage/kv/namespaces")),
            consoleUrl = "$dash/workers/kv/namespaces/$id",
        ))
    }

    fun queues(queues: List<JsonObject>, metrics: Map<String, JsonObject?> = emptyMap()) = queues.forEach { queue ->
        val name = queue.text("queue_name") ?: return@forEach
        val queueId = "cf:queue:$name"
        val metric = metrics[queue.text("queue_id")]
        val backlog = metric?.long("backlog_count")?.takeIf { it >= 0 }
        val bytes = metric?.long("backlog_bytes")?.takeIf { it >= 0 }
        add(CloudResource(
            id = queueId, kind = CloudKind.Queue, platform = CloudPlatform.Cloudflare, name = name,
            status = ResourceStatus.Unknown, statusDetail = "Queue configuration observed; delivery health is not inferred from backlog",
            attributes = mapOfNotNull("id" to queue.text("queue_id"), "engine" to "Queues",
                "producers" to queue.long("producers_total_count")?.toString(),
                "consumers" to queue.long("consumers_total_count")?.toString(), "created" to queue.text("created_on"),
                "backlogRecords" to backlog?.toString(), "backlogBytes" to bytes?.toString(),
                "oldestMessageAt" to metric?.long("oldest_message_timestamp_ms")?.takeIf { it > 0 }?.toString(),
                "metricCoverage" to if (metric == null) "unavailable" else "approximate",
                "deliveryPaused" to queue.obj("settings").flag("delivery_paused")?.toString()),
            metrics = buildList {
                backlog?.let { add(CloudMetric("backlog", "Approximate backlog", it.toDouble(), CloudUnit.Count)) }
                bytes?.let { add(CloudMetric("backlogBytes", "Approximate backlog bytes", it.toDouble(), CloudUnit.Bytes)) }
            },
            evidence = listOf(evidence("queues")) + if (metric == null) emptyList() else listOf(evidence("queues/metrics · best effort")),
            consoleUrl = "$dash/workers/queues/view/${queue.text("queue_id")}",
        ))
        queue.objects("producers").forEach { producer ->
            when (producer.text("type")) {
                "worker" -> producer.text("script")?.let { worker ->
                    relate("cf:worker:$worker", queueId, CloudRelationKind.Sends, "producer", targetHint(CloudKind.Queue, name), "queues producers")
                }
                "r2_bucket" -> producer.text("bucket_name")?.let { bucket ->
                    relate("cf:r2:$bucket", queueId, CloudRelationKind.Sends, "producer", targetHint(CloudKind.Queue, name), "queues producers")
                }
            }
        }
        queue.objects("consumers").mapNotNull { it.text("script_name") ?: it.text("script") ?: it.text("service") }.forEach { consumer ->
            relate(queueId, "cf:worker:$consumer", CloudRelationKind.Sends, "consumer", targetHint(CloudKind.Worker, consumer), "queues consumers")
        }
    }

    fun k2(streams: List<JsonObject>, subscriptions: Map<String, List<JsonObject>?> = emptyMap()) = streams.forEach { stream ->
        val id = stream.text("id") ?: return@forEach
        val name = stream.text("name") ?: return@forEach
        val streamId = "cf:k2:$id"
        add(CloudResource(
            id = streamId, kind = CloudKind.Messaging, platform = CloudPlatform.Cloudflare, name = name,
            status = ResourceStatus.Unknown, statusDetail = "K2 stream configuration observed; runtime health is not reported",
            attributes = mapOfNotNull("id" to id, "engine" to "K2", "retentionSeconds" to stream.long("retention_seconds")?.toString(),
                "httpEnabled" to stream.obj("http").flag("enabled")?.toString(),
                "bindingEnabled" to stream.obj("worker_binding").flag("enabled")?.toString(),
                "subscriptionCoverage" to if (subscriptions[id] == null) "unavailable" else "observed"),
            evidence = listOf(evidence("k2/streams")),
            createdAtMillis = instantMillis(stream.text("created_at")),
        ))
        subscriptions[id].orEmpty().forEach { subscription ->
            val subscriptionId = subscription.text("id") ?: return@forEach
            val lag = subscription.obj("lag")
            val lagStatus = lag.text("status") ?: "unavailable"
            val records = lag.text("records")?.takeIf { lagStatus == "available" && it.matches(Regex("0|[1-9][0-9]*")) }
            val resourceId = "$streamId/subscription:$subscriptionId"
            add(CloudResource(
                id = resourceId, kind = CloudKind.Messaging, platform = CloudPlatform.Cloudflare,
                name = subscription.text("name") ?: subscriptionId, status = ResourceStatus.Unknown,
                statusDetail = if (records == null) "Consumer lag $lagStatus" else "Consumer position observed; lag includes in-flight records",
                attributes = mapOfNotNull("id" to subscriptionId, "engine" to "K2 subscription", "streamId" to id,
                    "lagStatus" to lagStatus, "lagRecords" to records, "startAt" to subscription.obj("start_at").text("type")),
                evidence = listOf(evidence("k2/streams/$id/subscriptions")),
                createdAtMillis = instantMillis(subscription.text("created_at")),
            ))
            relate(streamId, resourceId, CloudRelationKind.Sends, "subscription", targetHint(CloudKind.Messaging, subscription.text("name") ?: subscriptionId), "k2 subscriptions")
        }
    }

    fun hyperdrives(configs: List<JsonObject>) = configs.forEach { config ->
        val id = config.text("id") ?: return@forEach
        val origin = config.obj("origin")
        val host = origin.text("host")
        val hyperdriveId = "cf:hyperdrive:$id"
        add(CloudResource(
            id = hyperdriveId, kind = CloudKind.Hyperdrive, platform = CloudPlatform.Cloudflare, name = config.text("name") ?: id,
            status = ResourceStatus.Healthy,
            attributes = mapOfNotNull("id" to id, "origin" to host?.let { "$it:${origin.text("port") ?: "5432"}/${origin.text("database").orEmpty()}" },
                "caching" to if (config.obj("caching").flag("disabled") == true) "off" else "on"),
            evidence = listOf(evidence("hyperdrive/configs")),
            consoleUrl = "$dash/workers/hyperdrive/$id",
        ))
        if (host != null) {
            val project = origin.text("user")?.substringAfter('.', "")?.takeIf { it.isNotBlank() }
                ?: Regex("""db\.([a-z0-9]+)\.supabase\.co""").find(host)?.groupValues?.get(1)
            val supabase = host.contains("supabase")
            val postgresId = "pg:${project ?: host}"
            add(CloudResource(
                id = postgresId, kind = CloudKind.Postgres,
                platform = if (supabase) CloudPlatform.Supabase else CloudPlatform.Cloudflare,
                name = if (supabase && project != null) "Supabase $project" else host,
                status = ResourceStatus.Unknown,
                statusDetail = "seen through Hyperdrive; the database itself is not read here",
                attributes = mapOfNotNull("host" to host, "project" to project, "database" to origin.text("database")),
                evidence = listOf(evidence("origin of Hyperdrive ${config.text("name")}")),
                consoleUrl = if (supabase && project != null) "https://supabase.com/dashboard/project/$project" else null,
            ))
            relate(hyperdriveId, postgresId, CloudRelationKind.Connects, host, detail = "hyperdrive/configs")
        }
    }

    fun durableObjects(namespaces: List<JsonObject>) = namespaces.forEach { namespace ->
        val className = namespace.text("class") ?: return@forEach
        val script = namespace.text("script") ?: return@forEach
        val id = "cf:do:$className@$script"
        add(CloudResource(
            id = id, kind = CloudKind.DurableObject, platform = CloudPlatform.Cloudflare, name = className,
            status = ResourceStatus.Healthy,
            attributes = mapOfNotNull("script" to script, "namespace" to namespace.text("id"), "sqlite" to namespace.flag("use_sqlite")?.toString()),
            evidence = listOf(evidence("workers/durable_objects/namespaces")),
        ))
        relate("cf:worker:$script", id, CloudRelationKind.Contains, "hosts", targetHint(CloudKind.DurableObject, className), "workers/durable_objects/namespaces")
    }

    fun tunnels(tunnels: List<JsonObject>, ingress: Map<JsonObject, List<JsonObject>>) = tunnels.forEach { tunnel ->
        val tunnelId = tunnel.text("id") ?: return@forEach
        val id = "cf:tunnel:$tunnelId"
        val connections = tunnel.objects("connections")
        val status = tunnel.text("status")
        add(CloudResource(
            id = id, kind = CloudKind.Tunnel, platform = CloudPlatform.Cloudflare, name = tunnel.text("name") ?: tunnelId,
            status = when (status) {
                "healthy" -> ResourceStatus.Healthy
                "degraded" -> ResourceStatus.Degraded
                else -> ResourceStatus.Down
            },
            statusDetail = "$status · ${connections.size} connection${if (connections.size == 1) "" else "s"}",
            attributes = mapOfNotNull("id" to tunnelId, "status" to status, "connections" to connections.size.toString(),
                "locations" to connections.mapNotNull { it.text("colo_name") }.distinct().joinToString().ifBlank { null },
                "connectorVersion" to connections.firstNotNullOfOrNull { it.text("client_version") },
                "created" to tunnel.text("created_at"), "lastActive" to tunnel.text("conns_active_at"),
                "offlineSince" to tunnel.text("conns_inactive_at")),
            evidence = listOf(evidence("cfd_tunnel")),
            consoleUrl = "https://one.dash.cloudflare.com/$accountId/networks/tunnels/cfd_tunnel/$tunnelId/edit",
            createdAtMillis = instantMillis(tunnel.text("created_at")),
        ))
        ingress.entries.firstOrNull { it.key.text("id") == tunnelId }?.value.orEmpty().forEach { rule ->
            val host = rule.text("hostname") ?: return@forEach
            val service = rule.text("service") ?: return@forEach
            hostname(host, "tunnel", service, if (status == "healthy") ResourceStatus.Healthy else if (status == "degraded") ResourceStatus.Degraded else ResourceStatus.Down)
            relate("host:$host", id, CloudRelationKind.Tunnels, service, detail = "cfd_tunnel/configurations")
            val target = service.substringAfter("://", "").substringBefore('/')
            val serviceHost = target.substringBefore(':')
            if (serviceHost.isNotBlank() && serviceHost != "localhost" && !serviceHost.first().isDigit()) {
                references += CloudReference.ServiceHost(
                    from = id, host = serviceHost, port = target.substringAfter(':', "").toIntOrNull(), namespaceHint = null,
                    kind = CloudRelationKind.Tunnels, label = host,
                    evidence = CloudEvidence(CloudSource.Inferred, "tunnel route $host → $service", readAt),
                )
            }
        }
    }

    fun vpcServices(services: List<JsonObject>) = services.forEach { service ->
        val serviceId = service.text("service_id") ?: return@forEach
        val id = "cf:vpc:$serviceId"
        val host = service.obj("host")
        val address = host.text("ipv4") ?: host.text("ipv6") ?: host.text("hostname")
        val tunnel = host.obj("network").text("tunnel_id")
        add(CloudResource(
            id = id, kind = CloudKind.VpcService, platform = CloudPlatform.Cloudflare, name = service.text("name") ?: serviceId,
            status = ResourceStatus.Healthy,
            attributes = mapOfNotNull("type" to service.text("type"), "address" to address,
                "ports" to listOfNotNull(service.text("http_port")?.let { "http $it" }, service.text("https_port")?.let { "https $it" }, service.text("tcp_port")?.let { "tcp $it" }).joinToString().ifBlank { null }),
            evidence = listOf(evidence("connectivity/directory/services")),
        ))
        tunnel?.let { relate(id, "cf:tunnel:$it", CloudRelationKind.Tunnels, detail = "connectivity/directory/services") }
        if (address != null && tunnel != null) {
            references += if (address.first().isDigit()) CloudReference.ClusterAddress(id, address, CloudRelationKind.Connects, service.text("name"),
                CloudEvidence(CloudSource.Inferred, "VPC service address $address matches a cluster IP", readAt))
            else CloudReference.ServiceHost(id, address, null, null, CloudRelationKind.Connects, service.text("name"),
                CloudEvidence(CloudSource.Inferred, "VPC service host $address", readAt))
        }
    }
}

internal fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (key, value) -> value?.takeIf { it.isNotBlank() }?.let { key to it } }.toMap()
