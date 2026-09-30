package dev.shibasis.reaktor.tooling.cloud

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

class CloudInventory(
    private val providers: List<CloudProvider>,
    private val clock: () -> Long,
    private val classify: (CloudResource) -> String? = { null },
    private val audit: (CloudSnapshot) -> List<CloudFinding> = CloudAudit::findings,
) {
    suspend fun read(timeoutMillis: Long = 90_000): CloudSnapshot = coroutineScope {
        assemble(providers.map { provider -> async { readSafely(provider, timeoutMillis) } }.awaitAll())
    }

    suspend fun readSafely(provider: CloudProvider, timeoutMillis: Long): CloudReading {
        val started = clock()
        return try {
            withTimeout(timeoutMillis) { provider.read() }
        } catch (timeout: TimeoutCancellationException) {
            failed(provider.id, started, "No answer within ${timeoutMillis / 1000} s")
        } catch (failure: Exception) {
            failed(provider.id, started, failure.message ?: failure::class.simpleName.orEmpty())
        }
    }

    fun assemble(readings: List<CloudReading>): CloudSnapshot {
        val merged = readings.flatMap { it.resources }.groupBy { it.id }.mapValues { (_, versions) -> versions.reduce(::combine) }
        val relations = readings.flatMap { it.relations }.groupBy { it.id }.map { (_, versions) -> versions.reduce(::combine) }
        val references = readings.flatMap { it.references }
        val resolutions = resolve(references, merged.values.toList(), relations)
        val resolved = resolutions.mapNotNull { it.second }
        val allRelations = (relations + resolved).groupBy { it.id }.map { (_, versions) -> versions.reduce(::combine) }
            .groupBy { Triple(it.source, it.kind, it.target) }
            .flatMap { (_, twins) ->
                val named = twins.filter { it.label != null }
                val unnamed = twins.filter { it.label == null }
                if (named.isEmpty() || unnamed.isEmpty()) twins
                else named.mapIndexed { index, relation -> if (index == 0) unnamed.fold(relation, ::combine) else relation }
            }
        val placeholders = allRelations.flatMap { listOf(it.source to it, it.target to it) }
            .filter { (id, _) -> id !in merged }
            .distinctBy { it.first }
            .map { (id, relation) -> placeholder(id, relation) }
        val resources = (merged.values + placeholders).map { resource ->
            if (resource.app != null) resource else resource.copy(app = classify(resource))
        }.sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))
        val snapshot = CloudSnapshot(
            resources = resources,
            relations = allRelations,
            changes = readings.flatMap { it.changes }.sortedByDescending { it.atMillis },
            readings = readings,
            findings = emptyList(),
            takenAtMillis = readings.maxOfOrNull { it.readAtMillis } ?: clock(),
            unresolved = resolutions.filter { it.second == null }.map { it.first },
        )
        return snapshot.copy(findings = audit(snapshot).sortedWith(compareBy({ it.severity.rank }, { it.category.ordinal }, { it.title })))
    }

    private fun failed(provider: String, started: Long, detail: String): CloudReading {
        val now = clock()
        return CloudReading(provider, null, ProviderHealth(provider, ResourceStatus.Down, detail), now, now - started)
    }

    private fun combine(first: CloudResource, second: CloudResource): CloudResource {
        val (primary, secondary) = when {
            second.observed && !first.observed -> second to first
            first.observed && !second.observed -> first to second
            first.platform == CloudPlatform.Kubernetes && second.platform != CloudPlatform.Kubernetes -> second to first
            else -> first to second
        }
        val statuses = listOf(first, second).filter { it.observed && it.status != ResourceStatus.Unknown }
        return primary.copy(
            status = statuses.maxByOrNull { it.status.rank }?.status ?: primary.status,
            statusDetail = statuses.maxByOrNull { it.status.rank }?.statusDetail ?: primary.statusDetail ?: secondary.statusDetail,
            app = primary.app ?: secondary.app,
            region = primary.region ?: secondary.region,
            attributes = secondary.attributes + primary.attributes,
            metrics = (primary.metrics + secondary.metrics).distinctBy { it.key },
            evidence = (first.evidence + second.evidence).distinct(),
            consoleUrl = primary.consoleUrl ?: secondary.consoleUrl,
            createdAtMillis = primary.createdAtMillis ?: secondary.createdAtMillis,
        )
    }

    private fun combine(first: CloudRelation, second: CloudRelation): CloudRelation =
        first.copy(attributes = second.attributes + first.attributes, evidence = (first.evidence + second.evidence).distinct())

    private fun placeholder(id: String, relation: CloudRelation): CloudResource {
        val kind = relation.attributes["targetKind"]?.let { name -> CloudKind.entries.firstOrNull { it.name == name } }
            ?: CloudKind.Worker
        return CloudResource(
            id = id,
            kind = kind,
            platform = relation.attributes["targetPlatform"]?.let { name -> CloudPlatform.entries.firstOrNull { it.name == name } }
                ?: CloudPlatform.Cloudflare,
            name = relation.attributes["targetName"] ?: id.substringAfterLast(':'),
            status = ResourceStatus.Unknown,
            statusDetail = "Referenced by ${relation.source.substringAfterLast(':')}, but no reading found it",
            attributes = mapOf("placeholder" to "true"),
            evidence = listOf(CloudEvidence(CloudSource.Inferred, "referenced by ${relation.source}")),
        )
    }

    private fun resolve(
        references: List<CloudReference>,
        resources: List<CloudResource>,
        relations: List<CloudRelation>,
    ): List<Pair<CloudReference, CloudRelation?>> {
        val services = resources.filter { it.kind == CloudKind.KubernetesService }
        val attachedDisks = relations.filter { it.kind == CloudRelationKind.Attached }
            .groupBy({ it.target }, { it.source })
        val disks = resources.filter { it.kind == CloudKind.Disk }.associateBy { it.id }
        val registries = resources.filter { it.kind == CloudKind.ImageRepository }
        return references.map { reference ->
            val target = when (reference) {
                is CloudReference.ServiceHost -> {
                    val labels = reference.host.split('.')
                    val namespace = labels.getOrNull(1)?.takeIf { labels.size > 1 } ?: reference.namespaceHint
                    val named = services.filter { it.name == labels.first() }
                    (named.firstOrNull { it.attributes["namespace"] == namespace } ?: named.singleOrNull())?.id
                }
                is CloudReference.ClusterAddress -> services.firstOrNull { it.attributes["clusterIP"] == reference.address }?.id
                is CloudReference.HostPath -> {
                    val candidates = attachedDisks["machine:${reference.machine}"].orEmpty().mapNotNull(disks::get)
                    val segments = reference.path.split('/').filter { it.isNotBlank() }.toSet()
                    candidates.firstOrNull { disk ->
                        val device = disk.attributes["deviceName"].orEmpty()
                        device.isNotBlank() && (device in segments || device.substringAfterLast('-') in segments)
                    }?.id ?: candidates.firstOrNull { it.attributes["boot"] == "true" }?.id
                }
                is CloudReference.Image -> registries.firstOrNull { registry ->
                    registry.attributes["prefix"]?.let { reference.image.startsWith("$it/") } == true
                }?.id
            }
            reference to target?.let { CloudRelation(reference.from, it, reference.kind, reference.label, evidence = listOf(reference.evidence)) }
        }
    }
}
