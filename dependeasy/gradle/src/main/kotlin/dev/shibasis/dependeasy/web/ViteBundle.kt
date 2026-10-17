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
    override fun commandArguments() = listOf("exec", "vite", "build") +
        configuration.orNull?.let { listOf("--config", it) }.orEmpty()
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.ViteBundle(executionSpec(), configuration.orNull)
}
