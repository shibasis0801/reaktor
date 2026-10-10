package dev.shibasis.dependeasy.tasks

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
        if (cmakeText.contains("flatbuffers", ignoreCase = true) || dependencies.any { "flexbuffer" in it.project.name }) add("flatbuffers")
        if (cmakeText.contains("hermes", ignoreCase = true)) add("hermes")
    }
    sourceNames.forEach { name ->
        project.rootProject.tasks.findByName("prepare${name.replaceFirstChar(Char::uppercaseChar)}Source")?.let { dependsOn(it) }
        sourceFiles.from(project.rootProject.fileTree(".github_modules/$name") {
            exclude(".git/**", "debug/**", "build/**")
        })
    }
    if (platform is CmakePlatform.Darwin && "hermes" in sourceNames) {
        dependsOn(project.rootProject.tasks.named("buildHermesHostCompiler"))
        configureArguments.add("-DHERMES_BUILD_DIR=${project.rootProject.layout.buildDirectory.dir("dependeasy/tools/hermes").get().asFile.absolutePath}")
    }
}
