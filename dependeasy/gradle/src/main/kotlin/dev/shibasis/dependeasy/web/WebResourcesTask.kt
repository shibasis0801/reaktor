package dev.shibasis.dependeasy.web

import dev.shibasis.dependeasy.model.*
import dev.shibasis.dependeasy.process.KernelSpecOwner
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.*

@CacheableTask
abstract class WebResourcesTask : DefaultTask(), KernelSpecOwner {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @get:Internal override val kernelSpec get() = KernelSpec.WebResources(
        FileSet(listOf(sourceDirectory.get().asFile.absolutePath)),
        FileSet(listOf(outputDirectory.get().asFile.absolutePath)))

    @TaskAction fun publish() = WebResourcesKernel.publish(kernelSpec)
}
