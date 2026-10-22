package dev.shibasis.dependeasy.model

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*

object PulumiStackKernel {
    fun plan(spec: PulumiStackSpec, root: File, gitSha: String, runner: String,
        execute: (ProcessExecution) -> String): PulumiStackPlan = try {
        project(spec, root)
        require(execute(Pulumi.version(spec)).trim().removePrefix("v") == spec.execution.tool.version)
        val inputs = inputs(spec, root)
        val config = configuration(spec, root)
        val directory = root.resolve("${GradleProtocol.PlanDirectory}/pulumi/${UUID.randomUUID()}")
        privateDirectory(directory)
        val saved = directory.resolve("update.json")
        val before = state(spec, execute)
        val result = BuildModelReader.json.parseToJsonElement(execute(Pulumi.preview(spec, config, saved))).jsonObject
        require(before == state(spec, execute)) { "Pulumi state changed during preview" }
        require(inputs == inputs(spec, root)) { "Pulumi sources changed during preview" }
        require(saved.isFile && !Files.isSymbolicLink(saved.toPath()))
        Files.setPosixFilePermissions(saved.toPath(), PosixFilePermissions.fromString("rw-------"))
        val changes = result.getValue("changeSummary").jsonObject.mapValues { (_, value) -> value.jsonPrimitive.int }
        require(changes.values.all { it >= 0 })
        PulumiStackPlan(spec, inputs, before, saved.relativeTo(root).invariantSeparatorsPath,
            BuildModelReader.digest(saved), changes.toSortedMap(), gitSha, runner)
    } catch (_: Exception) { error("Cannot preview Pulumi stack; provider output withheld") }

    fun apply(plan: PulumiStackPlan, root: File, run: String, execute: (ProcessExecution) -> String,
        record: (DeployReceipt) -> Unit): DeployReceipt {
        val started = System.currentTimeMillis()
        val spec = plan.spec
        val stack = spec.stack.stacks.getValue(spec.environment)
        var version: String? = null
        fun receipt(status: ReceiptStatus) = DeployReceipt(spec.node, spec.environment, ServiceRef(stack), run,
            "pulumi-apply", plan.gitSha, plan.runner, BuildModelReader.digest(BuildModelReader.json.encodeToString(plan)),
            plan.inputDigest, version, null, status, if (status == ReceiptStatus.Succeeded) "update-verified" else "pulumi",
            started, System.currentTimeMillis(), failure = if (status == ReceiptStatus.Failed) "Pulumi update failed; provider output withheld" else null)
        try {
            require(inputs(spec, root) == plan.inputDigest)
            val saved = root.resolve(plan.savedPlan).canonicalFile
            require(saved.toPath().startsWith(root.resolve("${GradleProtocol.PlanDirectory}/pulumi").canonicalFile.toPath()))
            require(saved.isFile && BuildModelReader.digest(saved) == plan.savedPlanDigest)
            require(state(spec, execute) == plan.stateDigest)
            require(execute(Pulumi.version(spec)).trim().removePrefix("v") == spec.execution.tool.version)
            execute(Pulumi.up(spec, configuration(spec, root), saved, run))
            val update = BuildModelReader.json.parseToJsonElement(execute(Pulumi.history(spec))).jsonArray.first().jsonObject
            require(update["kind"]?.jsonPrimitive?.content == "update" && update["result"]?.jsonPrimitive?.content == "succeeded")
            require(update["message"]?.jsonPrimitive?.content == "dependeasy:$run")
            val updateVersion = update.getValue("version").jsonPrimitive.int.also { require(it >= 0) }
            version = if (updateVersion > 0) "$stack:$updateVersion" else {
                val time = java.time.Instant.parse(update.getValue("startTime").jsonPrimitive.content)
                "$stack:$time:$run"
            }
        } catch (_: Exception) {
            record(receipt(ReceiptStatus.Failed))
            error("Pulumi update failed; provider output withheld")
        }
        return receipt(ReceiptStatus.Succeeded).also(record)
    }

    fun observe(plan: PulumiStackPlan, run: String, record: (DeployReceipt) -> Unit): DeployReceipt {
        val now = System.currentTimeMillis()
        return DeployReceipt(plan.spec.node, plan.spec.environment, ServiceRef(plan.spec.stack.stacks.getValue(plan.spec.environment)),
            run, "pulumi-preview", plan.gitSha, plan.runner, BuildModelReader.digest(BuildModelReader.json.encodeToString(plan)),
            plan.inputDigest, null, null, ReceiptStatus.Succeeded, "previewed", now, now).also(record)
    }

    fun planFile(root: File, node: NodeId, environment: Environment) =
        root.resolve("${GradleProtocol.PlanDirectory}/pulumi/" + BuildModelReader.digest("$node:${environment.name}") + ".json")

    fun retained(root: File, node: NodeId, environment: Environment): PulumiStackPlan =
        BuildModelReader.json.decodeFromString(planFile(root, node, environment).readText())

    fun store(root: File, plan: PulumiStackPlan) {
        val file = planFile(root, plan.spec.node, plan.spec.environment)
        privateDirectory(file.parentFile)
        val temporary = File.createTempFile(".pulumi-plan-", ".json", file.parentFile)
        try {
            Files.setPosixFilePermissions(temporary.toPath(), PosixFilePermissions.fromString("rw-------"))
            temporary.writeText(BuildModelReader.json.encodeToString(plan))
            Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }

    private fun state(spec: PulumiStackSpec, execute: (ProcessExecution) -> String): String =
        BuildModelReader.digest(BuildModelReader.json.parseToJsonElement(execute(Pulumi.export(spec))).toString())

    private fun inputs(spec: PulumiStackSpec, root: File): String {
        val owner = root.canonicalFile.toPath()
        val projectFile = if (spec.programDirectory != null) listOf(spec.stack.directory.trimEnd('/') + "/Pulumi.yaml") else emptyList()
        val files = (spec.stack.sources.paths + projectFile).distinct().sorted().associateWith { path ->
            val file = root.resolve(path).canonicalFile
            require(file.toPath().startsWith(owner) && file.isFile)
            BuildModelReader.digest(file)
        }
        val program = spec.programDirectory?.let { path ->
            val directory = root.resolve(path).canonicalFile
            require(directory.isDirectory)
            directory.walkTopDown().filter { it.isFile }.map { file ->
                require(file.canonicalFile.toPath().startsWith(directory.toPath()) && !Files.isSymbolicLink(file.toPath()))
                file.relativeTo(directory).invariantSeparatorsPath to BuildModelReader.digest(file)
            }.toList().also { require(it.isNotEmpty()) }.toMap().toSortedMap()
        }.orEmpty()
        return BuildModelReader.digest(BuildModelReader.json.encodeToString(spec) +
            BuildModelReader.json.encodeToString(files) + BuildModelReader.json.encodeToString(program))
    }

    private fun project(spec: PulumiStackSpec, root: File) {
        val program = spec.programDirectory?.let { root.resolve(it).canonicalFile } ?: return
        val directory = root.resolve(spec.stack.directory).canonicalFile
        require(directory.toPath().startsWith(root.resolve("build/dependeasy/contracts/pulumi/projects").canonicalFile.toPath()))
        val binary = program.resolve("bin").listFiles().orEmpty().single { it.extension == "jar" }.canonicalFile
        require(binary.toPath().startsWith(program.toPath()) && binary.isFile)
        privateDirectory(directory)
        directory.resolve("Pulumi.yaml").writeText(buildJsonObject {
            put("name", spec.node.name)
            put("runtime", buildJsonObject { put("name", "java"); put("options", buildJsonObject { put("binary", binary.absolutePath) }) })
        }.toString())
    }

    private fun configuration(spec: PulumiStackSpec, root: File): File {
        val directory = root.resolve("build/dependeasy/contracts/pulumi/" + BuildModelReader.digest("${spec.node}:${spec.environment.name}"))
        privateDirectory(directory)
        return directory.resolve("stack.json").also { file ->
            require(!Files.isSymbolicLink(file.toPath()))
            if (!file.exists()) Files.createFile(file.toPath(), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString("rw-------"))
            file.writeText(buildJsonObject { put("config", spec.stack.configuration.getValue(spec.environment)) }.toString())
        }
    }

    private fun privateDirectory(directory: File) {
        require(directory.canonicalFile.toPath() == directory.toPath().toAbsolutePath().normalize())
        directory.mkdirs()
        Files.setPosixFilePermissions(directory.toPath(), PosixFilePermissions.fromString("rwx------"))
    }
}
