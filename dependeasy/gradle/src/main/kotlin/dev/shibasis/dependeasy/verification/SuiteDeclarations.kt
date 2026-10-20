package dev.shibasis.dependeasy.verification

import dev.shibasis.dependeasy.configuration.option
import dev.shibasis.dependeasy.handles.*
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.toolchain.VerificationTools
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

internal fun Project.karateSuite(name: String, directory: String, config: String, tags: List<String>, files: List<String>, optionalFiles: List<String> = emptyList(), environment: Map<String, String> = emptyMap()): NodeHandle {
    val tool = VerificationTools.get(this).karate
    val executionEnvironment = environment.mapValues { (key, value) -> option(BuildOption.environment(key, value)) }
    val task = tasks.register<KarateSuite>(name) {
        group = "verification"
        workingDirectory.set(rootProject.layout.projectDirectory)
        executable.set(java.io.File(option(BuildOption.systemProperty("java.home")).get(), "bin/java").absolutePath)
        toolVersion.set(tool.handle.version)
        jar.set(tool.executable); dependsOn(tool.installation)
        suites.set(listOf(directory)); this.tags.set(tags)
        executionEnvironment.forEach { (key, value) -> this.environment.put(key, value) }
        arguments.set(listOf("--configdir", config))
        environmentFiles.from(optionalFiles.map { path ->
            val file = rootProject.file(path)
            rootProject.fileTree(file.parentFile).matching { include(file.name) }
        }, files.map(rootProject::file))
        sourceFiles.from(rootProject.fileTree(directory), rootProject.fileTree(config).matching { include("*.js", "*.json") })
        outputDirectories.from(layout.buildDirectory.dir("reports/karate/$name"))
    }
    return node(task, OutputKind.VerificationResults).after(rootProject.node(tool.installation))
}

internal fun Project.k6Suite(name: String, script: String, files: List<String>, optionalFiles: List<String> = emptyList(), environment: Map<String, String> = emptyMap()): NodeHandle {
    val tool = VerificationTools.get(this).k6
    val executionEnvironment = environment.mapValues { (key, value) -> option(BuildOption.environment(key, value)) }
    val task = tasks.register<K6Suite>(name) {
        group = "verification"
        workingDirectory.set(rootProject.layout.projectDirectory)
        executable.set(tool.executable.map { it.asFile.absolutePath }); dependsOn(tool.installation)
        toolVersion.set(tool.handle.version); this.script.set(rootProject.layout.projectDirectory.file(script))
        executionEnvironment.forEach { (key, value) -> this.environment.put(key, value) }
        environmentFiles.from(optionalFiles.map { path ->
            val file = rootProject.file(path)
            rootProject.fileTree(file.parentFile).matching { include(file.name) }
        }, files.map(rootProject::file))
        sourceFiles.from(rootProject.file(script))
        outputFiles.from(layout.buildDirectory.file("reports/k6/$name.json"))
    }
    return node(task, OutputKind.VerificationResults).after(rootProject.node(tool.installation))
}
