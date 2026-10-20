package dev.shibasis.dependeasy.benchmark

import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import dev.shibasis.dependeasy.handles.NodeHandle
import dev.shibasis.dependeasy.handles.node
import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.module.compiledJvmEntry
import dev.shibasis.dependeasy.module.entryClass
import dev.shibasis.dependeasy.module.jvmExecution
import dev.shibasis.dependeasy.workspace.BuildWorkspace
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.*
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "Profiles the declared workload on its execution host")
abstract class ProfileKernel : JavaExec() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val entryContract: RegularFileProperty

    companion object {
        internal fun install(project: Project, name: String, declaration: Profile, run: Run): NodeHandle {
            val tools = ProfilerTools(project)
            val entry = project.compiledJvmEntry(name, CompilationRef.Main, declaration.sources)
            val output = project.file(declaration.output)
            val task = project.tasks.register<ProfileKernel>(name) {
                group = "benchmark"
                description = "Profile the compiled entry owned by $name"
                entryContract.set(entry.location)
                mainClass.set(entry.entryClass)
                javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(ToolchainVersions.Java))
                })
                classpath = project.jvmExecution(CompilationRef.Main.name).classpath
                if (declaration.outputEnvironment != null) {
                    classpath += project.jvmExecution(CompilationRef.Test.name).classpath
                    val library = tools.library
                    val jar = tools.jar
                    classpath += project.files(jar.map { if (it.isEmpty()) emptyList<String>() else listOf(it) })
                    environment(declaration.outputEnvironment, output.absolutePath)
                    environment("ASPROF_LIB", library.get())
                    outputs.dir(output)
                    doFirst {
                        check(library.get().isNotEmpty()) { "async-profiler library unavailable; set ASPROF_LIB" }
                        check(jar.get().isNotEmpty()) { "async-profiler API unavailable; set ASPROF_JAR" }
                        output.mkdirs()
                    }
                } else {
                    jvmArgumentProviders.add(CpuAgentArguments(tools.library, output.absolutePath, project.providers.provider { true }))
                    outputs.file(output)
                    doFirst { output.parentFile.mkdirs() }
                }
                run.timeoutMillis?.let { timeout.set(java.time.Duration.ofMillis(it)) }
            }
            BuildWorkspace.get(project).reference(project, task, OutputKind.VerificationResults, run.host)
            return project.node(task, OutputKind.VerificationResults).consumes(entry)
        }
    }
}

internal class CpuAgentArguments(@get:Input val library: Provider<String>, @get:Input val output: String,
    @get:Input val enabled: Provider<Boolean>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        if (!enabled.get()) return emptyList()
        val path = library.get()
        check(path.isNotEmpty()) { "async-profiler library unavailable; set ASPROF_LIB" }
        return listOf("-agentpath:$path=start,event=cpu,flamegraph,file=$output", "-XX:+UnlockDiagnosticVMOptions", "-XX:+DebugNonSafepoints")
    }
}
