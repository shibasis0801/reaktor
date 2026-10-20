package dev.shibasis.dependeasy.benchmark

import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.handles.verification
import dev.shibasis.dependeasy.model.BuildModelReader
import dev.shibasis.dependeasy.model.Edge
import dev.shibasis.dependeasy.model.ToolHandle
import dev.shibasis.dependeasy.model.Verification
import dev.shibasis.dependeasy.native.CMakeBuild
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

internal class CppBenchmarkKernel(private val project: Project) {
    fun install(name: String, executable: String, property: String, headers: Map<ToolHandle, String>): dev.shibasis.dependeasy.handles.NodeHandle {
        val compiler = CMakeBuild(project, executable).apply {
            source = "src/commonBenchmark/cpp"
            target = executable
            headers.forEach { (tool, variable) -> sourceLibrary(tool, variable) }
        }.register(name)
        project.extensions.getByType(KotlinMultiplatformExtension::class.java).targets.withType(KotlinJvmTarget::class.java).configureEach {
            testRuns.matching { it.name == KotlinCompilation.TEST_COMPILATION_NAME }.configureEach {
                executionTask.configure {
                    dependsOn(compiler)
                    jvmArgumentProviders.add(CppReferenceArguments(property, compiler.flatMap { it.buildDirectory }.map { it.file(executable) }))
                }
            }
        }
        val workspace = BuildWorkspace.get(project)
        workspace.reference(project, compiler, null, family = "verification")
        val producer = project.node(compiler)
        val module = project.dependencies.project(mapOf("path" to project.path)) as org.gradle.api.artifacts.ProjectDependency
        val consumer = project.verification(module, Verification.Jvm)
        workspace.graph.configure {
            edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.Consumes(consumer.nodeId, producer.id, executable)))
            edges.add(BuildModelReader.json.encodeToString<Edge>(Edge.After(consumer.nodeId, producer.id)))
        }
        return producer
    }
}

private class CppReferenceArguments(
    @get:Input val property: String,
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val binary: Provider<RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf("-D$property=${binary.get().asFile.absolutePath}")
}
