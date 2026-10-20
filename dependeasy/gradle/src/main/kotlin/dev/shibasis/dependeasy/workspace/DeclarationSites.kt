package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.KernelRef
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.ProcessExecution
import dev.shibasis.dependeasy.model.SourceSite
import org.gradle.api.Project
import org.gradle.api.Task

internal fun Project.declarationSite(): SourceSite? {
    val frames = Throwable().stackTrace
    frames.firstOrNull { it.fileName?.endsWith(".gradle.kts") == true && it.lineNumber > 0 }?.let {
        return SourceSite(rootProject.name, buildFile.relativeTo(rootDir).invariantSeparatorsPath, it.lineNumber)
    }
    val owners = listOf(rootDir) + gradle.includedBuilds.map { it.projectDir }
    return frames.firstNotNullOfOrNull { frame ->
        if (frame.lineNumber <= 0 || frame.fileName?.endsWith(".kt") != true ||
            frame.className.startsWith("dev.shibasis.dependeasy.workspace.")) null
        else owners.firstNotNullOfOrNull { owner ->
            val source = owner.resolve("dependeasy/gradle/src/main/kotlin/" +
                frame.className.substringBeforeLast('.').replace('.', '/') + "/" + frame.fileName)
            source.takeIf { it.isFile }?.let {
                SourceSite(owner.name, it.relativeTo(owner).invariantSeparatorsPath, frame.lineNumber)
            }
        }
    }
}

internal fun kernelRef(task: Task): KernelRef {
    val type = generateSequence<Class<*>>(task.javaClass) { it.superclass }
        .firstOrNull { !it.name.endsWith("_Decorated") } ?: task.javaClass
    val implementation = when (task) {
        is dev.shibasis.dependeasy.module.ResolveJvmEntryTask -> dev.shibasis.dependeasy.model.JvmEntry::class.java
        is dev.shibasis.dependeasy.benchmark.ResolveJvmBenchmarksTask -> dev.shibasis.dependeasy.model.JvmEntry::class.java
        is dev.shibasis.dependeasy.verification.ResolveJvmTestsTask -> dev.shibasis.dependeasy.model.JvmTests::class.java
        is dev.shibasis.dependeasy.darwin.XcodeHostTask, is dev.shibasis.dependeasy.darwin.VerifyXcodeHost -> dev.shibasis.dependeasy.model.AppleHostKernel::class.java
        is dev.shibasis.dependeasy.darwin.ReleasePlanTask -> dev.shibasis.dependeasy.model.AppleReleaseKernel::class.java
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
    return when (this) {
        is KernelSpec.GradleInvocation -> copy(model = path(model), requests = path(requests), output = path(output))
        is KernelSpec.GeneratedObject -> this
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
        is KernelSpec.DependencyBoundary -> this
        is KernelSpec.ExternalCommand -> this
        is KernelSpec.DeviceFlow -> copy(flows = flows.map(::path), contract = path(contract))
        is KernelSpec.KarateSuite -> copy(execution = execution(execution), suites = suites.map(::path))
        is KernelSpec.K6Suite -> copy(execution = execution(execution), script = path(script))
        is KernelSpec.XcodeHost -> copy(files = files.map(::path), signingInclude = path(signingInclude))
        is KernelSpec.ReleasePlan -> copy(output = path(output), xcodeDirectory = path(xcodeDirectory),
            androidArtifacts = androidArtifacts.mapValues { path(it.value) }, apkDirectories = apkDirectories.mapValues { path(it.value) },
            apkMetadata = apkMetadata.map(::path))
        is KernelSpec.DarwinArchive -> copy(ipa = path(ipa), archive = path(archive))
        is KernelSpec.SigningXcconfig -> copy(request = path(request), config = path(config), exportOptions = exportOptions?.let(::path))
        is KernelSpec.PnpmInstall -> copy(execution = execution(execution), manifests = manifests.map(::path))
        is KernelSpec.PnpmLock -> copy(execution = execution(execution))
        is KernelSpec.TypeScriptCheck -> copy(execution = execution(execution))
        is KernelSpec.ViteBundle -> copy(execution = execution(execution), configuration = configuration?.let(::path))
        is KernelSpec.KarakumBindings -> copy(execution = execution(execution), configuration = path(configuration))
        is KernelSpec.WorkerTypes -> copy(execution = execution(execution), configuration = path(configuration))
        is KernelSpec.WranglerDeploy -> copy(execution = execution(execution), configuration = path(configuration))
        is KernelSpec.NodeGenerator -> copy(execution = execution(execution), script = path(script))
        is KernelSpec.PackageCommand -> copy(execution = execution(execution))
        is KernelSpec.Process -> copy(execution = execution(execution))
        is KernelSpec.CMakeBuild -> copy(source = path(source), output = path(output), executable = path(executable),
            arguments = arguments.map(::path), contract = path(contract), ninjaExecutable = path(ninjaExecutable), nativeContract = nativeContract?.let(::path))
        is KernelSpec.GitSource -> copy(repository = path(repository), checkout = path(checkout))
        is KernelSpec.InstallTool -> copy(source = path(source), installation = path(installation))
        is KernelSpec.ArtifactSize -> copy(inputs = inputs.map(::path), report = path(report), fileInputs = fileInputs.map { it.copy(identity = path(it.identity)) })
    }
}

internal fun kernelRef(project: Project, type: Class<*>): KernelRef {
    val sourceOwner = (listOf(project.rootDir) + project.gradle.includedBuilds.map { it.projectDir })
        .firstOrNull { it.resolve("dependeasy/gradle/src/main/kotlin").isDirectory }
    val site = sourceOwner?.let { owner ->
        dev.shibasis.dependeasy.model.JvmEntry.sourceSite(type, owner, owner.name,
            listOf("gradle/src/main/kotlin", "model/src/main/kotlin", "toolchain/src/main/kotlin", "toolchain/src/contracts/kotlin")
                .map { owner.resolve("dependeasy/$it") })
    }
    return KernelRef(type.name, site)
}
