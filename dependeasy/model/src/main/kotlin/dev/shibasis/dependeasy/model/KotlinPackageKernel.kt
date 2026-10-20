package dev.shibasis.dependeasy.model

import dev.shibasis.dependeasy.files.deleteTreeSafely
import java.io.File
import java.nio.file.Files

object KotlinPackageKernel {
    fun plan(spec: KernelSpec.KotlinPackage): List<String> {
        require(spec.destination.paths.size == 1 && spec.dependencies.paths.size == 1 && spec.receipt.paths.size == 1)
        return listOf("node_modules", "node_modules/**")
    }

    fun dependenciesMatch(spec: KernelSpec.KotlinPackage): Boolean {
        plan(spec)
        val source = File(spec.dependencies.paths.single()).toPath()
        val link = File(spec.destination.paths.single()).parentFile.resolve("node_modules").toPath()
        return Files.isSymbolicLink(link) && link.parent.resolve(Files.readSymbolicLink(link)).normalize() == source.normalize()
    }

    fun linkDependencies(spec: KernelSpec.KotlinPackage) {
        plan(spec)
        val source = File(spec.dependencies.paths.single())
        if (!source.isDirectory || dependenciesMatch(spec)) return
        val exported = File(spec.destination.paths.single()).parentFile
        val link = exported.resolve("node_modules").toPath()
        exported.mkdirs()
        if (Files.isSymbolicLink(link)) Files.delete(link)
        else if (Files.exists(link)) link.toFile().deleteTreeSafely(within = exported)
        Files.createSymbolicLink(link, link.parent.relativize(source.toPath()))
    }

    fun receipt(spec: KernelSpec.KotlinPackage): String {
        plan(spec)
        return "dependency=" + File(spec.dependencies.paths.single()).relativeTo(File(spec.destination.paths.single())).invariantSeparatorsPath + "\n"
    }
}
