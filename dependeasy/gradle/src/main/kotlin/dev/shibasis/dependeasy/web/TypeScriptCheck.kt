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
    override fun commandArguments() = listOf("exec", "node", adapter.get().asFile.absolutePath,
        if (emit.get()) "--project" else "--projects") + projects.get()
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.TypeScriptCheck(executionSpec(), projects.get())
}
