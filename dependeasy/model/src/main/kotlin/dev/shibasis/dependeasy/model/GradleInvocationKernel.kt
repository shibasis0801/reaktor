package dev.shibasis.dependeasy.model

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

data class ValidatedGradleInvocation(
    val request: GradleRequest,
    val node: Node,
    val modelDigest: String,
    val requestDigest: String,
    val own: String,
    val tasks: List<String>,
)

object GradleInvocationKernel {
    fun validate(root: File, requestFile: File, model: BuildModel): ValidatedGradleInvocation {
        val owned = root.resolve(GradleProtocol.RequestDirectory).toPath()
        require(owned.startsWith(root.toPath()) && (root.toPath().nameCount..owned.nameCount).none { count ->
            java.nio.file.Files.isSymbolicLink(owned.root.resolve(owned.subpath(0, count)))
        }) { "Gradle request directory cannot contain symbolic links" }
        val file = requestFile.canonicalFile
        require(file.toPath().startsWith(owned) && !java.nio.file.Files.isSymbolicLink(requestFile.toPath())) {
            "Gradle requests must be inside the owned request directory"
        }
        val request = BuildModelReader.readRequest(file)
        val digest = BuildModelReader.digest(BuildModelReader.encode(model))
        require(request.modelDigest == digest) { "Build model changed; review a fresh plan" }
        val node = model.nodes.single { it.id == request.node }
        require(request.declaredAt == node.declaredAt) { "Build declaration changed; review a fresh plan" }
        val invocation = node.invocation as? Invocation.Gradle ?: error("This node has no Gradle invocation")
        val own = model.builds.single { it.directory == "." }.name
        val tasks = invocation.tasks.map { if (node.id.build == own) it else ":${node.id.build}$it" }
        require(tasks.none { it.substringAfterLast(':') in setOf(GradleProtocol.PlanTask, GradleProtocol.RunTask) }) {
            "Build planning and execution bridges cannot invoke themselves"
        }
        val environmentPrefix = "-P${GradleProtocol.EnvironmentOption}="
        val environments = request.arguments.filter { it.startsWith(environmentPrefix) }
        require(environments.size <= 1) { "Invocation must select one environment" }
        environments.singleOrNull()?.substringAfter('=')?.let { environment ->
            require(node.environments.any { it.name == environment } && model.declarations.any { input ->
                input.build == node.id.build && input.option?.let { option ->
                    option.name == GradleProtocol.EnvironmentOption && option.scope == node.id.project && option.value == environment
                } == true
            }) { "Invocation environment must match the declared build context" }
        }
        val properties = request.arguments.filter { it.startsWith("-P") && !it.startsWith(environmentPrefix) }
        val declarations = model.buildOptions(node.id).mapNotNull { it.option }.groupBy { it.name }
        val reserved = setOf(GradleProtocol.RequestOption, GradleProtocol.CorrelationOption,
            GradleProtocol.DeployActionOption, GradleProtocol.DeployRequestOption)
        require(properties.map { it.substringBefore('=') }.distinct().size == properties.size) {
            "Invocation must select each build property once"
        }
        properties.forEach { argument ->
            val name = argument.removePrefix("-P").substringBefore('=')
            val options = declarations[name].orEmpty()
            require('=' in argument && name !in reserved && options.isNotEmpty() && options.all {
                !it.sensitive && OptionSource.CommandLine in it.resolutionOrder && it.value == argument.substringAfter('=')
            }) { "Invocation build properties must match the declared build context" }
        }
        require(request.arguments.none { argument ->
            argument in setOf("-p", "--project-dir", "-g", "--gradle-user-home") ||
                argument.startsWith("-p") || argument.startsWith("-g") ||
                argument.startsWith("--project-dir=") || argument.startsWith("--gradle-user-home=")
        }) { "Invocation arguments cannot change workspace ownership or the Gradle protocol" }
        deploymentNodes(model, node.id).forEach { deployment ->
            val environment = when (val spec = deployment.kernel?.spec) {
                is KernelSpec.ServerRollout -> spec.deployment.environment
                is KernelSpec.WranglerDeploy -> spec.deployment.environment
                else -> null
            }
            if (environment != null) deploymentNodes(model, deployment.id).forEach { prerequisite ->
                val migration = (prerequisite.kernel?.spec as? KernelSpec.DatabaseMigration)?.migration
                require(migration == null || migration.environment == environment) {
                    "Deployment and prerequisite migration must select the same environment"
                }
            }
        }
        val requestDigest = BuildModelReader.digest(file)
        require(file.name == "$requestDigest.json") { "Gradle request content changed; review a fresh plan" }
        return ValidatedGradleInvocation(request, node, digest, requestDigest, own, tasks)
    }

    fun context(javaHome: String, gradleHome: File, gradleUserHome: File, initScripts: Set<File>,
        environment: Map<String, String>, systemProperties: Map<*, *>): String {
        val context = buildJsonObject {
            put("java", javaHome)
            put("gradle", gradleHome.absolutePath)
            val userProperties = gradleUserHome.resolve("gradle.properties")
            put("userProperties", if (userProperties.isFile) BuildModelReader.digest(userProperties) else "absent")
            put("initScripts", BuildModelReader.digest(initScripts.sortedBy { it.absolutePath }.joinToString("\n") { BuildModelReader.digest(it) }))
            put("environment", BuildModelReader.digest(BuildModelReader.json.encodeToString<Map<String, String>>(environment.filterKeys { key -> key !in setOf("REMOTE_DEV_RUN_ID", "DEPENDEASY_OPERATION_RUN_ID", "_") && !key.matches(Regex("APP_ICON_[0-9]+")) }.toSortedMap())))
            put("systemProperties", BuildModelReader.digest(systemProperties.entries
                .filter { entry -> entry.key.toString().startsWith("org.gradle.project.") }
                .sortedBy { entry -> entry.key.toString() }.joinToString("\n") { entry -> "${entry.key}=${entry.value}" }))
        }
        return BuildModelReader.digest(context.toString())
    }

    fun plan(invocation: ValidatedGradleInvocation, contextDigest: String, elapsed: Long,
        observed: List<PlannedGradleTask>, preparation: Set<NodeId>): GradlePlan {
        require(observed.isNotEmpty()) { "Gradle returned no selected tasks" }
        val requested = observed.filterNot { task -> task.id in preparation }
        val selectedIds = requested.map { task -> task.id }.toSet()
        val planned = requested.map { task -> task.copy(
            dependencies = task.dependencies.filter(selectedIds::contains),
            dependenciesOutsideSelection = task.dependencies.filterNot(selectedIds::contains)) }
        return GradlePlan(node = invocation.node.id, modelDigest = invocation.modelDigest, requestDigest = invocation.requestDigest,
            contextDigest = contextDigest, invocation = invocation.tasks + invocation.request.arguments, configuredMillis = elapsed, tasks = planned,
            preparationTasks = preparation.sortedBy { task -> task.toString() })
    }

    fun deploymentNodes(model: BuildModel, selected: NodeId): List<Node> {
        val edges = model.edges.filter { it !is Edge.OrderedAfter }.groupBy { it.from }
        val visited = mutableSetOf<NodeId>()
        fun visit(id: NodeId) { if (visited.add(id)) edges[id].orEmpty().forEach { visit(it.to) } }
        visit(selected)
        return model.nodes.filter { it.id in visited &&
            (it.kernel?.spec is KernelSpec.WranglerDeploy || it.kernel?.spec is KernelSpec.ServerRollout || it.kernel?.spec is KernelSpec.WorkerSecrets || it.kernel?.spec is KernelSpec.DatabaseMigration || it.kernel?.spec is KernelSpec.PulumiStack) }
    }

    fun requireExecution(invocation: ValidatedGradleInvocation, contextDigest: String) {
        require(invocation.request.expectedContextDigest == contextDigest) {
            "Gradle execution host or configuration changed; review a fresh plan"
        }
    }
}
