package dev.shibasis.reaktor.tooling

import dev.shibasis.dependeasy.model.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

internal data class PreparedGradleBuild(
    val argv: List<String>, val seal: ProcessDefinitionSeal, val plan: GradlePlan,
    val artifacts: List<ArtifactExpectation>, val facts: List<String>,
)

internal class GradleBuildPlanner {
    fun prepare(root: File, wrapper: String, node: Node, arguments: List<String>,
                seal: ProcessDefinitionSeal, redactions: Set<String>, environment: Map<String, String>,
                sensitiveEnvironmentKeys: Set<String>): PreparedGradleBuild {
        val properties = arguments.filter { it.startsWith("-P") }
        fun gradle(tasks: List<String>, definitions: ProcessDefinitionSeal, failure: String) = runBlocking {
            SupervisedProcessExecutor().use { executor ->
                val prepared = AdHocProcessPlan.create(listOf(wrapper) + tasks + properties + "--console=plain", root, TaskId(node.id.toString()),
                    SafetyPolicy(SafetyClass.LocalArtifactWrite), redactions = redactions, definitionSeal = definitions,
                    environment = environment, sensitiveEnvironmentKeys = sensitiveEnvironmentKeys)
                val handle = executor.start(prepared)
                val tail = ArrayDeque<String>()
                val collector = launch { handle.events.collect { event ->
                    if (event is RunEvent.Output) { tail.addLast(event.text); if (tail.size > 16) tail.removeFirst() }
                } }
                val completed = handle.await(); collector.join()
                check(completed.status == RunStatus.Succeeded) { "$failure: " +
                    (completed.failure?.message.orEmpty() + "\n" + tail.joinToString("\n")).takeLast(8192) }
            }
        }
        gradle(listOf(GradleProtocol.ExportTask), ProcessDefinitionSeal.capture(seal.files, seal.directories),
            "Exporting the build model for the requested context failed")
        val model = BuildModelReader.readAllowingStale(root)
        val current = requireNotNull(model.nodes.singleOrNull { it.id == node.id }) {
            "${node.id} is no longer declared in the build; refresh the catalog"
        }
        require(current == node) {
            "The declaration of ${node.id} changed" + (current.declaredAt?.let { " at ${it.build}/${it.file}:${it.line}" } ?: "") +
                " since the catalog was read; refresh the catalog"
        }
        val request = GradleRequest(node = node.id, declaredAt = node.declaredAt,
            modelDigest = BuildModelReader.digest(BuildModelReader.encode(model)), arguments = arguments)
        fun write(value: GradleRequest): File {
            val text = BuildModelReader.json.encodeToString(value) + "\n"
            val path = root.resolve(GradleProtocol.RequestDirectory).toPath()
            require(path.toFile().canonicalFile.toPath() == path.toAbsolutePath().normalize()) { "Gradle request path escapes its owner" }
            Files.createDirectories(path)
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            val file = path.resolve(BuildModelReader.digest(text) + ".json")
            if (!Files.exists(file)) {
                Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
                Files.writeString(file, text)
            }
            require(Files.readString(file) == text && !Files.isSymbolicLink(file)) { "Gradle request content changed" }
            return file.toFile()
        }
        val contract = write(request)
        gradle(listOf(GradleProtocol.PlanTask, "-P${GradleProtocol.RequestOption}=${contract.absolutePath}"),
            ProcessDefinitionSeal.capture(seal.files + contract, seal.directories), "Gradle planning failed")
        val planFile = root.resolve("${GradleProtocol.PlanDirectory}/${contract.name}")
        val plan = BuildModelReader.readPlan(planFile)
        require(plan.node == node.id && plan.modelDigest == request.modelDigest && plan.requestDigest == BuildModelReader.digest(contract))
        val executable = write(request.copy(expectedContextDigest = plan.contextDigest))
        val argv = listOf(wrapper, GradleProtocol.RunTask, "-P${GradleProtocol.RequestOption}=${executable.absolutePath}", "--console=plain") + properties
        return PreparedGradleBuild(argv, ProcessDefinitionSeal.capture(seal.files + executable + planFile, seal.directories),
            plan, listOf(ArtifactExpectation("gradle-result", "${GradleProtocol.ResultDirectory}/${executable.name}", true)),
            listOf("gradle-plan", BuildModelReader.digest(planFile), "configuration-ms=${plan.configuredMillis}"))
    }
}
