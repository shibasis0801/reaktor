package dev.shibasis.dependeasy.process

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

/** Incremental external tool invocation. Backends supply inputs, outputs and tool identity. */
@DisableCachingByDefault(because = "External tools may embed host paths; backends must qualify relocatability first")
abstract class CommandTask : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Internal abstract val workingDirectory: DirectoryProperty
    @get:Input abstract val executable: Property<String>
    @get:Input abstract val arguments: ListProperty<String>
    @get:Input abstract val environment: MapProperty<String, String>
    @get:Input abstract val toolVersion: Property<String>
    @get:Input abstract val backend: Property<String>
    @get:InputFiles @get:IgnoreEmptyDirectories @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceFiles: ConfigurableFileCollection
    @get:OutputDirectories abstract val outputDirectories: ConfigurableFileCollection
    @get:OutputFiles abstract val outputFiles: ConfigurableFileCollection
    @get:Optional @get:OutputFile abstract val receiptFile: RegularFileProperty

    init {
        arguments.convention(emptyList())
        environment.convention(emptyMap())
        backend.convention("command")
    }

    protected open fun commandArguments(): List<String> = arguments.get()

    @TaskAction fun run() {
        val command = commandArguments()
        execOperations.exec {
            workingDir = this@CommandTask.workingDirectory.get().asFile
            executable = this@CommandTask.executable.get()
            args(command)
            environment(this@CommandTask.environment.get())
        }
        if (receiptFile.isPresent) {
            receiptFile.get().asFile.apply {
                parentFile.mkdirs()
                writeText("tool=${toolVersion.get()}\ncommand=${command.joinToString(" ")}\nresult=success\n")
            }
        }
    }
}
