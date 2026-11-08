package dev.shibasis.dependeasy.model

import kotlinx.serialization.Serializable

@Serializable
data class GradlePlan(
    val schema: Int = 1,
    val node: NodeId,
    val modelDigest: String,
    val requestDigest: String,
    val contextDigest: String,
    val invocation: List<String>,
    val configuredMillis: Long,
    val tasks: List<PlannedGradleTask>,
    val preparationTasks: List<NodeId> = emptyList(),
    val deployments: List<WorkerDeployPlan> = emptyList(),
    val serverDeployments: List<ServerRolloutPlan> = emptyList(),
    val secrets: List<WorkerSecretsPlan> = emptyList(),
    val migrations: List<MigrationPlan> = emptyList(),
)

@Serializable data class PlannedGradleTask(val id: NodeId, val dependencies: List<NodeId>, val dependenciesOutsideSelection: List<NodeId> = emptyList())

@Serializable
data class GradleRequest(
    val schema: Int = 1,
    val node: NodeId,
    val declaredAt: SourceSite?,
    val modelDigest: String,
    val arguments: List<String> = emptyList(),
    val expectedContextDigest: String? = null,
    val deployments: List<WorkerDeployPlan> = emptyList(),
    val serverDeployments: List<ServerRolloutPlan> = emptyList(),
    val secrets: List<WorkerSecretsPlan> = emptyList(),
    val migrations: List<MigrationPlan> = emptyList(),
)

@Serializable data class GradleRunResult(val schema: Int = 1, val requestDigest: String, val receipts: List<String>, val operationRunId: String? = null)
@Serializable data class GradleTaskProgress(val schema: Int = 1, val task: String, val outcome: TaskOutcome? = null)

object GradleProtocol {
    const val EnvironmentOption = "dependeasy.environment"
    const val RequestOption = "dependeasy.gradleRequest"
    const val CorrelationOption = "dependeasy.requestDigest"
    const val DeployActionOption = "dependeasy.deployAction"
    const val DeployRequestOption = "dependeasy.deployRequest"
    const val ExportTask = "exportBuildModel"
    const val PlanTask = "planBuildNode"
    const val RunTask = "runBuildNode"
    const val RequestDirectory = "build/dependeasy/contracts/gradle/requests"
    const val PlanDirectory = "build/dependeasy/plans"
    const val ReceiptDirectory = "build/dependeasy/receipts"
    const val ResultDirectory = "build/dependeasy/runs"
    const val OperationRunVariable = "DEPENDEASY_OPERATION_RUN_ID"
    const val ProgressPrefix = "@dependeasy-task "
}
