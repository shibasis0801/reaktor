package dev.shibasis.dependeasy.codegen

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import dev.shibasis.dependeasy.configuration.option
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.process.ExecOperations
import org.gradle.work.DisableCachingByDefault
import javax.inject.Inject

@DisableCachingByDefault(because = "Pulumi generated SDK relocatability has not been qualified")
abstract class PulumiSdkTask : DefaultTask(), KernelSpecOwner {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val providerName: Property<String>
    @get:Input abstract val providerVersion: Property<String>
    @get:Input abstract val server: Property<String>
    @get:Input abstract val executable: Property<String>
    @get:Input abstract val toolVersion: Property<String>
    @get:Input abstract val processPath: Property<String>
    init { processPath.convention(project.option(BuildOption.environment("PATH", ""))) }
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Internal override val kernelSpec get() = KernelSpec.PulumiSdk(providerName.get(), providerVersion.get(), server.get(),
        executable.get(), ToolRef("pulumi", toolVersion.get()), FileSet(listOf(outputDirectory.get().asFile.absolutePath)))

    @TaskAction fun generate() {
        val spec = kernelSpec
        val plan = PulumiSdkKernel.plan(spec)
        PulumiSdkKernel.prepare(spec)
        execOperations.exec {
            executable = plan.executable
            args(plan.arguments)
            environment(PulumiSdkKernel.environment(spec, processPath.get()))
        }
        PulumiSdkKernel.resources(spec).forEach { (path, text) ->
            outputDirectory.get().asFile.resolve(path).apply { parentFile.mkdirs(); writeText(text) }
        }
    }
}
