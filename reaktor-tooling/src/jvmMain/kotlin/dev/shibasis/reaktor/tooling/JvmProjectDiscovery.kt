package dev.shibasis.reaktor.tooling

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

data class PreparedProcessExecution(
    val plan: TaskPlan,
    val request: ProcessExecutionRequest,
)

class SafetyApprovalRequiredException(val task: ToolingTask) : IllegalStateException(
    "Task '${task.id.value}' requires explicit approval: " +
        (task.safety.reason ?: task.safety.classification.name),
)

/** A discovered catalog plus the private argv bindings used to create an executable plan. */
class DiscoveredJvmWorkspace internal constructor(
    val catalog: ToolingCatalog,
    private val bindings: Map<TaskId, JvmTaskBinding>,
    private val clock: () -> Long,
) : TaskPlanningPort {
    private val tasksById = catalog.tasks.associateBy { it.id }

    override suspend fun plan(invocation: TaskInvocation): TaskPlan = prepare(invocation).plan

    fun prepare(invocation: TaskInvocation): PreparedProcessExecution {
        val task = tasksById[invocation.taskId]
            ?: throw IllegalArgumentException("Unknown task '${invocation.taskId.value}'")
        task.unavailableReason?.let { reason ->
            error("Task '${task.id.value}' is unavailable: $reason")
        }
        val binding = bindings[invocation.taskId]
            ?: throw IllegalStateException("Task '${invocation.taskId.value}' has no JVM execution adapter")
        binding.blockedReason?.let { reason ->
            error("Task '${task.id.value}' is unavailable: $reason")
        }
        if (binding.definitionFiles.isNotEmpty() || binding.definitionDirectories.isNotEmpty()) {
            val currentSeal = ProcessDefinitionSeal.capture(binding.definitionFiles, binding.definitionDirectories)
            check(currentSeal.digest == binding.definitionDigest) {
                "Task definition changed after catalog discovery; refresh the catalog before planning '${task.id.value}'"
            }
        }
        val inputSecrets = task.inputs
            .filter { it.sensitive }
            .mapNotNull { invocation.inputs[it.name] }
            .toSet()
        val argumentSecrets = invocation.arguments.sensitiveArgumentValues()
        val redactions = binding.redactions + inputSecrets + argumentSecrets
        val argv = binding.argv + invocation.arguments
        val invocationSafety = invocation.arguments.additionalSafety()
        val effectiveSafety = invocationSafety?.let { added ->
            val classification = maxSafety(task.safety.classification, added)
            task.safety.copy(
                classification = classification,
                reason = if (classification.requiresApproval) {
                    "Task definition or invocation arguments indicate a write or remote effect"
                } else task.safety.reason,
            )
        } ?: task.safety
        val request = AdHocProcessPlan.create(
            argv = argv,
            workingDirectory = binding.workingDirectory,
            taskId = task.id,
            safety = effectiveSafety,
            invocation = invocation.redacted(task, argumentSecrets),
            environment = binding.environment,
            sensitiveEnvironmentKeys = binding.sensitiveEnvironmentKeys,
            redactions = redactions,
            timeoutMillis = binding.timeoutMillis,
            nowEpochMillis = clock(),
            workspaceId = catalog.workspace.id,
            fingerprintContext = listOf(
                task.provenance.source,
                task.provenance.path.orEmpty(),
                binding.definitionDigest.orEmpty(),
            ),
            definitionSeal = binding.definitionDigest?.let { digest ->
                ProcessDefinitionSeal(binding.definitionFiles, digest, binding.definitionDirectories)
            },
        )
        return PreparedProcessExecution(
            plan = request.plan,
            request = request,
        )
    }

    private fun TaskInvocation.redacted(task: ToolingTask, argumentSecrets: Set<String>): TaskInvocation {
        val sensitiveNames = task.inputs.filter { it.sensitive }.mapTo(mutableSetOf()) { it.name }
        return copy(
            inputs = inputs.mapValues { (key, value) -> if (key in sensitiveNames) "[REDACTED]" else value },
            arguments = arguments.map { argument ->
                argumentSecrets.fold(argument) { value, secret -> value.replace(secret, "[REDACTED]") }
            },
        )
    }
}

private fun List<String>.sensitiveArgumentValues(): Set<String> {
    val arguments = this
    return buildSet {
    addAll(arguments.pulumiConfigSetSecretValues())
    arguments.forEachIndexed { index, argument ->
        val normalized = argument.lowercase().replace('_', '-').trimStart('-')
        val isSensitiveFlag = listOf("token", "password", "secret", "api-key", "private-key", "credential")
            .any(normalized::contains)
        if (isSensitiveFlag) {
            argument.substringAfter('=', missingDelimiterValue = "").takeIf(String::isNotBlank)?.let(::add)
            arguments.getOrNull(index + 1)?.takeUnless { it == "--" }?.takeIf(String::isNotBlank)?.let(::add)
        }
        if (argument == "--secret" && arguments.pulumiConfigSetSecretValues().isEmpty())
            arguments.getOrNull(index - 1)?.takeIf(String::isNotBlank)?.let(::add)
    }
    }
}

private fun List<String>.pulumiConfigSetSecretValues(): Set<String> {
    val config = indexOf("config")
    val set = indexOf("set")
    if (config < 0 || set < 0 || none { it == "--secret" || it.startsWith("--secret=") }) return emptySet()
    return drop(set + 1).filterTo(linkedSetOf()) { it.isNotBlank() && it != "--" }
}

private fun List<String>.additionalSafety(): SafetyClass? {
    val words = joinToString(" ").lowercase()
    val normalized = map(String::lowercase)
    val hasProductionSelector = normalized.indices.any { index ->
        val token = normalized[index]
        token in setOf("app_env=prod", "target_env=prod", "--env=prod", "--environment=prod") ||
            token in setOf("app_env", "target_env", "--env", "--environment") &&
                normalized.getOrNull(index + 1) in setOf("prod", "production", "live")
    }
    val hasExternalConfigSelector = normalized.any { token ->
        token == "--config" || token.startsWith("--config=")
    }
    return when {
        listOf("destroy", "delete", "purge", "drop", "teardown", "uninstall").any(words::contains) -> SafetyClass.Destructive
        listOf("migrate", "migration", "schema:apply").any(words::contains) -> SafetyClass.DataMigration
        listOf("secret", "credential", "rotate-key", "rotate_key").any(words::contains) -> SafetyClass.CredentialWrite
        listOf("deploy", "publish", "release", "pulumi up", "wrangler deploy").any(words::contains) -> SafetyClass.ProductionReversibleWrite
        listOf("--remote", "remote_data=true", "session:drive").any(words::contains) -> SafetyClass.UnknownRemoteEffect
        hasProductionSelector || hasExternalConfigSelector ||
            listOf(":prod", ":live").any(words::contains) -> SafetyClass.UnknownRemoteEffect
        else -> null
    }
}

private fun maxSafety(left: SafetyClass, right: SafetyClass): SafetyClass =
    if (left.riskRank() >= right.riskRank()) left else right

private fun SafetyClass.riskRank(): Int = when (this) {
    SafetyClass.ReadOnly -> 0
    SafetyClass.LocalEphemeral -> 1
    SafetyClass.LocalArtifactWrite -> 2
    SafetyClass.LiveRead -> 3
    SafetyClass.DeviceWrite -> 4
    SafetyClass.NonProductionWrite -> 5
    SafetyClass.ProductionReversibleWrite -> 6
    SafetyClass.CredentialWrite -> 7
    SafetyClass.DataMigration -> 8
    SafetyClass.Destructive -> 9
    SafetyClass.UnknownRemoteEffect -> 10
}

internal data class JvmTaskBinding(
    val argv: List<String>,
    val workingDirectory: File,
    val definitionFiles: List<File> = emptyList(),
    val definitionDirectories: List<ProcessDefinitionDirectory> = emptyList(),
    val definitionDigest: String? = null,
    val blockedReason: String? = null,
    val environment: Map<String, String> = emptyMap(),
    val sensitiveEnvironmentKeys: Set<String> = emptySet(),
    val redactions: Set<String> = emptySet(),
    val timeoutMillis: Long? = null,
)

/**
 * Reads package.json, Gradle settings, and target folders already present in a Reaktor workspace.
 * It deliberately does not introduce another manifest or execute project code during discovery.
 */
class JvmProjectDiscovery(
    private val clock: () -> Long = System::currentTimeMillis,
) : WorkspaceDiscoveryPort {
    override suspend fun discover(startPath: String): ToolingCatalog? =
        discoverWorkspace(File(startPath))?.catalog

    fun discoverWorkspace(start: File = File(System.getProperty("user.dir"))): DiscoveredJvmWorkspace? {
        val root = locateRoot(start) ?: return null
        val packageJson = parseObject(File(root, "package.json")) ?: return null
        val reaktor = packageJson["reaktor"] as? JsonObject ?: return null
        val name = reaktor.string("name") ?: packageJson.string("name") ?: root.name
        val scripts = packageJson.stringMap("scripts")
        val workspaces = packageJson.workspaces()
        val declarations = reaktor.targetDeclarations()
        val gradleModules = parseGradleModules(root)
        val targetNames = targetNames(root, workspaces, declarations, gradleModules)
        val workspace = ToolingWorkspace(
            id = WorkspaceId("workspace-${sha256(root.canonicalFile.absolutePath).take(24)}"),
            name = name,
            root = root.canonicalFile.absolutePath,
            kind = WorkspaceKind.Reaktor,
            attributes = mapOf("manifest" to "package.json"),
        )
        val targets = targetNames.map { targetName ->
            val declaration = declarations[targetName]
                ?: declarations.values.firstOrNull { it.workspace?.substringAfterLast('/') == targetName }
            val path = declaration?.workspace ?: "targets/$targetName"
            ToolingTarget(
                id = "target/$targetName",
                name = targetName,
                kind = inferTargetKind(root, targetName, declaration?.runtime),
                path = path,
                runtime = declaration?.runtime,
                attributes = declaration?.declaredName?.let { mapOf("declaration" to it) }.orEmpty(),
            )
        }
        val resources = buildResources(reaktor)
        val taskBuilder = TaskCatalogBuilder(root, workspace, scripts)
        scripts.toSortedMap().forEach { (script, body) ->
            taskBuilder.npmTask(
                id = "npm/$script",
                label = script,
                script = script,
                body = body,
                workingDirectory = root,
                provenancePath = "package.json#scripts.$script",
            )
        }
        targets.forEach { target ->
            val targetDir = File(root, target.path)
            parseObject(File(targetDir, "package.json"))?.stringMap("scripts")?.toSortedMap()?.forEach { (script, body) ->
                taskBuilder.npmTask(
                    id = "target/${target.name}/npm/$script",
                    label = "${target.name}: $script",
                    script = script,
                    body = body,
                    workingDirectory = targetDir,
                    targetId = target.id,
                    provenancePath = "${target.path}/package.json#scripts.$script",
                )
            }
        }
        declarations.values.sortedBy { it.declaredName }.forEach { declaration ->
            val target = targets.firstOrNull {
                it.name == declaration.declaredName || it.path == declaration.workspace
            }
            declaration.actions().forEach { (action, reference) ->
                when {
                    reference in scripts -> taskBuilder.npmTask(
                        id = "target/${target?.name ?: declaration.declaredName}/$action",
                        label = "${target?.name ?: declaration.declaredName}: $action",
                        script = reference,
                        body = scripts.getValue(reference),
                        workingDirectory = root,
                        targetId = target?.id,
                        provenancePath = "package.json#reaktor.targets.${declaration.declaredName}.$action",
                        explicitKind = action.toTaskKind(),
                    )
                    declaration.gradle != null -> taskBuilder.gradleTask(
                        id = "target/${target?.name ?: declaration.declaredName}/$action",
                        label = "${target?.name ?: declaration.declaredName}: $action",
                        gradleTask = declaration.gradle + ":" + action.gradleAction(),
                        targetId = target?.id,
                        provenancePath = "package.json#reaktor.targets.${declaration.declaredName}.$action",
                        explicitKind = action.toTaskKind(),
                    )
                }
            }
        }
        gradleModules.sorted().forEach { module ->
            val target = targets.firstOrNull { it.name == module.substringAfterLast(':') }
            listOf("build", "test", "check").forEach { action ->
                taskBuilder.gradleTask(
                    id = "gradle/$module/$action",
                    label = "$module: $action",
                    gradleTask = ":${module.trim(':')}:$action",
                    targetId = target?.id,
                    provenancePath = "settings.gradle.kts",
                    explicitKind = action.toTaskKind(),
                )
            }
        }
        return DiscoveredJvmWorkspace(
            catalog = ToolingCatalog(
                workspace = workspace,
                targets = targets,
                resources = resources,
                tasks = taskBuilder.tasks.sortedBy { it.id.value },
                providers = taskBuilder.providerStates(),
                generatedAtEpochMillis = clock(),
            ),
            bindings = taskBuilder.bindings,
            clock = clock,
        )
    }

    companion object {
        fun locateRoot(start: File): File? {
            var directory: File? = if (start.isDirectory) start.absoluteFile else start.absoluteFile.parentFile
            while (directory != null) {
                val manifest = File(directory, "package.json")
                val json = parseObject(manifest)
                if (json?.containsKey("reaktor") == true) return directory.canonicalFile
                directory = directory.parentFile
            }
            return null
        }

        internal fun parseObject(file: File): JsonObject? {
            if (!file.isFile) return null
            return runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
        }

        private fun parseGradleModules(root: File): List<String> {
            val settings = File(root, "settings.gradle.kts").takeIf(File::isFile)
                ?: File(root, "settings.gradle").takeIf(File::isFile)
                ?: return emptyList()
            val text = settings.readText()
            val modules = mutableListOf<String>()
            Regex("""\binclude\(([^)]*)\)""").findAll(text).forEach { match ->
                Regex(""""(:[^"]+)"""").findAll(match.groupValues[1]).forEach {
                    modules += it.groupValues[1].trimStart(':')
                }
            }
            Regex("""includeWithPath\(\s*"([^"]+)"""").findAll(text).forEach {
                modules += it.groupValues[1]
            }
            return modules.distinct()
        }

        private fun targetNames(
            root: File,
            workspaces: List<String>,
            declarations: Map<String, TargetDeclaration>,
            gradleModules: List<String>,
        ): List<String> {
            val workspaceTargets = workspaces
                .filter { it.startsWith("targets/") && !it.contains('*') }
                .map { it.substringAfterLast('/') }
            val declaredTargets = declarations.values.map {
                it.workspace?.substringAfterLast('/') ?: it.declaredName
            }
            val gradleTargets = gradleModules.map { it.substringAfterLast(':') }.filter {
                File(root, "targets/$it").isDirectory
            }
            val filesystemTargets = File(root, "targets").listFiles()
                ?.filter(File::isDirectory)
                ?.map(File::getName)
                .orEmpty()
            return (workspaceTargets + declaredTargets + gradleTargets + filesystemTargets)
                .distinct()
                .sorted()
        }

        private fun buildResources(reaktor: JsonObject): List<ToolingResource> {
            val stores = (reaktor["stores"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
                .map { store ->
                    ToolingResource(
                        id = "store/$store",
                        provider = "workspace",
                        kind = "store",
                        name = store,
                    )
                }
            val cloud = (reaktor["cloud"] as? JsonObject)
                ?.mapNotNull { (name, value) ->
                    value.jsonPrimitive.contentOrNull?.let { provider ->
                        ToolingResource(
                            id = "cloud/$name",
                            provider = provider,
                            kind = "cloud-binding",
                            name = name,
                        )
                    }
                }
                .orEmpty()
            return (stores + cloud).sortedBy { it.id }
        }

        private fun inferTargetKind(root: File, name: String, runtime: String?): TargetKind {
            val normalizedRuntime = runtime?.lowercase().orEmpty()
            val targetDir = File(root, "targets/$name")
            return when {
                normalizedRuntime in setOf("android", "ios", "js-web", "web") -> TargetKind.Application
                normalizedRuntime in setOf("worker", "server", "jvm", "k3s") -> TargetKind.Service
                File(targetDir, "wrangler.json").isFile || File(targetDir, "wrangler.jsonc").isFile -> TargetKind.Worker
                name.endsWith("Worker", ignoreCase = true) -> TargetKind.Worker
                name.endsWith("Server", ignoreCase = true) -> TargetKind.Service
                name.startsWith("app", ignoreCase = true) || name.endsWith("Desktop", ignoreCase = true) -> TargetKind.Application
                else -> TargetKind.Unknown
            }
        }
    }
}

private data class TargetDeclaration(
    val declaredName: String,
    val runtime: String?,
    val workspace: String?,
    val gradle: String?,
    val dev: String?,
    val build: String?,
    val deploy: String?,
    val test: String?,
) {
    fun actions(): List<Pair<String, String>> = listOfNotNull(
        dev?.let { "dev" to it },
        build?.let { "build" to it },
        deploy?.let { "deploy" to it },
        test?.let { "test" to it },
    )
}

private class TaskCatalogBuilder(
    private val root: File,
    private val workspace: ToolingWorkspace,
    private val rootScripts: Map<String, String>,
) {
    val tasks = mutableListOf<ToolingTask>()
    val bindings = linkedMapOf<TaskId, JvmTaskBinding>()

    // Gradle tasks share one build-definition closure within this discovery pass. Task-specific
    // provenance still participates in each plan; preparation and admission recapture the seal.
    private val gradleDefinitionSeal: ProcessDefinitionSeal by lazy {
        val wrapper = File(root, if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew")
        val definitionFiles = listOfNotNull(
            wrapper,
            File(root, "settings.gradle.kts").takeIf(File::isFile)
                ?: File(root, "settings.gradle").takeIf(File::isFile),
            File(root, "build.gradle.kts").takeIf(File::isFile)
                ?: File(root, "build.gradle").takeIf(File::isFile),
            File(root, "gradle.properties").takeIf(File::isFile),
        )
        val definitionDirectories = buildList {
            add(ProcessDefinitionDirectory(root, GRADLE_DEFINITION_SUFFIXES))
            File(root, "buildSrc").takeIf(File::isDirectory)?.let {
                add(ProcessDefinitionDirectory(it))
            }
            definitionFiles.filter { it.name.startsWith("settings.gradle") }.forEach { settings ->
                Regex("""includeBuild\s*\(\s*[\"']([^\"']+)[\"']""")
                    .findAll(settings.readText())
                    .map { File(root, it.groupValues[1]) }
                    .filter(File::isDirectory)
                    .forEach { add(ProcessDefinitionDirectory(it, GRADLE_DEFINITION_SUFFIXES)) }
            }
        }
        ProcessDefinitionSeal.capture(definitionFiles, definitionDirectories)
    }

    fun npmTask(
        id: String,
        label: String,
        script: String,
        body: String,
        workingDirectory: File,
        targetId: String? = null,
        provenancePath: String,
        explicitKind: TaskKind? = null,
    ) {
        val taskId = TaskId(id)
        if (bindings.containsKey(taskId)) return
        val safety = safetyFor(script, body)
        val definitionFile = File(workingDirectory, "package.json")
        // A single oversized or otherwise unreadable target must not erase the entire workspace
        // catalog. Keep the task visible but fail its execution closed with an explicit reason.
        val definitionClosure = runCatching {
            transitiveDefinitionClosure(definitionFile, workingDirectory, script, body)
        }.getOrElse { failure ->
            DefinitionClosure(
                files = listOfNotNull(definitionFile.takeIf(File::isFile)),
                directories = emptyList(),
                blockedReason = "Task definition closure could not be sealed: ${failure.message ?: failure::class.simpleName}",
            )
        }
        val capturedSeal = runCatching {
            ProcessDefinitionSeal.capture(definitionClosure.files, definitionClosure.directories)
        }
        val definitionSeal = capturedSeal.getOrElse {
            ProcessDefinitionSeal.capture(files = listOfNotNull(definitionFile.takeIf(File::isFile)))
        }
        val blockedReason = definitionClosure.blockedReason ?: capturedSeal.exceptionOrNull()?.let { failure ->
            "Task definition closure could not be sealed: ${failure.message ?: failure::class.simpleName}"
        }
        val definitionDigest = definitionSeal.digest
        tasks += ToolingTask(
            id = taskId,
            label = label,
            kind = explicitKind ?: script.toTaskKind(),
            provider = "npm",
            targetId = targetId,
            safety = safety,
            provenance = TaskProvenance("package-json", provenancePath, definitionDigest?.take(16)),
            unavailableReason = blockedReason,
            attributes = buildMap {
                put("script", script)
                blockedReason?.let { put("unavailableReason", it) }
            },
        )
        bindings[taskId] = JvmTaskBinding(
            argv = listOf(platformExecutable("npm"), "run", script, "--"),
            workingDirectory = workingDirectory,
            definitionFiles = definitionSeal.files,
            definitionDirectories = definitionSeal.directories,
            definitionDigest = definitionDigest,
            blockedReason = blockedReason,
        )
    }

    fun gradleTask(
        id: String,
        label: String,
        gradleTask: String,
        targetId: String?,
        provenancePath: String,
        explicitKind: TaskKind,
    ) {
        val wrapper = File(root, if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew")
        if (!wrapper.isFile) return
        val taskId = TaskId(id)
        if (bindings.containsKey(taskId)) return
        val definitionSeal = gradleDefinitionSeal
        val definitionDigest = definitionSeal.digest
        tasks += ToolingTask(
            id = taskId,
            label = label,
            kind = explicitKind,
            provider = "gradle",
            targetId = targetId,
            safety = safetyFor(gradleTask, gradleTask),
            provenance = TaskProvenance("gradle-settings", provenancePath, definitionDigest?.take(16)),
            attributes = mapOf("gradleTask" to gradleTask),
        )
        bindings[taskId] = JvmTaskBinding(
            argv = listOf(wrapper.absolutePath, gradleTask),
            workingDirectory = root,
            definitionFiles = definitionSeal.files,
            definitionDirectories = definitionSeal.directories,
            definitionDigest = definitionDigest,
        )
    }

    fun providerStates(): List<ToolingProviderState> {
        val providers = tasks.map { it.provider }.distinct().sorted()
        return providers.map { provider ->
            val available = when (provider) {
                "gradle" -> File(root, "gradlew").isFile || File(root, "gradlew.bat").isFile
                "npm" -> true
                else -> false
            }
            ToolingProviderState(
                provider = provider,
                availability = if (available) ProviderAvailability.Available else ProviderAvailability.Unknown,
                detail = if (provider == "npm") "Resolved from package.json; executable checked at run time" else null,
            )
        }
    }

    private fun safetyFor(name: String, body: String): SafetyPolicy {
        val words = "$name $body".lowercase()
        val tokens = Regex("[a-z0-9_-]+").findAll(words).map { it.value }.toSet()
        val deviceTask = "maestro" in words || "xcrun simctl" in words || "adb " in words
        val remoteHarness = "karate" in words || Regex("(?:^|[:/\\s])k6(?:[:/\\s]|$)").containsMatchIn(words)
        val productionTarget = listOf(
            ":prod", ":live", "app_env=prod", "target_env=prod", " app_env prod", " target_env prod",
        ).any(words::contains)
        val developmentTarget = listOf(
            ":dev", "app_env=dev", "target_env=dev", " app_env dev", " target_env dev",
        ).any(words::contains)
        val safety = when {
            DESTRUCTIVE_WORDS.any(words::contains) -> SafetyClass.Destructive
            MIGRATION_WORDS.any(words::contains) -> SafetyClass.DataMigration
            CREDENTIAL_WORDS.any(words::contains) -> SafetyClass.CredentialWrite
            productionTarget && (deviceTask || remoteHarness) -> SafetyClass.UnknownRemoteEffect
            deviceTask -> SafetyClass.DeviceWrite
            remoteHarness && developmentTarget -> SafetyClass.NonProductionWrite
            remoteHarness -> SafetyClass.UnknownRemoteEffect
            REMOTE_WRITE_WORDS.any(words::contains) -> SafetyClass.ProductionReversibleWrite
            REMOTE_UNKNOWN_WORDS.any(words::contains) -> SafetyClass.UnknownRemoteEffect
            READ_PHRASES.any(words::contains) || READ_TOKENS.any(tokens::contains) -> SafetyClass.ReadOnly
            LOCAL_EPHEMERAL_WORDS.any(words::contains) -> SafetyClass.LocalEphemeral
            LOCAL_ARTIFACT_WORDS.any(words::contains) -> SafetyClass.LocalArtifactWrite
            else -> SafetyClass.UnknownRemoteEffect
        }
        return SafetyPolicy(
            classification = safety,
            reason = when {
                safety.requiresApproval -> "Command name or body indicates a write outside the local workspace"
                else -> null
            },
        )
    }

    private data class DefinitionClosure(
        val files: List<File>,
        val directories: List<ProcessDefinitionDirectory>,
        val blockedReason: String? = null,
    )

    private fun transitiveDefinitionClosure(
        packageFile: File,
        workingDirectory: File,
        script: String,
        body: String,
    ): DefinitionClosure {
        val files = linkedSetOf<File>()
        val directories = linkedSetOf<ProcessDefinitionDirectory>()
        val expandedBody = expandNpmScripts(body)
        var blockedReason: String? = null

        fun addFile(file: File) {
            file.takeIf(File::isFile)?.canonicalFile?.let(files::add)
        }
        fun addDirectory(directory: File, suffixes: Set<String> = emptySet()) {
            directory.takeIf(File::isDirectory)?.let {
                directories += ProcessDefinitionDirectory(it.canonicalFile, suffixes)
            }
        }
        fun resolveLiteral(base: File, path: String): File? {
            if (path.any { it == '$' || it == '{' || it == '}' || it == '*' }) return null
            return File(base, path.trim(' ', '"', '\'')).canonicalFile
        }
        fun addShellClosure(scriptFile: File, visited: MutableSet<File> = mutableSetOf()) {
            val canonical = scriptFile.canonicalFile
            if (!canonical.isFile || !visited.add(canonical)) return
            files += canonical
            val sourceText = canonical.readText()
            SOURCE_DEPENDENCY.findAll(sourceText).forEach { match ->
                val raw = match.groupValues[1].trim(' ', '"', '\'')
                val dependency = when {
                    raw.startsWith("${'$'}{SCRIPT_DIR}/") -> canonical.parentFile.resolve(raw.substringAfter("/"))
                    raw.startsWith("${'$'}SCRIPT_DIR/") -> canonical.parentFile.resolve(raw.substringAfter("/"))
                    raw.contains("${'$'}") -> null
                    else -> canonical.parentFile.resolve(raw)
                }
                dependency?.takeIf(File::isFile)?.let { addShellClosure(it, visited) }
            }
            // BestBuds k3s wrappers use this sibling as their default live client credential.
            // Keep only its private path/digest in the seal; its contents never enter a plan.
            if (
                canonical.path.contains("${File.separator}cloud${File.separator}k3s${File.separator}") &&
                sourceText.contains("kubeconfig")
            ) {
                addFile(canonical.parentFile.resolve("kubeconfig"))
            }
        }

        addFile(packageFile)
        ROOT_DEPENDENCY_FILES.forEach { addFile(File(root, it)) }

        val workspacePackages = linkedSetOf<File>()
        Regex("""--workspace(?:=|\s+)([^\s;&|]+)""").findAll(expandedBody).forEach { match ->
            val workspaceReference = match.groupValues[1].trim('"', '\'')
            resolveWorkspacePackage(workspaceReference)?.canonicalFile?.let {
                files += it
                workspacePackages += it
            }
        }

        LITERAL_DEFINITION_PATH.findAll(expandedBody).forEach { match ->
            resolveLiteral(workingDirectory, match.groupValues[1])?.let { candidate ->
                if (candidate.isFile) {
                    if (candidate.extension in setOf("sh", "zsh")) addShellClosure(candidate) else addFile(candidate)
                }
            }
        }
        TEST_DIRECTORY_PATH.findAll(expandedBody).forEach { match ->
            resolveLiteral(workingDirectory, match.groupValues[1])?.let {
                addDirectory(it, TEST_DEFINITION_SUFFIXES)
            }
        }
        Regex("""(?:^|[;&|'\"]\s*)cd\s+([^;&|]+?)\s*&&\s*([^;&|]+)""")
            .findAll(expandedBody)
            .forEach { match ->
                val nestedDirectory = resolveLiteral(workingDirectory, match.groupValues[1]) ?: return@forEach
                addFile(nestedDirectory.resolve("package.json"))
                LITERAL_DEFINITION_PATH.findAll(match.groupValues[2]).forEach { executable ->
                    resolveLiteral(nestedDirectory, executable.groupValues[1])?.takeIf(File::isFile)?.let { file ->
                        if (file.extension in setOf("sh", "zsh")) addShellClosure(file) else addFile(file)
                    }
                }
            }

        val words = "$script $expandedBody".lowercase()
        if ("fastlane" in words) {
            val fastlaneDirectory = File(root, "fastlane")
            if (!fastlaneDirectory.isDirectory) {
                blockedReason = "Fastlane definitions are not available for exact execution sealing"
            } else {
                addDirectory(fastlaneDirectory)
                listOf(
                    "fastlane/run.sh", "fastlane/Fastfile", "Gemfile", "Gemfile.lock",
                    "targets/appDarwin/Gemfile", "targets/appDarwin/Gemfile.lock",
                ).forEach { addFile(File(root, it)) }
                addDirectory(File(root, "config"), setOf(".env"))
            }
            if (FASTLANE_PUBLISH_WORDS.any(words::contains)) {
                blockedReason = "Production distribution is disabled until its app artifact/source closure is sealed"
            }
        }

        if ("maestro" in words) {
            val definitionText = buildString {
                append(expandedBody)
                File(root, "fastlane/Fastfile").takeIf(File::isFile)?.let { append('\n').append(it.readText()) }
            }
            TEST_DIRECTORY_PATH.findAll(definitionText).forEach { match ->
                resolveLiteral(workingDirectory, match.groupValues[1])?.let {
                    addDirectory(it, TEST_DEFINITION_SUFFIXES)
                }
            }
            listOf("../reaktor/tools/maestro", "tools/maestro").map { File(root, it) }
                .firstOrNull(File::isDirectory)
                ?.let { addDirectory(it, SCRIPT_DEFINITION_SUFFIXES) }
                ?: run { blockedReason = blockedReason ?: "Maestro runner definitions are unavailable" }
        }

        if ("karate" in words || Regex("(?:^|[:/\\s])k6(?:[:/\\s]|${'$'})").containsMatchIn(words)) {
            listOf("karate", "k6").forEach { family ->
                listOf("../reaktor/tools/$family", "tools/$family").map { File(root, it) }
                    .firstOrNull(File::isDirectory)
                    ?.let { addDirectory(it, SCRIPT_DEFINITION_SUFFIXES) }
            }
            if (directories.none { it.directory.path.contains("${File.separator}tests${File.separator}") }) {
                blockedReason = blockedReason ?: "Remote harness flow/config closure could not be bounded"
            }
        }

        val workerDeploy = "wrangler deploy" in words || ("deploy" in words && workspacePackages.isNotEmpty())
        if (workerDeploy) {
            if (workspacePackages.isEmpty() && packageFile.parentFile != root) workspacePackages += packageFile
            if (workspacePackages.isEmpty()) {
                blockedReason = blockedReason ?: "Worker deploy target could not be resolved to a bounded workspace"
            }
            workspacePackages.forEach { manifest ->
                val targetDirectory = manifest.parentFile
                addDirectory(targetDirectory)
                val targetSources = targetDirectory.resolve("src")
                val importsGeneratedBundle = targetSources.walkTopDown()
                    .filter(File::isFile)
                    .any { runCatching { "bestbuds-kt" in it.readText() }.getOrDefault(false) }
                if (importsGeneratedBundle) {
                    val generated = File(root, "modules/app/bestbuds-kt")
                    if (generated.isDirectory) addDirectory(generated)
                    else blockedReason = "Generated worker bundle is unavailable for exact execution sealing"
                }
            }
        }

        return DefinitionClosure(
            files = files.sortedBy(File::getAbsolutePath),
            directories = directories.sortedBy { it.directory.absolutePath },
            blockedReason = blockedReason,
        )
    }

    private fun expandNpmScripts(initialBody: String): String {
        val bodies = mutableListOf(initialBody)
        val visited = mutableSetOf<String>()
        var index = 0
        while (index < bodies.size) {
            NPM_RUN.findAll(bodies[index++]).forEach { match ->
                val child = match.groupValues[1]
                if (visited.add(child)) rootScripts[child]?.let(bodies::add)
            }
        }
        return bodies.joinToString("\n")
    }

    private fun resolveWorkspacePackage(reference: String): File? {
        File(root, reference).resolve("package.json").takeIf(File::isFile)?.let { return it }
        val rootManifest = JvmProjectDiscovery.parseObject(File(root, "package.json")) ?: return null
        return rootManifest.workspaces().asSequence()
            .map { File(root, it).resolve("package.json") }
            .filter(File::isFile)
            .firstOrNull { manifest -> JvmProjectDiscovery.parseObject(manifest)?.string("name") == reference }
    }

    companion object {
        private val DESTRUCTIVE_WORDS = listOf("destroy", "delete", "purge", "drop ", "teardown", "uninstall")
        private val MIGRATION_WORDS = listOf("migrate", "migration", "schema:apply")
        private val CREDENTIAL_WORDS = listOf(
            "secret", "credential", "rotate-key", "rotate_key", "setup-signing", "setup_signing",
            "import-signing", "import_signing", "import-repo-signing", "import_repo_signing",
        )
        private val REMOTE_WRITE_WORDS = listOf(
            "deploy", "publish", "release", "pulumi up", "wrangler deploy", "distribute", "firebase",
            "testflight", "appstore", "play:internal", "play_internal", "play:production", "play_production",
        )
        private val REMOTE_UNKNOWN_WORDS = listOf("--remote", "remote_data=true", "session:drive")
        private val READ_TOKENS = setOf("status", "doctor", "inspect", "list", "logs", "check", "lint", "preview", "whoami", "functions")
        private val READ_PHRASES = listOf("--version", "stack ls")
        private val LOCAL_EPHEMERAL_WORDS = listOf(" dev", "serve", "watch", "vite", "localhost", "node -e")
        private val LOCAL_ARTIFACT_WORDS = listOf("build", "compile", "assemble", "bundle", "package", "generate", "tsc", "gradlew", "vitest", "jest")
        private val FASTLANE_PUBLISH_WORDS = listOf(
            " distribute", " firebase", "play_internal", "play_production", "testflight_", "appstore_",
        )
        private val ROOT_DEPENDENCY_FILES = listOf(
            "package.json", "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "yarn.lock",
        )
        private val NPM_RUN = Regex(
            """(?:^|[;&|]\s*|--\s+)npm\s+run\s+([^\s;&|]+)""",
        )
        private val SOURCE_DEPENDENCY = Regex(
            """(?m)^\s*(?:source|\.)\s+([\"']?[^\s;&|]+[\"']?)""",
        )
        private val LITERAL_DEFINITION_PATH = Regex(
            """(?<![A-Za-z0-9_])((?:\.?\.?/)?[A-Za-z0-9_./-]+\.(?:sh|zsh|rb|mjs|js|cjs|ts|json|jsonc|yaml|yml|env|toml|properties))(?=${'$'}|[\s'\";&|])""",
        )
        private val TEST_DIRECTORY_PATH = Regex(
            """(?<![A-Za-z0-9_./-])((?:\.?\.?/)?tests/[A-Za-z0-9_./-]+)(?=${'$'}|[\s'\";&|])""",
        )
        private val SCRIPT_DEFINITION_SUFFIXES = setOf(
            ".sh", ".zsh", ".rb", ".js", ".mjs", ".cjs", ".json", ".yaml", ".yml", ".env",
        )
        private val TEST_DEFINITION_SUFFIXES = setOf(
            ".yaml", ".yml", ".js", ".mjs", ".cjs", ".json", ".env", ".sh", ".zsh", ".feature",
        )
        private val GRADLE_DEFINITION_SUFFIXES = setOf(
            ".gradle", ".gradle.kts", ".toml", ".properties",
        )

    }
}

fun platformExecutable(name: String, osName: String = System.getProperty("os.name")): String =
    if (osName.startsWith("Windows", ignoreCase = true)) "$name.cmd" else name

private fun JsonObject.string(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

private fun JsonObject.stringMap(name: String): Map<String, String> =
    (this[name] as? JsonObject)?.mapNotNull { (key, value) ->
        value.jsonPrimitive.contentOrNull?.let { key to it }
    }?.toMap().orEmpty()

private fun JsonObject.workspaces(): List<String> = when (val value = this["workspaces"]) {
    is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
    is JsonObject -> (value["packages"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
    else -> emptyList()
}

private fun JsonObject.targetDeclarations(): Map<String, TargetDeclaration> =
    (this["targets"] as? JsonObject)?.mapNotNull { (name, value) ->
        val target = value as? JsonObject ?: return@mapNotNull null
        name to TargetDeclaration(
            declaredName = name,
            runtime = target.string("runtime"),
            workspace = target.string("workspace"),
            gradle = target.string("gradle"),
            dev = target.string("dev"),
            build = target.string("build"),
            deploy = target.string("deploy"),
            test = target.string("test"),
        )
    }?.toMap().orEmpty()

private fun String.toTaskKind(): TaskKind {
    val normalized = lowercase().substringAfterLast(':').substringAfterLast('/')
    return when {
        normalized in setOf("dev", "serve", "watch") -> TaskKind.Develop
        normalized.startsWith("build") || normalized == "assemble" -> TaskKind.Build
        normalized.startsWith("test") -> TaskKind.Test
        normalized in setOf("check", "lint", "verify", "doctor") -> TaskKind.Check
        normalized.startsWith("deploy") || normalized == "publish" || normalized == "release" -> TaskKind.Deploy
        normalized in setOf("logs", "status", "inspect", "preview") -> TaskKind.Observe
        normalized.startsWith("migrate") -> TaskKind.Migrate
        normalized in setOf("delete", "destroy", "purge", "teardown") -> TaskKind.Delete
        normalized in setOf("run", "start") -> TaskKind.Run
        else -> TaskKind.Custom
    }
}

private fun String.gradleAction(): String = when (this) {
    "dev" -> "run"
    else -> this
}

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun digestDefinitionFiles(files: List<File>): String? =
    files.takeIf(List<File>::isNotEmpty)?.let(::processDefinitionDigest)
