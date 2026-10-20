package dev.shibasis.dependeasy.benchmark

import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.toolchain.ToolchainVersions
import kotlinx.serialization.encodeToString
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.provider.Property
import org.gradle.process.ExecOperations
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
abstract class ProfileTask : JavaExec(), KernelSpecOwner {
    @get:Input abstract val declaration: Property<String>
    @get:Inject abstract val process: ExecOperations
    @get:Internal override val kernelSpec get() = BuildModelReader.json.decodeFromString<KernelSpec.Profile>(declaration.get())
    @get:Internal override val kernelEffect get() = Effect.Verify
    @get:Internal override val kernelHost get() = kernelSpec.run.host

    @TaskAction override fun exec() {
        val spec = kernelSpec
        JvmProfileKernel.validate(spec)
        val version = ByteArrayOutputStream()
        process.exec {
            executable = javaLauncher.get().executablePath.asFile.absolutePath
            args(AsyncProfiler.versionArguments(spec.library.paths.single()))
            standardOutput = version
            errorOutput = ByteArrayOutputStream()
        }
        AsyncProfiler.requireVersion(spec.profiler, version.toString(Charsets.UTF_8))
        JvmProfileKernel.prepare(spec)
        environment(JvmProfileKernel.environment(spec))
        super.exec()
    }
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val entryContract: RegularFileProperty

    companion object {
        internal fun install(project: Project, name: String, declaration: Profile, run: Run): NodeHandle {
            val tools = ProfilerTools(project)
            val entry = project.compiledJvmEntry(name, CompilationRef.Main, declaration.sources)
            val output = project.file(declaration.output)
            val entryId = entry.producer.id
            val destination = output.absolutePath
            val task = project.tasks.register<ProfileTask>(name) {
                group = "benchmark"
                description = "Profile the compiled entry owned by $name"
                entryContract.set(entry.location)
                this.declaration.set(tools.library.zip(tools.jar) { library, api ->
                    BuildModelReader.json.encodeToString(KernelSpec.Profile(declaration.sources, entryId,
                        FileSet(listOf(destination)), declaration.outputEnvironment,
                        FileSet(listOf(library).filter(String::isNotEmpty)),
                        FileSet(listOf(api).filter(String::isNotEmpty)),
                        ToolRef("java", ToolchainVersions.Java.toString()), ToolRef("async-profiler", ToolchainVersions.AsyncProfiler), run))
                })
                mainClass.set(entry.entryClass)
                javaLauncher.set(project.extensions.getByType<JavaToolchainService>().launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(ToolchainVersions.Java))
                })
                classpath = project.jvmExecution(CompilationRef.Main.name).classpath
                if (declaration.outputEnvironment != null) {
                    classpath += project.jvmExecution(CompilationRef.Test.name).classpath
                    val jar = tools.jar
                    classpath += project.files(jar.map { if (it.isEmpty()) emptyList<String>() else listOf(it) })
                    outputs.dir(output)
                } else {
                    jvmArgumentProviders.add(CpuAgentArguments(tools.library, output.absolutePath, project.providers.provider { true }))
                    outputs.file(output)
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
        return AsyncProfiler.cpuArguments(library.get(), output)
    }
}
