package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.KernelRef
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.ProcessExecution
import dev.shibasis.dependeasy.model.SourceSite
import org.gradle.api.Project
import org.gradle.api.Task

internal fun Project.declarationSite(): SourceSite? = Throwable().stackTrace
    .firstOrNull { it.fileName?.endsWith(".gradle.kts") == true && it.lineNumber > 0 }
    ?.let { SourceSite(rootProject.name, buildFile.relativeTo(rootDir).invariantSeparatorsPath, it.lineNumber) }

internal fun kernelRef(task: Task): KernelRef {
    val type = generateSequence<Class<*>>(task.javaClass) { it.superclass }
        .firstOrNull { !it.name.endsWith("_Decorated") } ?: task.javaClass
    return kernelRef(task.project, type).copy(spec = (task as? dev.shibasis.dependeasy.process.KernelSpecOwner)?.kernelSpec?.relativeTo(task.project.rootDir))
}

private fun KernelSpec.relativeTo(root: java.io.File): KernelSpec {
    val owner = root.canonicalPath
    val workspace = root.canonicalFile.parentFile.path + java.io.File.separator
    fun path(value: String): String = if (value == owner) "." else value.replace(owner + java.io.File.separator, "").replace(workspace, "../")
    fun execution(value: ProcessExecution) = value.copy(directory = path(value.directory), executable = path(value.executable),
        arguments = value.arguments.map(::path), inputs = value.inputs.map(::path), outputs = value.outputs.map(::path),
        fileInputs = value.fileInputs.map { it.copy(identity = path(it.identity)) })
    return when (this) {
        is KernelSpec.XcodeHost -> copy(files = files.map(::path))
        is KernelSpec.ReleasePlan -> copy(output = path(output))
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
    val source = sourceOwner?.resolve("dependeasy/gradle/src/main/kotlin/" + type.name.replace('.', '/') + ".kt")
    val line = source?.takeIf { it.isFile }?.readLines()?.indexOfFirst {
        Regex("\\bclass\\s+" + Regex.escape(type.simpleName) + "\\b").containsMatchIn(it)
    }?.takeIf { it >= 0 }?.plus(1)
    return KernelRef(type.name, if (sourceOwner != null && source != null && line != null) SourceSite(sourceOwner.name,
        source.relativeTo(sourceOwner).invariantSeparatorsPath, line) else null)
}
