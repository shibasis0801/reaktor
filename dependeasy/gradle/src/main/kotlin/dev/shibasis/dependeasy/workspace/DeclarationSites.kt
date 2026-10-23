package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.KernelRef
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.ProcessExecution
import dev.shibasis.dependeasy.model.SourceSite
import org.gradle.api.Project
import org.gradle.api.Task
import java.io.File

internal fun Project.declarationSite(): SourceSite? {
    val owners = listOf(rootDir) + gradle.includedBuilds.map { it.projectDir }
    val sources by lazy { owners.associateWith { declarationSources(it) } }
    val calls = Throwable().stackTrace.mapNotNull { frame ->
        if (frame.lineNumber <= 0) return@mapNotNull null
        val name = frame.fileName ?: return@mapNotNull null
        if (name.endsWith(".gradle.kts")) {
            SourceSite(rootProject.name, buildFile.relativeTo(rootDir).invariantSeparatorsPath,
                frame.lineNumber, frame.className + "." + frame.methodName)
        } else if (name.endsWith(".kt") && frame.className != "dev.shibasis.dependeasy.workspace.DeclarationSitesKt") {
            val packagePath = frame.className.substringBeforeLast('.', "").replace('.', '/')
            val relative = listOf(packagePath, name).filter(String::isNotEmpty).joinToString("/")
            owners.firstNotNullOfOrNull { owner ->
                listOf("buildSrc/src/main/kotlin", "dependeasy/gradle/src/main/kotlin", "dependeasy/model/src/main/kotlin")
                    .asSequence().map { owner.resolve("$it/$relative") }.firstOrNull { it.absolutePath in sources.getValue(owner) }?.let { source ->
                        SourceSite(if (owner == rootDir) rootProject.name else owner.name,
                            source.relativeTo(owner).invariantSeparatorsPath, frame.lineNumber,
                            frame.className + "." + frame.methodName)
                    }
            }
        } else null
    }.distinct().take(16)
    val declaration = calls.firstOrNull { it.file.endsWith(".gradle.kts") } ?: calls.firstOrNull() ?: return null
    return declaration.copy(callPath = calls.asReversed())
}

internal fun kernelRef(task: Task): KernelRef {
    val type = generateSequence<Class<*>>(task.javaClass) { it.superclass }
        .firstOrNull { !it.name.endsWith("_Decorated") } ?: task.javaClass
    val implementation = when (task) {
        is dev.shibasis.dependeasy.benchmark.JvmBenchmarkKernel -> dev.shibasis.dependeasy.model.Jmh::class.java
        is dev.shibasis.dependeasy.module.JvmClasspathTask -> dev.shibasis.dependeasy.model.JvmLaunchKernel::class.java
        is dev.shibasis.dependeasy.benchmark.ProfileTask -> dev.shibasis.dependeasy.model.JvmProfileKernel::class.java
        is dev.shibasis.dependeasy.module.JvmProgramTask, is dev.shibasis.dependeasy.desktop.PackagedRuntimeTest -> dev.shibasis.dependeasy.model.JvmLaunchKernel::class.java
        is dev.shibasis.dependeasy.verification.JvmTestProgramTask -> dev.shibasis.dependeasy.model.TestEnvironmentKernel::class.java
        is dev.shibasis.dependeasy.module.JvmProcessTask -> dev.shibasis.dependeasy.model.JvmLaunchKernel::class.java
        is dev.shibasis.dependeasy.module.ResolveJvmEntryTask -> dev.shibasis.dependeasy.model.JvmEntry::class.java
        is dev.shibasis.dependeasy.benchmark.ResolveJvmBenchmarksTask -> dev.shibasis.dependeasy.model.JvmEntry::class.java
        is dev.shibasis.dependeasy.verification.ResolveJvmTestsTask -> dev.shibasis.dependeasy.model.JvmTests::class.java
        is dev.shibasis.dependeasy.darwin.XcodeHostTask, is dev.shibasis.dependeasy.darwin.VerifyXcodeHost -> dev.shibasis.dependeasy.model.AppleHostKernel::class.java
        is dev.shibasis.dependeasy.darwin.DarwinArchiveCheck -> dev.shibasis.dependeasy.model.DarwinArchiveKernel::class.java
        is dev.shibasis.dependeasy.darwin.SigningXcconfigTask -> dev.shibasis.dependeasy.model.SigningKernel::class.java
        is dev.shibasis.dependeasy.darwin.ReleasePlanTask -> dev.shibasis.dependeasy.model.AppleReleaseKernel::class.java
        is dev.shibasis.dependeasy.cloudflare.WorkerFleetTask -> dev.shibasis.dependeasy.model.WorkerFleetKernel::class.java
        is dev.shibasis.dependeasy.codegen.KotlinObjectTask -> dev.shibasis.dependeasy.model.KotlinObjectKernel::class.java
        is dev.shibasis.dependeasy.provenance.SourceIdentityTask -> dev.shibasis.dependeasy.model.SourceIdentityKernel::class.java
        is dev.shibasis.dependeasy.settings.GitSourceTask -> dev.shibasis.dependeasy.model.GitSourceKernel::class.java
        is dev.shibasis.dependeasy.toolchain.InstallTool -> dev.shibasis.dependeasy.model.ToolInstallKernel::class.java
        is dev.shibasis.dependeasy.verification.DependencyBoundary -> dev.shibasis.dependeasy.model.DependencyBoundaryKernel::class.java
        is dev.shibasis.dependeasy.codegen.KotlinTemplateTask -> dev.shibasis.dependeasy.model.KotlinTemplateKernel::class.java
        is dev.shibasis.dependeasy.web.WebContractTask -> dev.shibasis.dependeasy.model.WebContractKernel::class.java
        is dev.shibasis.dependeasy.web.WebResourcesTask -> dev.shibasis.dependeasy.model.WebResourcesKernel::class.java
        is dev.shibasis.dependeasy.web.KotlinExportCatalog -> dev.shibasis.dependeasy.model.KotlinExportCatalogKernel::class.java
        is dev.shibasis.dependeasy.web.KotlinPackageExport -> dev.shibasis.dependeasy.model.KotlinPackageKernel::class.java
        is dev.shibasis.dependeasy.tasks.KotlinCMakeTask -> dev.shibasis.dependeasy.model.CMakeBuildKernel::class.java
        is dev.shibasis.dependeasy.tasks.ExtractPrefabTask -> dev.shibasis.dependeasy.model.PrefabKernel::class.java
        is dev.shibasis.dependeasy.codegen.PulumiSdkTask -> dev.shibasis.dependeasy.model.PulumiSdkKernel::class.java
        is ExportBuildModelTask -> dev.shibasis.dependeasy.model.BuildModelKernel::class.java
        is PackageScriptsTask -> dev.shibasis.dependeasy.model.PackageScriptsKernel::class.java
        is dev.shibasis.dependeasy.dagger.GenerateDaggerTask, is dev.shibasis.dependeasy.dagger.PrepareContainerNode -> dev.shibasis.dependeasy.model.DaggerKernel::class.java
        is dev.shibasis.dependeasy.web.TypeScriptCheck, is dev.shibasis.dependeasy.web.ViteBundle,
        is dev.shibasis.dependeasy.web.KarakumBindings, is dev.shibasis.dependeasy.web.NodeGenerator,
        is dev.shibasis.dependeasy.web.PnpmInstallTask -> dev.shibasis.dependeasy.model.JavaScriptKernel::class.java
        is dev.shibasis.dependeasy.verification.DeviceFlow -> dev.shibasis.dependeasy.model.DeviceFlowKernel::class.java
        is dev.shibasis.dependeasy.verification.KarateSuite -> dev.shibasis.dependeasy.model.KarateSuiteKernel::class.java
        is dev.shibasis.dependeasy.verification.K6Suite -> dev.shibasis.dependeasy.model.K6SuiteKernel::class.java
        is dev.shibasis.dependeasy.web.WranglerDeploy -> dev.shibasis.dependeasy.model.WorkerDeployKernel::class.java
        is dev.shibasis.dependeasy.cloudflare.WorkerSecrets -> dev.shibasis.dependeasy.model.WorkerSecretsKernel::class.java
        is PulumiInfrastructure -> dev.shibasis.dependeasy.model.PulumiStackKernel::class.java
        is DatabaseMigration -> if (task.spec.database is dev.shibasis.dependeasy.model.DatabaseMigrations.ClickHouse)
            dev.shibasis.dependeasy.model.ClickHouseMigrationKernel::class.java else dev.shibasis.dependeasy.model.MigrationKernel::class.java
        is dev.shibasis.dependeasy.cloudflare.D1Migration -> dev.shibasis.dependeasy.model.D1MigrationKernel::class.java
        is dev.shibasis.dependeasy.server.BuildServerImage, is dev.shibasis.dependeasy.server.PushServerImage -> dev.shibasis.dependeasy.model.ServerImageKernel::class.java
        is dev.shibasis.dependeasy.server.RolloutServer -> dev.shibasis.dependeasy.model.ServerRolloutKernel::class.java
        is dev.shibasis.dependeasy.process.ProcessTask -> dev.shibasis.dependeasy.model.ProcessKernel::class.java
        is WorkspaceNodeTask -> task.kernel.javaClass
        is GradleInvocationTask -> dev.shibasis.dependeasy.model.GradleInvocationKernel::class.java
        is dev.shibasis.dependeasy.tasks.ArtifactSizeReport -> dev.shibasis.dependeasy.model.ArtifactSizeKernel::class.java
        else -> type
    }
    return kernelRef(task.project, implementation).copy(spec = ((task as? dev.shibasis.dependeasy.process.KernelSpecOwner)?.kernelSpec
        ?: task.extensions.findByType(KernelSpec::class.java))?.relativeTo(task.project.rootDir))
}

private fun KernelSpec.relativeTo(root: java.io.File): KernelSpec {
    val owner = root.canonicalPath
    val workspace = root.canonicalFile.parentFile.path + java.io.File.separator
    fun path(value: String): String = if (value == owner) "." else value.replace(owner + java.io.File.separator, "").replace(workspace, "../")
    fun execution(value: ProcessExecution) = value.copy(directory = path(value.directory), executable = path(value.executable),
        arguments = value.arguments.map(::path), inputs = value.inputs.map(::path), outputs = value.outputs.map(::path),
        fileInputs = value.fileInputs.map { it.copy(identity = path(it.identity)) })
    fun files(value: dev.shibasis.dependeasy.model.FileSet) = value.copy(paths = value.paths.map(::path),
        declarations = value.declarations.map { it.copy(identity = path(it.identity)) })
    return when (this) {
        is KernelSpec.Profile -> copy(sources = files(sources), output = files(output), library = files(library), api = files(api))
        is KernelSpec.GradleInvocation -> copy(model = path(model), requests = path(requests), output = path(output))
        is KernelSpec.GeneratedObject -> copy(workerFleet = files(workerFleet))
        is KernelSpec.SourceIdentity -> copy(sources = sources.map { it.copy(directory = path(it.directory)) },
            revisions = revisions.map { it.copy(directory = path(it.directory)) })
        is KernelSpec.JvmClasspath -> this
        is KernelSpec.ResolveJvmEntry -> this
        is KernelSpec.ResolveJvmTests -> this
        is KernelSpec.ResolveJvmBenchmarks -> this
        is KernelSpec.Jmh -> this
        is KernelSpec.JvmProgram -> this
        is KernelSpec.JvmCommand -> this
        is KernelSpec.JvmLauncher -> this
        is KernelSpec.JvmDistribution -> this
        is KernelSpec.TestSuite -> this
        is KernelSpec.WorkspaceNode -> this
        is KernelSpec.DatabaseMigration -> when (val database = migration.database) {
            is dev.shibasis.dependeasy.model.DatabaseMigrations.Postgres -> this
            is dev.shibasis.dependeasy.model.DatabaseMigrations.ClickHouse -> copy(migration = migration.copy(kubeconfig = migration.kubeconfig?.let(::path)))
            is dev.shibasis.dependeasy.model.DatabaseMigrations.D1 -> copy(migration = migration.copy(database = database.copy(
                deployment = database.deployment.copy(execution = execution(database.deployment.execution), configuration = path(database.deployment.configuration)))))
        }
        is KernelSpec.WorkerFleet -> this
        is KernelSpec.BuildModelExport -> copy(declarations = files(declarations), includedModels = files(includedModels), output = files(output), routing = files(routing))
        is KernelSpec.PackageScripts -> copy(output = files(output), packageContracts = packageContracts.mapValues { files(it.value) })
        is KernelSpec.DaggerPipeline -> copy(output = files(output), sharedDirectory = files(sharedDirectory))
        is KernelSpec.ContainerNode -> copy(model = files(model), output = files(output))
        is KernelSpec.WebContract -> copy(directory = path(directory), manifests = files(manifests), wrappers = files(wrappers),
            runtime = files(runtime), baseConfiguration = files(baseConfiguration), output = files(output),
            typeScriptConfiguration = files(typeScriptConfiguration), sourceManifests = files(sourceManifests),
            packageContracts = files(packageContracts), packageLocations = packageLocations.mapValues { path(it.value) },
            workspaceMetadata = files(workspaceMetadata), workspaceDefinition = files(workspaceDefinition), workerFleet = files(workerFleet))
        is KernelSpec.WebResources -> copy(sources = files(sources), output = files(output))
        is KernelSpec.KotlinPackage -> copy(sources = files(sources), destination = files(destination), dependencies = files(dependencies), receipt = files(receipt))
        is KernelSpec.Prefab -> copy(archive = files(archive), output = files(output))
        is KernelSpec.PulumiSdk -> copy(executable = path(executable), output = files(output))
        is KernelSpec.KotlinTemplate -> copy(template = template.copy(paths = template.paths.map(::path)),
            assets = assets.mapValues { it.value.copy(paths = it.value.paths.map(::path)) }, output = output.copy(paths = output.paths.map(::path)))
        is KernelSpec.KotlinExportCatalog -> copy(output = output.copy(paths = output.paths.map(::path)))
        is KernelSpec.DependencyBoundary -> this
        is KernelSpec.ExternalCommand -> this
        is KernelSpec.DeviceFlow -> copy(flows = flows.map(::path), contract = path(contract))
        is KernelSpec.KarateSuite -> copy(execution = execution(execution), suites = suites.map(::path), jar = path(jar), report = path(report), arguments = arguments.map(::path))
        is KernelSpec.K6Suite -> copy(execution = execution(execution), script = path(script), summary = path(summary), arguments = arguments.map(::path))
        is KernelSpec.XcodeHost -> copy(files = files.map(::path), signingInclude = path(signingInclude))
        is KernelSpec.ReleasePlan -> copy(output = path(output), xcodeDirectory = path(xcodeDirectory),
            androidArtifacts = androidArtifacts.mapValues { path(it.value) }, apkDirectories = apkDirectories.mapValues { path(it.value) },
            apkMetadata = apkMetadata.map(::path), androidFirebaseConfiguration = androidFirebaseConfiguration?.let(::path))
        is KernelSpec.DarwinArchive -> copy(ipa = path(ipa), archive = path(archive), firebaseConfig = path(firebaseConfig),
            uploader = path(uploader), scratch = path(scratch), receipt = path(receipt), qualification = qualification?.let(::path),
            providerResult = providerResult?.let(::path), packagedFirebaseConfig = packagedFirebaseConfig?.let(::path))
        is KernelSpec.SigningXcconfig -> copy(request = path(request), config = path(config), exportOptions = exportOptions?.let(::path))
        is KernelSpec.PnpmInstall -> copy(execution = execution(execution), manifests = manifests.map(::path))
        is KernelSpec.PnpmLock -> copy(execution = execution(execution))
        is KernelSpec.TypeScriptCheck -> copy(execution = execution(execution), adapter = path(adapter))
        is KernelSpec.ViteBundle -> copy(execution = execution(execution), configuration = configuration?.let(::path))
        is KernelSpec.KarakumBindings -> copy(execution = execution(execution), configuration = path(configuration))
        is KernelSpec.WorkerTypes -> copy(execution = execution(execution), configuration = path(configuration))
        is KernelSpec.WranglerDeploy -> copy(deployment = deployment.copy(execution = execution(deployment.execution), configuration = path(deployment.configuration)))
        is KernelSpec.PulumiStack -> copy(infrastructure = infrastructure.copy(execution = execution(infrastructure.execution),
            programDirectory = infrastructure.programDirectory?.let(::path)))
        is KernelSpec.WorkerSecrets -> copy(secrets = secrets.copy(deployment = secrets.deployment.copy(
            execution = execution(secrets.deployment.execution), configuration = path(secrets.deployment.configuration))))
        is KernelSpec.ServerImageBuild -> copy(image = image.copy(jar = path(image.jar)))
        is KernelSpec.ServerImagePush -> copy(image = image.copy(jar = path(image.jar)), built = path(built))
        is KernelSpec.ServerRollout -> copy(deployment = deployment.copy(execution = execution(deployment.execution), kubeconfig = path(deployment.kubeconfig)), published = path(published))
        is KernelSpec.NodeGenerator -> copy(execution = execution(execution), script = path(script), arguments = arguments.map(::path))
        is KernelSpec.PackageCommand -> copy(execution = execution(execution))
        is KernelSpec.Process -> copy(execution = execution(execution))
        is KernelSpec.CMakeBuild -> copy(source = path(source), output = path(output), executable = path(executable),
            arguments = arguments.map(::path), contract = path(contract), ninjaExecutable = path(ninjaExecutable), nativeContract = nativeContract?.let(::path), nativeConfiguration = nativeConfiguration?.let(::path))
        is KernelSpec.GitSource -> copy(repository = path(repository), checkout = path(checkout))
        is KernelSpec.InstallTool -> copy(source = path(source), installation = path(installation))
        is KernelSpec.ArtifactSize -> copy(inputs = inputs.map(::path), report = path(report), fileInputs = fileInputs.map { it.copy(identity = path(it.identity)) })
    }
}

internal fun Project.declarationSources(owner: File): Set<String> {
    val key = "dependeasy.declarationSources:${owner.absolutePath}"
    val metadata = rootProject.extensions.extraProperties
    @Suppress("UNCHECKED_CAST")
    return if (metadata.has(key)) metadata.get(key) as Set<String> else {
        providers.of(BuildDeclarationFiles::class.java) { parameters.root.set(owner) }.get()
            .map { it.absolutePath }.toSet().also { metadata.set(key, it) }
    }
}

internal fun kernelRef(project: Project, type: Class<*>): KernelRef {
    val sourceOwner = (listOf(project.rootDir) + project.gradle.includedBuilds.map { it.projectDir })
        .firstOrNull { owner ->
            val prefix = owner.resolve("dependeasy/gradle/src/main/kotlin").absolutePath + File.separator
            project.declarationSources(owner).any { it.startsWith(prefix) }
        }
    val site = sourceOwner?.let { owner ->
        dev.shibasis.dependeasy.model.JvmEntry.sourceSite(type, owner, owner.name,
            listOf("gradle/src/main/kotlin", "model/src/main/kotlin", "toolchain/src/main/kotlin", "toolchain/src/contracts/kotlin")
                .map { owner.resolve("dependeasy/$it") }, project.declarationSources(owner))
    }
    return KernelRef(type.name, site)
}
