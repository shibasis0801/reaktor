package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.KernelSpec
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "External tool outputs contain host paths")
abstract class TypeScriptCheck : ProcessTask() {
    @get:Input abstract val projects: ListProperty<String>
    init { projects.convention(emptyList()) }
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val adapter: RegularFileProperty
    @get:Input abstract val emit: Property<Boolean>
    init { emit.convention(false) }
    @get:Internal override val kernelEffect: dev.shibasis.dependeasy.model.Effect get() = if (emit.get()) dev.shibasis.dependeasy.model.Effect.Build else dev.shibasis.dependeasy.model.Effect.Verify
    override fun commandArguments() = (kernelSpec as KernelSpec.TypeScriptCheck).execution.arguments
    @get:Internal override val kernelSpec: KernelSpec get() {
        val spec = KernelSpec.TypeScriptCheck(executionSpec(arguments.get()), projects.get(), adapter.get().asFile.absolutePath, emit.get())
        return spec.copy(execution = dev.shibasis.dependeasy.model.JavaScriptKernel.plan(spec))
    }
}
