package dev.shibasis.reaktor.tooling

import dev.shibasis.reaktor.tooling.delivery.BuildDeclarations
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

    fun prepare(invocation: TaskInvocation, environment: Map<String, String> = emptyMap(),
                sensitiveEnvironmentKeys: Set<String> = emptySet(),
                reviewedPlan: dev.shibasis.dependeasy.model.GradlePlan? = null): PreparedProcessExecution {
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
        val resolvedEnvironment = binding.environment + environment
        val resolvedSensitiveKeys = binding.sensitiveEnvironmentKeys + sensitiveEnvironmentKeys
        val redactions = binding.redactions + inputSecrets + argumentSecrets + resolvedSensitiveKeys.mapNotNull(resolvedEnvironment::get)
        val gradle = task.attributes["buildNode"]?.let { encoded ->
            GradleBuildPlanner().prepare(binding.workingDirectory, binding.argv.first(),
                dev.shibasis.dependeasy.model.BuildModelReader.decodeNode(encoded), invocation.arguments, definitionSeal, redactions, resolvedEnvironment, resolvedSensitiveKeys, reviewedPlan)
        }
        val argv = gradle?.argv ?: (binding.argv + invocation.arguments)
        val request = AdHocProcessPlan.create(
            argv = argv,
            workingDirectory = binding.workingDirectory,
            taskId = task.id,
            safety = task.safety,
            invocation = invocation.redacted(task, argumentSecrets),
            environment = resolvedEnvironment,
            sensitiveEnvironmentKeys = resolvedSensitiveKeys,
            redactions = redactions,
            timeoutMillis = binding.timeoutMillis,
            nowEpochMillis = clock(),
            workspaceId = catalog.workspace.id,
            fingerprintContext = listOf(
                task.provenance.source,
                task.provenance.path.orEmpty(),
                (gradle?.seal ?: definitionSeal).digest,
            ) + gradle?.facts.orEmpty(),
            artifacts = gradle?.artifacts.orEmpty(),
            definitionSeal = gradle?.seal ?: definitionSeal,
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

internal data class JvmTaskBinding(
    val argv: List<String>,
    val workingDirectory: File,
    val definitionSeal: Lazy<ProcessDefinitionSeal>,
    val environment: Map<String, String> = emptyMap(),
    val sensitiveEnvironmentKeys: Set<String> = emptySet(),
    val redactions: Set<String> = emptySet(),
    val timeoutMillis: Long? = null,
)

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
        val reaktor = packageJson["reaktor"] as? JsonObject ?: JsonObject(emptyMap())
        val name = reaktor.string("name") ?: packageJson.string("name") ?: root.name
        val scripts = packageJson.stringMap("scripts")
        val authoredScripts = PackageScriptCommands.authoredScripts(packageJson)
        val workspaces = PackageWorkspaces.paths(root) { file ->
            declarationsText.getOrPut(file.canonicalFile) { file.readText() }
        }
        val targetNames = targetNames(root, workspaces)
        val workspace = ToolingWorkspace(
            id = WorkspaceId("workspace-${sha256(root.canonicalFile.absolutePath).take(24)}"),
            name = name,
            root = root.canonicalFile.absolutePath,
            kind = WorkspaceKind.Reaktor,
            attributes = mapOf("manifest" to "package.json"),
        )
        val targets = targetNames.map { targetName ->
            ToolingTarget(
                id = "target/$targetName",
                name = targetName,
                kind = TargetKind.Unknown,
                path = "targets/$targetName",
            )
        }
        val resources = buildResources(reaktor)
        val taskBuilder = TaskCatalogBuilder(root, scripts, declarationsText)
        authoredScripts.toSortedMap().forEach { (script, body) ->
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
            declaration(File(targetDir, "package.json"))?.let(PackageScriptCommands::authoredScripts)?.toSortedMap()?.forEach { (script, body) ->
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
        runCatching { BuildDeclarations.read(root) }.getOrDefault(emptyList()).also {
            val report = root.resolve(BuildDeclarations.Report).canonicalFile
            if (report.isFile) declarationsText[report] = report.readText()
        }.forEach { target ->
            taskBuilder.gradleTask(target.node.id.toString(), target.node.label, target.task, null,
                target.node.declaredAt?.let { "${it.build}/${it.file}:${it.line}" } ?: BuildDeclarations.Report,
                target.kind, target)
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
                if (json != null && (json.containsKey("reaktor") || readableBuildModel(directory) != null)) return directory.canonicalFile
                directory = directory.parentFile
            }
            return null
        }

        internal fun parseObject(file: File): JsonObject? {
            if (!file.isFile || file.length() > 1_048_576) return null
            return runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
        }

        private fun targetNames(
            root: File,
            workspaces: List<String>,
        ): List<String> {
            val workspaceTargets = workspaces
                .filter { it.startsWith("targets/") && !it.contains('*') }
                .map { it.substringAfterLast('/') }
            val filesystemTargets = File(root, "targets").listFiles()
                ?.filter(File::isDirectory)
                ?.map(File::getName)
                .orEmpty()
            return (workspaceTargets + filesystemTargets)
                .map { name ->
                    val directory = File(root, "targets/$name")
                    filesystemTargets.firstOrNull { physical ->
                        directory.isDirectory && java.nio.file.Files.isSameFile(directory.toPath(), File(root, "targets/$physical").toPath())
                    } ?: name
                }
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
    }
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
        val reports = listOf(BuildDeclarations.Report, BuildDeclarations.Layout)
            .map { root.resolve(it).canonicalFile }.filter { it in declarationsText }
        val expected = reports.associateWith(::declarationDigest)
        checkDeclarations(expected)
        val base = gradleDefinitionSeal.value
        ProcessDefinitionSeal.capture(base.files + reports, base.directories).also { checkDeclarations(expected) }
    }

    fun npmTask(
        id: String,
        label: String,
        script: String,
        body: String,
        workingDirectory: File,
        targetId: String? = null,
        provenancePath: String,
    ) {
        val taskId = TaskId(id)
        if (bindings.containsKey(taskId)) return
        val definitionFile = File(workingDirectory, "package.json")
        val expandedBody = expandPackageScripts(body, workingDirectory)
        val safety = SafetyPolicy(SafetyClass.UnknownRemoteEffect,
            reason = "Authored package command has no typed kernel effect")
        val words = "$script $expandedBody".lowercase()
        val blockedReason = when {
            Regex("(?:^|[\\s/])gradlew(?:\\.bat)?(?:\\s|$)").containsMatchIn(expandedBody) ->
                "Gradle package alias has no sealed generated binding; use its canonical build node"
            "wrangler deploy" in words || ("deploy" in words && PackageScriptCommands.workspaces(expandedBody).isNotEmpty()) ->
                "Package deployment has no sealed generated binding; use its canonical build node"
            else -> null
        }
        val declarations = listOf(File(root, "package.json"), definitionFile, File(root, "pnpm-workspace.yaml"))
            .filter(File::isFile).distinctBy(File::getCanonicalPath)
            .associate { it.canonicalFile to declarationDigest(it) }
        val definitionSeal = lazy {
            synchronized(this) {
                checkDeclarations(declarations)
                val closure = transitiveDefinitionClosure(definitionFile, workingDirectory, body)
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
            kind = TaskKind.Custom,
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
        declaration: BuildDeclarations.Target,
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
            safety = SafetyPolicy(declaration.safety),
            provenance = TaskProvenance("dependeasy", provenancePath),
            attributes = mapOf("gradleTask" to gradleTask,
                "buildNode" to dev.shibasis.dependeasy.model.BuildModelReader.encode(declaration.node)) + declaration.staleAttributes,
            unavailableReason = declaration.unavailableReason,
            environments = declaration.node.environments.map { it.name }.toSet(),
        )
        bindings[taskId] = JvmTaskBinding(
            argv = listOf(wrapper.absolutePath) + declaration.tasks,
            workingDirectory = root,
            definitionSeal = declaredGradleDefinitionSeal,
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

    private data class DefinitionClosure(
        val files: List<File>,
        val directories: List<ProcessDefinitionDirectory>,
    )

    private fun transitiveDefinitionClosure(
        packageFile: File,
        workingDirectory: File,
        body: String,
    ): DefinitionClosure {
        val files = linkedSetOf<File>()
        val directories = linkedSetOf<ProcessDefinitionDirectory>()
        val expandedBody = expandPackageScripts(body, workingDirectory)

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

        PackageScriptCommands.workspaces(expandedBody).forEach { workspaceReference ->
            resolveWorkspacePackage(workspaceReference)?.let(::addFile)
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

        return DefinitionClosure(
            files = files.sortedBy(File::getAbsolutePath),
            directories = directories.sortedBy { it.directory.absolutePath },
        )
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

internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

private fun digestDefinitionFiles(files: List<File>): String? =
    files.takeIf(List<File>::isNotEmpty)?.let(::processDefinitionDigest)
