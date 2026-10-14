package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.process.ProcessTask
import dev.shibasis.dependeasy.model.KernelSpec
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "External tool outputs contain host paths")
abstract class NodeGenerator : ProcessTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE) abstract val script: RegularFileProperty
    override fun commandArguments() = listOf(script.get().asFile.absolutePath) + arguments.get()
    @get:Internal override val kernelSpec: KernelSpec get() = KernelSpec.NodeGenerator(executionSpec(), script.get().asFile.absolutePath)
}
