package dev.shibasis.dependeasy.benchmark

import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

internal class ProfileKernel(private val project: Project, private val declaration: Profile) {
    fun install() {
        val tools = ProfilerTools(project)
        val target = project.extensions.getByType<KotlinMultiplatformExtension>().targets.getByName("jvm") as KotlinJvmTarget
        val main = target.compilations.getByName("main")
        val output = project.layout.projectDirectory.dir(declaration.output)
        project.tasks.register<JavaExec>(declaration.name) {
            group = "benchmark"
            description = "Profile ${declaration.mainClass}"
            dependsOn("jvmMainClasses")
            javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                languageVersion.set(JavaLanguageVersion.of(ToolchainVersions.Java))
            })
            mainClass.set(declaration.mainClass)
            classpath = project.files(main.output.allOutputs, main.runtimeDependencyFiles)
            if (declaration.fixtures) {
                dependsOn("jvmTestClasses")
                val test = target.compilations.getByName("test")
                classpath += project.files(test.output.allOutputs, test.runtimeDependencyFiles)
            }
            if (declaration.api) classpath += project.files(tools.jar.map { if (it.isEmpty()) emptyList<String>() else listOf(it) })
            declaration.flamegraph?.let { file ->
                jvmArgumentProviders.add(CpuAgentArguments(tools.library, output.file(file).asFile.absolutePath))
            }
            declaration.variables.forEach { (name, path) -> environment(name, project.file(path).absolutePath) }
            outputs.dir(output)
            doFirst { output.asFile.mkdirs() }
        }
    }
}

internal class CpuAgentArguments(@get:Input val library: Provider<String>, @get:Input val output: String) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = library.get().takeIf(String::isNotEmpty)?.let {
        listOf("-agentpath:$it=start,event=cpu,flamegraph,file=$output", "-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints")
    }.orEmpty()
}
