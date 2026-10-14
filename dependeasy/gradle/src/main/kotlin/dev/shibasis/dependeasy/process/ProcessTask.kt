package dev.shibasis.dependeasy.process

import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.ProcessExecution
import dev.shibasis.dependeasy.model.ToolRef
import dev.shibasis.dependeasy.model.Effect
import dev.shibasis.dependeasy.model.HostPlatform
import dev.shibasis.dependeasy.model.HostRequirement
import dev.shibasis.dependeasy.workspace.declarations
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "External tool outputs contain host paths")
abstract class ProcessTask : DefaultTask(), KernelSpecOwner {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Internal abstract val workingDirectory: DirectoryProperty
    @get:Input val workingDirectoryIdentity: String get() = workingDirectory.get().asFile.canonicalPath
    @get:Input abstract val executable: Property<String>
    @get:Input abstract val arguments: ListProperty<String>
    @get:Input abstract val environment: MapProperty<String, String>
    @get:Input abstract val executionEffect: Property<Effect>
    @get:Input abstract val executionPlatform: Property<HostPlatform>
    @get:Internal override val kernelEffect: Effect get() = executionEffect.get()
    @get:Internal override val kernelHost: HostRequirement get() = HostRequirement(executionPlatform.get())
    @get:Input abstract val toolVersion: Property<String>
    @get:InputFiles @get:IgnoreEmptyDirectories @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:OutputDirectories abstract val outputDirectories: ConfigurableFileCollection
    @get:OutputFiles abstract val outputFiles: ConfigurableFileCollection
    @get:Optional @get:OutputFile abstract val receiptFile: RegularFileProperty

    init {
        executionEffect.convention(Effect.Build)
        executionPlatform.convention(HostPlatform.Portable)
        arguments.convention(emptyList())
        environment.convention(emptyMap())
    }

    protected open fun commandArguments(): List<String> = arguments.get()

    protected fun executionSpec(command: List<String> = commandArguments()) = ProcessExecution(
        workingDirectory.get().asFile.absolutePath, executable.get(), command,
        environment.get().keys.sorted(), ToolRef(java.io.File(executable.get()).name, toolVersion.get()),
        emptyList(),
        outputs.files.files
            .map { it.absolutePath }.sorted(),
        sourceFiles.declarations(),
    )

    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.Process(executionSpec())

    @TaskAction fun run() = CommandTask.run(execOperations, workingDirectory.get().asFile, executable.get(),
        commandArguments(), environment.get(), toolVersion.get(), receiptFile.orNull?.asFile)
}
