package dev.shibasis.reaktor.cloud

import dev.shibasis.reaktor.tooling.CatalogProvider
import dev.shibasis.reaktor.tooling.ProviderAvailability
import dev.shibasis.reaktor.tooling.SafetyClass
import dev.shibasis.reaktor.tooling.SafetyPolicy
import dev.shibasis.reaktor.tooling.TaskId
import dev.shibasis.reaktor.tooling.TaskInput
import dev.shibasis.reaktor.tooling.TaskKind
import dev.shibasis.reaktor.tooling.TaskProvenance
import dev.shibasis.reaktor.tooling.ToolingProviderState
import dev.shibasis.reaktor.tooling.ToolingResource
import dev.shibasis.reaktor.tooling.ToolingTask
import dev.shibasis.reaktor.tooling.ToolingWorkspace

/**
 * Makes legacy cloud tool declarations visible to the shared tooling catalog while execution moves
 * to typed tooling adapters. Operations with unclear effects intentionally fail closed.
 */
class CloudToolCatalogProvider(
    private val cloud: CloudToolProvider,
    private val provenancePath: String? = null,
) : CatalogProvider {
    override val providerId: String = cloud.id

    override suspend fun tasks(workspace: ToolingWorkspace): List<ToolingTask> =
        cloud.operations().map { operation -> operation.toToolingTask() }

    override suspend fun state(workspace: ToolingWorkspace): ToolingProviderState =
        runCatching { cloud.operations() }.fold(
            onSuccess = {
                ToolingProviderState(
                    provider = providerId,
                    availability = ProviderAvailability.Unknown,
                    detail = "Catalog available; legacy provider does not probe executable health",
                )
            },
            onFailure = { error ->
                ToolingProviderState(
                    provider = providerId,
                    availability = ProviderAvailability.Unavailable,
                    detail = error.message,
                )
            },
        )

    private fun CloudOperation.toToolingTask(): ToolingTask {
        val kind = taskKind()
        return ToolingTask(
            id = TaskId(id),
            label = label,
            kind = kind,
            provider = provider,
            inputs = inputs.map { input ->
                TaskInput(
                    name = input.key,
                    description = input.label,
                    required = input.required,
                    sensitive = input.sensitive,
                    defaultValue = input.default,
                    allowedValues = input.allowedValues,
                )
            },
            safety = safety(kind),
            provenance = TaskProvenance(
                source = "reaktor-cloud:$providerId",
                path = provenancePath,
            ),
            unavailableReason = unavailableReason,
            attributes = buildMap {
                put("legacyCloudOperation", "true")
                unavailableReason?.let { put("unavailableReason", it) }
            },
        )
    }

    private fun CloudOperation.taskKind(): TaskKind {
        val words = "$id $label".lowercase()
        return when {
            words.contains("destroy") || words.contains("delete") -> TaskKind.Delete
            words.contains("deploy") || words.endsWith(" up") || id.endsWith(".up") -> TaskKind.Deploy
            words.contains("preview") || words.contains("status") || words.contains("logs") -> TaskKind.Observe
            words.contains("test") -> TaskKind.Test
            words.contains("check") || words.contains("verify") || id.endsWith(".pr") -> TaskKind.Check
            words.contains("build") || words.contains("bundle") || words.contains("package") -> TaskKind.Build
            else -> TaskKind.Custom
        }
    }

    private fun CloudOperation.safety(kind: TaskKind): SafetyPolicy {
        val classification = safety ?: when {
            destructive -> SafetyClass.Destructive
            kind == TaskKind.Observe -> SafetyClass.ReadOnly
            kind == TaskKind.Test || kind == TaskKind.Check -> SafetyClass.LocalEphemeral
            kind == TaskKind.Build -> SafetyClass.LocalArtifactWrite
            else -> SafetyClass.UnknownRemoteEffect
        }
        return SafetyPolicy(
            classification = classification,
            reason = when {
                destructive -> "Legacy cloud operation is declared destructive"
                safety != null -> "Cloud provider declares this operation as ${classification.name}"
                classification == SafetyClass.UnknownRemoteEffect ->
                    "Legacy cloud operation does not declare enough information to prove its effects"
                else -> null
            },
        )
    }
}

fun CloudToolProvider.asCatalogProvider(provenancePath: String? = null): CatalogProvider =
    CloudToolCatalogProvider(this, provenancePath)

/** Projects provider inventory into the same catalog consumed by Desktop and the CLI. */
class CloudInventoryCatalogProvider(
    private val cloud: CloudProvider,
) : CatalogProvider {
    override val providerId: String = cloud.id

    override suspend fun resources(workspace: ToolingWorkspace): List<ToolingResource> =
        cloud.inventory().map { resource ->
            ToolingResource(
                id = "${resource.provider}/${resource.kind}/${resource.id}",
                provider = resource.provider,
                kind = resource.kind,
                name = resource.name,
                targetId = resource.tags["targetId"],
                environment = resource.tags["environment"],
                attributes = buildMap {
                    put("status", resource.status.name)
                    resource.region?.let { put("region", it) }
                    resource.consoleUrl?.let { put("consoleUrl", it) }
                    resource.grafanaUrl?.let { put("grafanaUrl", it) }
                    putAll(resource.metrics.mapKeys { "metric.${it.key}" })
                    putAll(resource.tags.mapKeys { "tag.${it.key}" })
                },
            )
        }

    override suspend fun state(workspace: ToolingWorkspace): ToolingProviderState =
        runCatching { cloud.health() }.fold(
            onSuccess = { health ->
                ToolingProviderState(
                    provider = providerId,
                    availability = health.status.toAvailability(),
                    detail = health.detail,
                )
            },
            onFailure = { error ->
                ToolingProviderState(
                    provider = providerId,
                    availability = ProviderAvailability.Unavailable,
                    detail = error.message,
                )
            },
        )

    private fun ResourceStatus.toAvailability(): ProviderAvailability = when (this) {
        ResourceStatus.Healthy -> ProviderAvailability.Available
        ResourceStatus.Degraded -> ProviderAvailability.Degraded
        ResourceStatus.Down -> ProviderAvailability.Unavailable
        ResourceStatus.Unknown -> ProviderAvailability.Unknown
    }
}

fun CloudProvider.asInventoryCatalogProvider(): CatalogProvider = CloudInventoryCatalogProvider(this)
