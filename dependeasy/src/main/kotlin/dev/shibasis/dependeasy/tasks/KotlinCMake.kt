package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.dag.BuildPipeline
import dev.shibasis.dependeasy.native.cmakeKernelSources
import dev.shibasis.dependeasy.native.cmakeSources
import dev.shibasis.dependeasy.native.nativeBuildDirectory
import dev.shibasis.dependeasy.native.nativeConfigurationOrNull
import dev.shibasis.dependeasy.native.nativeProjectDependencies
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

fun Project.kotlinCmake(platform: CmakePlatform): TaskProvider<KotlinCMakeTask>? {
    val taskName = "${platform.taskPrefix}CMake"
    if (taskName in tasks.names) return tasks.named<KotlinCMakeTask>(taskName)
    val native = nativeConfigurationOrNull?.takeIf { it.isEnabled } ?: return null
    val nativeDependencies = nativeProjectDependencies()
    val dependencyTasks = nativeDependencies.mapNotNull { dependency ->
        dependency.project.kotlinCmake(platform)?.let { dependency to it }
    }
    val prefabTasks = if (platform is CmakePlatform.Android) {
        native.android.resolvedPrefabs.map { it to registerPrefabTask(it) }
    } else emptyList()

    val buildTask = tasks.register<KotlinCMakeTask>(taskName) {
        group = "reaktor"
        sourceDirectory.set(native.resolvedCmakeLists.parentFile)
        sourceFiles.from(project.cmakeSources(native.resolvedSourceDirectory), project.cmakeKernelSources())
        buildDirectory.set(project.nativeBuildDirectory(platform.variant))
        generator.set(platform.generator)
        cmakeExecutable.set(platform.cmakeExecutable)
        toolVersion.set(platform.identity(project))
        buildTarget.set(native.resolvedLibraryName)
        configureArguments.addAll(platform.flags(project))
        configureArguments.add("-DCMAKE_CXX_STANDARD=${ToolchainVersions.CppStandard}")
        configureArguments.add("-DREAKTOR_NATIVE_PUBLIC_INCLUDE_DIRS=${native.resolvedIncludeDirs.joinToString(";")}")
        includeNativeDependencies(nativeDependencies, dependencyTasks, platform)
        includePrefabs(prefabTasks)
        includeToolSources(project, native.resolvedCmakeLists, nativeDependencies, platform)
    }
    val pipeline = BuildPipeline(this, "${platform.taskPrefix}Native")
    val inputs = prefabTasks.map { pipeline.node(it.second) } + dependencyTasks.map { (dependency, task) ->
        pipeline.node(task, "${dependency.project.path}:${task.name}")
    }
    pipeline.node(buildTask).after(*inputs.toTypedArray())
    pipeline.report()
    return buildTask
}
