package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.delivery.GradleBuildTargets
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
        val definitionSeal = binding.definitionSeal.value
        check(definitionSeal.currentDigest() == definitionSeal.digest) {
            "Task definition changed after planning; refresh the catalog before planning '${task.id.value}'"
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
                definitionSeal.digest,
            ),
            definitionSeal = definitionSeal,
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
    val definitionSeal: Lazy<ProcessDefinitionSeal>,
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
        val declarationsText = mutableMapOf<File, String>()
        fun declaration(file: File): JsonObject? {
            if (!file.isFile || file.length() > 1_048_576) return null
            return runCatching {
                val text = declarationsText.getOrPut(file.canonicalFile) { file.readText() }
                Json.parseToJsonElement(text).jsonObject
            }.getOrNull()
        }
        val packageJson = declaration(File(root, "package.json")) ?: return null
        val reaktor = packageJson["reaktor"] as? JsonObject ?: return null
        val name = reaktor.string("name") ?: packageJson.string("name") ?: root.name
        val scripts = packageJson.stringMap("scripts")
        val workspaces = PackageWorkspaces.paths(root) { file ->
            declarationsText.getOrPut(file.canonicalFile) { file.readText() }
        }
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
        val taskBuilder = TaskCatalogBuilder(root, scripts, declarationsText)
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
            declaration(File(targetDir, "package.json"))?.stringMap("scripts")?.toSortedMap()?.forEach { (script, body) ->
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
        GradleBuildTargets.read(root) { file ->
            declarationsText.getOrPut(file.canonicalFile) { file.readText() }
        }.forEach { target ->
            taskBuilder.gradleTask("dependeasy${target.task}", target.task.removePrefix(":"), target.task, null,
                GradleBuildTargets.Report, target.kind, target)
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
            if (!file.isFile || file.length() > 1_048_576) return null
            return runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
        }

        private fun parseGradleModules(root: File): List<String> {
            val settings = File(root, "settings.gradle.kts").takeIf(File::isFile)
                ?: File(root, "settings.gradle").takeIf(File::isFile)
                ?: return emptyList()
            if (settings.length() > 1_048_576) return emptyList()
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
    private val rootScripts: Map<String, String>,
    private val declarationsText: Map<File, String>,
) {
    val tasks = mutableListOf<ToolingTask>()
    val bindings = linkedMapOf<TaskId, JvmTaskBinding>()
    private val declarationDigests = mutableMapOf<File, String>()
    private val sourceText = mutableMapOf<File, Pair<String, String>>()
    private val gradleDefinitionSeal = lazy {
        val declarations = mapOf(File(root, "package.json").canonicalFile to declarationDigest(File(root, "package.json")))
        checkDeclarations(declarations)
        val wrapper = File(root, if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew")
        val definitionFiles = listOfNotNull(
            wrapper,
            File(root, "settings.gradle.kts").takeIf(File::isFile)
                ?: File(root, "settings.gradle").takeIf(File::isFile),
            File(root, "build.gradle.kts").takeIf(File::isFile)
                ?: File(root, "build.gradle").takeIf(File::isFile),
            File(root, "gradle.properties").takeIf(File::isFile),
        )
        val metadata = definitionFiles.associateWith(DefinitionDigestCache::metadataOf)
        val definitionDirectories = buildList {
            add(ProcessDefinitionDirectory(root, GRADLE_DEFINITION_SUFFIXES))
            File(root, "buildSrc").takeIf(File::isDirectory)?.let {
                add(ProcessDefinitionDirectory(it))
            }
            gradleSourceRoots(root).roots.filter(File::isDirectory)
                .forEach { add(ProcessDefinitionDirectory(it, GRADLE_DEFINITION_SUFFIXES)) }
        }
        ProcessDefinitionSeal.capture(definitionFiles, definitionDirectories).also {
            checkDeclarations(declarations)
            check(metadata.all { (file, identity) -> DefinitionDigestCache.metadataOf(file) == identity }) {
                "Build definitions changed while planning; refresh the catalog"
            }
        }
    }

    private val declaredGradleDefinitionSeal = lazy {
        val report = root.resolve(GradleBuildTargets.Report).canonicalFile
        val expected = mapOf(report to declarationDigest(report))
        checkDeclarations(expected)
        val base = gradleDefinitionSeal.value
        ProcessDefinitionSeal.capture(base.files + report, base.directories).also { checkDeclarations(expected) }
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
        val definitionFile = File(workingDirectory, "package.json")
        val expandedBody = expandPackageScripts(body, workingDirectory)
        val safety = safetyFor(script, expandedBody)
        val words = "$script $expandedBody".lowercase()
        val blockedReason = when {
            "fastlane" in words && FASTLANE_PUBLISH_WORDS.any(words::contains) ->
                "Production distribution is disabled until its app artifact/source closure is sealed"
            "fastlane" in words && !File(root, "fastlane").isDirectory ->
                "Fastlane definitions are not available for exact execution sealing"
            ("karate" in words || Regex("(?:^|[:/\\s])k6(?:[:/\\s]|${'$'})").containsMatchIn(words)) &&
                TEST_DIRECTORY_PATH.findAll(expandedBody).none { File(workingDirectory, it.groupValues[1]).isDirectory } ->
                "Remote harness flow/config closure could not be bounded"
            "wrangler deploy" in words && definitionFile.parentFile == root && PackageScriptCommands.workspaces(expandedBody).isEmpty() ->
                "Worker deploy target could not be resolved to a bounded workspace"
            else -> null
        }
        val declarations = listOf(File(root, "package.json"), definitionFile, File(root, "pnpm-workspace.yaml"))
            .filter(File::isFile).distinctBy(File::getCanonicalPath)
            .associate { it.canonicalFile to declarationDigest(it) }
        val definitionSeal = lazy {
            synchronized(this) {
                checkDeclarations(declarations)
                val closure = transitiveDefinitionClosure(definitionFile, workingDirectory, script, body)
                check(closure.blockedReason == null) { requireNotNull(closure.blockedReason) }
                ProcessDefinitionSeal.capture(closure.files, closure.directories).also {
                    checkDeclarations(declarations)
                    check(sourceText.all { (file, cached) -> file.isFile && DefinitionDigestCache.metadataOf(file) == cached.first }) {
                        "Task sources changed while planning; refresh the catalog"
                    }
                }
            }
        }
        tasks += ToolingTask(
            id = taskId,
            label = label,
            kind = explicitKind ?: script.toTaskKind(),
            provider = "npm",
            targetId = targetId,
            safety = safety,
            provenance = TaskProvenance("package-json", provenancePath, declarationDigest(definitionFile).take(16)),
            unavailableReason = blockedReason,
            attributes = mapOf("script" to script),
        )
        val packageArgv = packageScriptArgv(workingDirectory, script)
        bindings[taskId] = JvmTaskBinding(
            argv = packageArgv,
            environment = if (File(packageArgv.first()).isAbsolute) mapOf(
                "PATH" to "${File(packageArgv.first()).parent}${File.pathSeparator}${System.getenv("PATH").orEmpty()}",
            ) else emptyMap(),
            workingDirectory = workingDirectory,
            definitionSeal = definitionSeal,
        )
    }

    fun gradleTask(
        id: String,
        label: String,
        gradleTask: String,
        targetId: String?,
        provenancePath: String,
        explicitKind: TaskKind,
        declaration: GradleBuildTargets.Target? = null,
    ) {
        val wrapper = File(root, if (System.getProperty("os.name").startsWith("Windows")) "gradlew.bat" else "gradlew")
        if (!wrapper.isFile) return
        val taskId = TaskId(id)
        if (bindings.containsKey(taskId)) return
        tasks += ToolingTask(
            id = taskId,
            label = label,
            kind = explicitKind,
            provider = "gradle",
            targetId = targetId,
            safety = safetyFor(if (declaration == null) gradleTask else "$explicitKind $gradleTask", gradleTask),
            provenance = TaskProvenance(if (declaration == null) "gradle-settings" else "dependeasy", provenancePath),
            attributes = mapOf("gradleTask" to gradleTask),
            unavailableReason = declaration?.unavailableReason,
        )
        bindings[taskId] = JvmTaskBinding(
            argv = listOf(wrapper.absolutePath, gradleTask),
            workingDirectory = root,
            definitionSeal = if (declaration == null) gradleDefinitionSeal else declaredGradleDefinitionSeal,
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
        val expandedBody = expandPackageScripts(body, workingDirectory)
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
            val sourceText = text(canonical)
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
        parse(packageFile)?.let { manifest ->
            dev.shibasis.reaktor.tooling.delivery.GradlePackageTargets.workerDeploy(root, manifest, script, body)
        }?.let { binding ->
            addFile(binding.directory.resolve("package.json"))
            workspacePackages += binding.directory.resolve("package.json").canonicalFile
            val gradle = gradleDefinitionSeal.value
            files.addAll(gradle.files)
            directories.addAll(gradle.directories)
            gradleSourceRoots(root).roots.forEach { source ->
                val owner = if (source.name == "dependeasy") source else source.resolve("dependeasy")
                addDirectory(owner, SCRIPT_DEFINITION_SUFFIXES + setOf(".kt", ".ts"))
            }
            addFile(root.resolve("wrangler.json"))
        }
        PackageScriptCommands.workspaces(expandedBody).forEach { workspaceReference ->
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
                File(root, "fastlane/Fastfile").takeIf(File::isFile)?.let { append('\n').append(text(it)) }
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
                generatedWorkerFiles(targetSources).forEach(::addFile)
            }
        }

        return DefinitionClosure(
            files = files.sortedBy(File::getAbsolutePath),
            directories = directories.sortedBy { it.directory.absolutePath },
            blockedReason = blockedReason,
        )
    }

    private fun generatedWorkerFiles(source: File): List<File> {
        val imports = if (!source.isDirectory) emptySet() else DefinitionDigestCache.listing(source,
            setOf(".ts", ".tsx", ".js", ".jsx", ".mjs", ".cjs"), setOf("node_modules", "build", "dist", ".git"))
            .flatMap { file -> WORKER_BUNDLE_IMPORT.findAll(text(file)).map { it.groupValues[1] }.toList() }.toSet()
        if (imports.isEmpty()) return emptyList()
        val bundle = File(root, "modules/app/bestbuds-kt").canonicalFile
        require(bundle.toPath().startsWith(root.canonicalFile.toPath())) { "Generated worker bundle escapes the workspace" }
        val manifest = File(root, "modules/app/bestbuds-kt.manifest.json").canonicalFile
        require(manifest.toPath().startsWith(root.canonicalFile.toPath()) && manifest.isFile) {
            "Generated worker bundle manifest is unavailable; run :app:jsBrowserProductionLibraryDistribution"
        }
        require(manifest.length() <= 32L * 1024 * 1024) { "Generated worker bundle manifest is too large" }
        val workerBundleManifest = Json.parseToJsonElement(text(manifest)).jsonObject
        require(workerBundleManifest.string("format") == "1") { "Unsupported generated worker bundle manifest" }
        val workerBundleFiles = mutableMapOf<String, List<File>>()
        val workerModuleImports = mutableMapOf<File, List<File>>()
        return listOf(manifest) + imports.flatMap { imported ->
            workerBundleFiles.getOrPut(imported) {
                val entries = (workerBundleManifest["imports"] as? JsonObject)?.get(imported) as? JsonArray
                    ?: error("Generated worker bundle manifest has no $imported; rebuild :app:jsBrowserProductionLibraryDistribution")
                require(entries.isNotEmpty() && entries.size <= 20_000) { "Generated worker import closure is empty or too large: $imported" }
                val members = entries.map { element ->
                    val entry = element.jsonObject
                    val path = entry.string("path") ?: error("Generated worker bundle manifest has no path")
                    require(!File(path).isAbsolute && path.split('/', '\\').none { it == ".." }) { "Generated worker bundle manifest path escapes the bundle" }
                    val file = bundle.resolve(path).canonicalFile
                    require(file.toPath().startsWith(bundle.toPath()) && file.isFile) { "Generated worker bundle member is missing or outside the bundle: $path" }
                    val digest = entry.string("sha256") ?: error("Generated worker bundle manifest has no digest")
                    require(digest.matches(Regex("[a-f0-9]{64}")) && DefinitionDigestCache.digestOf(file) == digest) {
                        "Generated worker bundle manifest is stale: $path; rebuild :app:jsBrowserProductionLibraryDistribution"
                    }
                    file
                }
                val packageFile = bundle.resolve("package.json")
                val entryFile = if (imported == "bestbuds-kt") bundle.resolve(
                    parse(packageFile)?.string("main") ?: error("Generated worker bundle has no main module")
                ) else bundle.resolve(imported.substringAfter("bestbuds-kt/"))
                val included = members.toSet()
                require(packageFile.canonicalFile in included && entryFile.canonicalFile in included) { "Generated worker bundle manifest omits its entry or package metadata: $imported" }
                members.filter { it.extension in setOf("mjs", "js", "cjs") }.forEach { member ->
                    val dependencies = workerModuleImports.getOrPut(member) {
                        WORKER_RELATIVE_IMPORT.findAll(text(member)).map { match ->
                            member.parentFile.resolve(match.groupValues[1]).canonicalFile
                        }.toList()
                    }
                    require(dependencies.all { it in included }) { "Generated worker bundle manifest omits a dependency of ${member.relativeTo(bundle)}" }
                }
                members
            }
        }
    }

    private fun declarationDigest(file: File): String = declarationDigests.getOrPut(file.canonicalFile) {
        sha256(declarationsText.getValue(file.canonicalFile))
    }

    private fun checkDeclarations(expected: Map<File, String>) {
        check(expected.all { (file, digest) -> file.isFile && sha256(file.readText()) == digest }) {
            "Task declarations changed after catalog discovery; refresh the catalog before planning"
        }
    }

    private fun text(file: File): String {
        val canonical = file.canonicalFile
        val metadata = DefinitionDigestCache.metadataOf(canonical)
        sourceText[canonical]?.takeIf { it.first == metadata }?.let { return it.second }
        return canonical.readText().also {
            check(DefinitionDigestCache.metadataOf(canonical) == metadata) { "Task source changed while reading; refresh the catalog" }
            sourceText[canonical] = metadata to it
        }
    }

    private fun parse(file: File): JsonObject? = if (!file.isFile) null else
        runCatching { Json.parseToJsonElement(text(file)).jsonObject }.getOrNull()

    private fun expandPackageScripts(body: String, directory: File): String =
        PackageScriptCommands.expand(body) { reference ->
            val manifest = reference.workspace?.let(::resolveWorkspacePackage)
                ?: File(directory, "package.json")
            parse(manifest)?.stringMap("scripts")?.get(reference.script)
        }

    private fun packageScriptArgv(directory: File, script: String): List<String> {
        val manager = (parse(File(directory, "package.json"))?.string("packageManager")
            ?: parse(File(root, "package.json"))?.string("packageManager"))?.substringBefore('@')
            ?: if (File(root, "pnpm-lock.yaml").isFile) "pnpm" else "npm"
        require(manager in setOf("pnpm", "npm")) { "Unsupported package manager: $manager" }
        val managed = File(root, "build/dependeasy/tools/javascript/bin/${platformExecutable(manager)}")
        val executable = if (managed.isFile && managed.canExecute()) managed.absolutePath else platformExecutable(manager)
        return listOf(executable, "run", script) + if (manager == "npm") listOf("--") else emptyList()
    }

    private fun resolveWorkspacePackage(reference: String): File? {
        File(root, reference).resolve("package.json").takeIf(File::isFile)?.let { return it }
        return PackageWorkspaces.paths(root, ::text).asSequence()
            .map { File(root, it).resolve("package.json") }
            .filter(File::isFile)
            .firstOrNull { manifest -> parse(manifest)?.string("name") == reference }
    }

    companion object {
        private val WORKER_BUNDLE_IMPORT = Regex("""(?:from\s*|import\s*\(\s*|require\s*\(\s*|import\s*)['"](bestbuds-kt(?:/[^'"]+)?)['"]""")
        private val WORKER_RELATIVE_IMPORT = Regex("""(?:from\s*|import\s*\(\s*|require\s*\(\s*|import\s*|new\s+URL\s*\(\s*)['"](\.[^'"]+)['"]""")
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
            " distribute", " firebase", "play_internal", "play_production", "testflight_", "appstore_", " release_",
        )
        private val ROOT_DEPENDENCY_FILES = listOf(
            "package.json", "package-lock.json", "npm-shrinkwrap.json", "pnpm-lock.yaml", "pnpm-workspace.yaml", ".npmrc", "yarn.lock",
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
