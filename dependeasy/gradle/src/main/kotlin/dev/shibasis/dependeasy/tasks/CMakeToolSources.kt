package dev.shibasis.dependeasy.tasks

import dev.shibasis.dependeasy.handles.Tools
import dev.shibasis.dependeasy.handles.Reaktor
import dev.shibasis.dependeasy.settings.nativeTools
import dev.shibasis.dependeasy.native.NativeProjectDependency
import org.gradle.api.Project
import java.io.File

internal fun KotlinCMakeTask.includeToolSources(
    project: Project,
    cmakeLists: File,
    dependencies: List<NativeProjectDependency>,
    platform: CmakePlatform,
) {
    val cmakeText = cmakeLists.readText()
    val sourceNames = buildList {
        if (cmakeText.contains(Tools.Flatbuffers.name, ignoreCase = true) || dependencies.any { it.project.path == Reaktor.Flexbuffer.path }) add(Tools.Flatbuffers)
        if (cmakeText.contains(Tools.Hermes.name, ignoreCase = true)) add(Tools.Hermes)
    }
    sourceNames.forEach { tool ->
        val name = tool.name
        project.nativeTools.sourceTasks[tool]?.let { dependsOn(it) }
        sourceFiles.from(project.rootProject.fileTree(".github_modules/$name") {
            exclude(".git/**", "debug/**", "build/**")
        })
    }
    if (platform is CmakePlatform.Darwin && Tools.Hermes in sourceNames) {
        dependsOn(project.nativeTools.hermesCompiler)
        configureArguments.add("-DHERMES_BUILD_DIR=${project.rootProject.layout.buildDirectory.dir("dependeasy/tools/hermes").get().asFile.absolutePath}")
    }
}
