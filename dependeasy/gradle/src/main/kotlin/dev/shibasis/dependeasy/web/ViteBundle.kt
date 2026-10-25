package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.KernelSpec
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "External tool outputs contain host paths")
abstract class ViteBundle : ProcessTask() {
    @get:Input @get:Optional abstract val configuration: Property<String>
    @get:Input abstract val excludedAssets: ListProperty<String>

    init { excludedAssets.convention(emptyList()) }

    override fun executeKernel() {
        dev.shibasis.dependeasy.model.JavaScriptKernel.run(kernelSpec as KernelSpec.ViteBundle, receiptFile.orNull?.asFile) { execution ->
            execOperations.exec {
                workingDir = java.io.File(execution.directory)
                executable = execution.executable
                args(execution.arguments)
                environment(executionEnvironment())
            }.assertNormalExitValue()
        }
    }
    override fun commandArguments() = (kernelSpec as KernelSpec.ViteBundle).execution.arguments
    @get:Internal override val kernelSpec: KernelSpec get() {
        val spec = KernelSpec.ViteBundle(executionSpec(arguments.get()), configuration.orNull,
            outputDirectories.singleFile.relativeTo(workingDirectory.get().asFile).invariantSeparatorsPath, excludedAssets.get())
        return spec.copy(execution = dev.shibasis.dependeasy.model.JavaScriptKernel.plan(spec))
    }
}
