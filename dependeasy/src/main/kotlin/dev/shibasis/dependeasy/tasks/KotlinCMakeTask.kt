package dev.shibasis.dependeasy.tasks

import org.gradle.work.DisableCachingByDefault
import dev.shibasis.dependeasy.files.deleteTreeSafely
import dev.shibasis.dependeasy.native.CMakeLibraries

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

@DisableCachingByDefault(because = "CMake build trees contain absolute host paths; use incremental local builds")
abstract class KotlinCMakeTask : DefaultTask() {
    @get:Inject
    abstract val providers: org.gradle.api.provider.ProviderFactory

    @get:Inject
    abstract val execOperations: ExecOperations

    @get:Internal
    abstract val sourceDirectory: DirectoryProperty

    @get:Input
    val sourceIdentity: String get() = sourceDirectory.get().asFile.canonicalPath

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val buildDirectory: DirectoryProperty

    @get:Internal
    abstract val staticLibrariesManifest: RegularFileProperty

    @get:Input
    abstract val cmakeExecutable: Property<String>

    @get:Input
    abstract val generator: Property<String>

    @get:Input
    abstract val configureArguments: ListProperty<String>

    @get:Input
    abstract val buildTarget: Property<String>

    @get:Input
    abstract val configuration: Property<String>

    @get:Input
    abstract val parallelism: Property<Int>

    @get:Input
    abstract val environment: MapProperty<String, String>

    @get:Input
    abstract val toolVersion: Property<String>

    init {
        staticLibrariesManifest.convention(buildDirectory.file(CMakeLibraries.MANIFEST))
        environment.convention(emptyMap())
        configuration.convention("Release")
        configureArguments.convention(emptyList())
        parallelism.convention(providers.environmentVariable("CMAKE_BUILD_PARALLEL_LEVEL").map { it.toInt() }.orElse(3))
        toolVersion.convention(cmakeExecutable.flatMap { executable ->
            providers.exec { commandLine(executable, "--version") }.standardOutput.asText
        }.map { it.lineSequence().first() })
    }

    @TaskAction
    fun build() {
        val sourceDir = sourceDirectory.get().asFile
        val buildDir = buildDirectory.get().asFile
        val configuration = listOf(sourceIdentity, generator.get(), cmakeExecutable.get(), toolVersion.get(), environment.get().toSortedMap().toString()) + configureArguments.get()
        val configured = buildDir.resolve("configure-arguments.txt")
        if (!configured.isFile || configured.readLines() != configuration) {
            buildDir.resolve("CMakeCache.txt").delete()
            buildDir.resolve("CMakeFiles").deleteTreeSafely(within = buildDir)
        }
        buildDir.mkdirs()
        CMakeLibraries.request(buildDir)

        execOperations.exec {
            workingDir = sourceDir
            executable = cmakeExecutable.get()
            environment(this@KotlinCMakeTask.environment.get())
            args(
                "-S", sourceDir.absolutePath,
                "-B", buildDir.absolutePath,
                "-G", generator.get(),
            )
            args(configureArguments.get())
        }
        configured.writeText(configuration.joinToString("\n"))

        execOperations.exec {
            workingDir = sourceDir
            executable = cmakeExecutable.get()
            environment(this@KotlinCMakeTask.environment.get())
            args(
                "--build", buildDir.absolutePath,
                "--config", this@KotlinCMakeTask.configuration.get(),
                "--parallel", parallelism.get().toString(),
                "--target", buildTarget.get(),
            )
        }
        CMakeLibraries.record(buildDir, buildTarget.get(), this.configuration.get())
    }
}
