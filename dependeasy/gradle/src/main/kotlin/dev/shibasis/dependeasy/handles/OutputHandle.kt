package dev.shibasis.dependeasy.handles

import dev.shibasis.dependeasy.model.*
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Attribute
import org.gradle.api.file.FileCollection

class OutputHandle internal constructor(internal val dependency: Any, internal val build: String,
    internal val projectPath: String, val kind: OutputKind, internal val qualifiers: List<String>) {
    internal val selector get() = (listOf(kind.name) + qualifiers).joinToString(":")
    internal val nodeId get() = NodeId(build, projectPath, "output" + (listOf(kind.name) + qualifiers).joinToString(""))
}

val OutputAttribute: Attribute<String> = Attribute.of("dependeasy.output", String::class.java)

fun Project.output(project: ProjectDependency, kind: OutputKind, vararg qualifiers: Enum<*>) =
    OutputHandle(project, rootProject.name, project.path, kind, qualifiers.map { it.name })
fun output(module: ModuleHandle, kind: OutputKind, vararg qualifiers: Enum<*>) =
    OutputHandle(module.coordinate, module.build.name, module.path, kind, qualifiers.map { it.name })
fun output(build: BuildHandle, role: Workspace) =
    OutputHandle(build.coordinate, build.name, ":", OutputKind.WorkspaceCompletion, listOf(role.name))
fun output(build: BuildHandle, kind: OutputKind) =
    OutputHandle(build.coordinate, build.name, ":", kind, emptyList())

fun Project.kotlinJsLibrary(module: ProjectDependency) = output(module, OutputKind.KotlinJsLibrary)
fun kotlinJsLibrary(module: ModuleHandle) = output(module, OutputKind.KotlinJsLibrary)
fun Project.webResources(module: ProjectDependency) = output(module, OutputKind.WebResources)
fun webResources(module: ModuleHandle) = output(module, OutputKind.WebResources)
fun Project.webPackage(module: ProjectDependency) = output(module, OutputKind.JavaScriptPackage)
fun webPackage(module: ModuleHandle) = output(module, OutputKind.JavaScriptPackage)
fun Project.verification(module: ProjectDependency, suite: Verification) = output(module, OutputKind.VerificationResults, suite)
fun verification(module: ModuleHandle, suite: Verification) = output(module, OutputKind.VerificationResults, suite)
fun verification(build: BuildHandle, suite: Verification) =
    OutputHandle(build.coordinate, build.name, ":", OutputKind.VerificationResults, listOf(suite.name))
fun Project.androidApk(module: ProjectDependency, build: Build) = output(module, OutputKind.AndroidApk, build)
fun androidLibrary(module: ModuleHandle, build: Build) = output(module, OutputKind.AndroidLibrary, build)
fun Project.androidBundle(module: ProjectDependency, build: Build) = output(module, OutputKind.AndroidBundle, build)
fun Project.appleFramework(module: ProjectDependency, target: Apple, build: Build) = output(module, OutputKind.AppleFramework, target, build)
fun Project.appleXcodeFramework(module: ProjectDependency) = output(module, OutputKind.AppleXcodeFramework)
fun appleXcodeFramework(module: ModuleHandle) = output(module, OutputKind.AppleXcodeFramework)
fun Project.jvmDistribution(module: ProjectDependency) = output(module, OutputKind.JvmDistribution)
fun Project.browserBundle(module: ProjectDependency) = output(module, OutputKind.BrowserBundle)
fun browserBundle(module: ModuleHandle) = output(module, OutputKind.BrowserBundle)
fun nativeLibrary(module: ModuleHandle, target: Apple) = output(module, OutputKind.NativeLibrary, target)
fun Project.moduleBuild(module: ProjectDependency) = output(module, OutputKind.ModuleBuild)

internal fun Project.resolve(output: OutputHandle): Configuration {
    val name = "dependeasy" + output.nodeId.toString().toByteArray(Charsets.UTF_8).joinToString("") { "%02x".format(it) }
    return configurations.findByName(name) ?: configurations.create(name) {
        isCanBeConsumed = false
        isCanBeResolved = true
        attributes.attribute(OutputAttribute, output.selector)
        val dependency = if (output.build == rootProject.name && output.dependency is String && output.projectPath != ":")
            this@resolve.dependencies.project(mapOf("path" to output.projectPath)) else this@resolve.dependencies.create(output.dependency)
        dependencies.add(dependency)
    }
}

fun Project.outputFiles(output: OutputHandle): FileCollection =
    if (output.build == rootProject.name && output.projectPath == path)
        files(provider { configurations.getByName(output.nodeId.name).outgoing.artifacts.files })
    else resolve(output)
