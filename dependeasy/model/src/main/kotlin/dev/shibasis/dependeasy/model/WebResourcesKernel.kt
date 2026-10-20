package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.deleteTreeSafely
import java.io.File
import java.nio.file.Files

object WebResourcesKernel {
    fun publish(spec: KernelSpec.WebResources) {
        val source = File(spec.sources.paths.single()).absoluteFile.normalize()
        val output = File(spec.output.paths.single()).absoluteFile.normalize()
        require(source.isDirectory) { "Missing web resources: $source" }
        val sourcePath = source.canonicalFile.toPath()
        val outputPath = output.canonicalFile.toPath()
        require(!sourcePath.startsWith(outputPath) && !outputPath.startsWith(sourcePath)) {
            "Published resources must be separate from their source"
        }
        val entries = source.walkTopDown().toList()
        require(entries.none { Files.isSymbolicLink(it.toPath()) }) { "Web resources cannot contain symbolic links" }
        output.deleteTreeSafely(within = output.parentFile)
        output.mkdirs()
        entries.forEach { entry ->
            val destination = output.resolve(entry.relativeTo(source))
            if (entry.isDirectory) destination.mkdirs() else entry.copyTo(destination)
        }
    }
}
