package dev.shibasis.dependeasy.settings

import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.model.KernelSpec
import dev.shibasis.dependeasy.model.ToolRef
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** Source preparation is a task, pinned to a commit; existing worktrees are never reset. */
@DisableCachingByDefault(because = "Git checkout metadata is host-specific")
abstract class GitSourceTask : DefaultTask(), KernelSpecOwner {
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.GitSource(repository.get(), revision.get(), checkout.get().asFile.absolutePath, ToolRef("git", toolVersion.get()))

    @get:Input abstract val executable: Property<String>
    @get:Input abstract val toolVersion: Property<String>
    @get:Input abstract val environment: org.gradle.api.provider.MapProperty<String, String>
    init {
        val tool = dev.shibasis.dependeasy.toolchain.NativeBuildTools.get(project).git
        dependsOn(tool.installation)
        executable.set(tool.executable.map { it.asFile.absolutePath })
        toolVersion.set(tool.handle.version)
        environment.set(tool.environment)
        inputs.files(tool.executable)
    }
    @get:Input abstract val repository: Property<String>
    @get:Input abstract val revision: Property<String>
    @get:OutputDirectory abstract val checkout: DirectoryProperty
    @get:Inject abstract val execOperations: ExecOperations

    @TaskAction fun prepare() = dev.shibasis.dependeasy.model.GitSourceKernel.prepare(
        kernelSpec as KernelSpec.GitSource, ::git)

    private fun git(arguments: List<String>): String {
        val output = ByteArrayOutputStream()
        execOperations.exec {
            executable = this@GitSourceTask.executable.get()
            environment(this@GitSourceTask.environment.get())
            args(arguments)
            standardOutput = output
        }
        return output.toString(Charsets.UTF_8)
    }
}
