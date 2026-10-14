package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.KernelSpec
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "External tool outputs contain host paths")
abstract class WorkerTypes : ProcessTask() {
    @get:Input abstract val configuration: Property<String>
    @get:OutputFile abstract val declarations: RegularFileProperty
    override fun commandArguments() = listOf("exec", "wrangler", "types", declarations.get().asFile.relativeTo(workingDirectory.get().asFile).invariantSeparatorsPath,
        "--config", configuration.get())
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.WorkerTypes(executionSpec(), configuration.get())
}
