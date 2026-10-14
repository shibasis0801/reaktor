package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.model.NativeContract
import dev.shibasis.dependeasy.native.NativeDependency
import dev.shibasis.dependeasy.settings.nativeTools
import org.gradle.api.Project

internal fun KotlinCMakeTask.includeToolSources(project: Project, dependencies: List<NativeDependency>, platform: CmakePlatform) {
    dependencies.mapNotNull { it.source }.distinct().forEach { tool ->
        val source = project.nativeTools.sourceTasks.getValue(tool)
        dependsOn(source)
        sourceFiles.from(source.flatMap { it.checkout }.map { it.asFileTree.matching { exclude(".git/**", "debug/**", "build/**") } })
    }
    if(platform !is CmakePlatform.Android && dependencies.any { it.recipe == "hermes" }) {
        dependsOn(project.nativeTools.hermesCompiler)
        sourceFiles.from(project.nativeTools.hermesCompiler.flatMap { it.buildDirectory }.map { it.file("ImportHostCompilers.cmake") })
    }
}

internal fun Project.nativeSourceContracts(dependencies: List<NativeDependency>, platform: CmakePlatform, settings: Map<String, String>): List<NativeContract.Source> = dependencies.distinctBy { it.name }.map { dependency ->
    val directory = dependency.source?.let { nativeTools.sourceTasks.getValue(it).get().checkout.get().asFile.absolutePath }.orEmpty()
    val compiler = if(dependency.recipe == "hermes" && platform !is CmakePlatform.Android)
        nativeTools.hermesCompiler.get().buildDirectory.get().file("ImportHostCompilers.cmake").asFile.absolutePath else ""
    NativeContract.Source(dependency.name, dependency.repository,
        if(dependency.recipe == "foundation") settings.getValue("DEPE_SDK_VERSION") else dependency.revision,
        if(dependency.recipe == "foundation") settings.getValue("DEPE_SDK_METADATA_SHA256") else dependency.checksum,
        dependency.checksumAlgorithm, dependency.recipe, directory, compiler)
} + if(platform is CmakePlatform.Android) dependencies.filter { it.source != null && it.prefab != null }.map { dependency ->
    val prefab = dependency.prefab!!
    NativeContract.Source("${dependency.name}-prefab", "maven:${prefab.dependencyNotation}", prefab.dependencyNotation.substringAfterLast(':'), prefab.checksum, "sha256", "prefab")
} else emptyList()
