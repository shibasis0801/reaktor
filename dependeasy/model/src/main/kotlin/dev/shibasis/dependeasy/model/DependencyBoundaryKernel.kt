package dev.shibasis.dependeasy.model

import java.io.File

object DependencyBoundaryKernel {
    fun verify(spec: KernelSpec.DependencyBoundary, coordinates: List<String>, result: File) {
        result.delete()
        val forbidden = coordinates.filter { coordinate ->
            val (group, module) = coordinate.split(':', limit = 3)
            spec.groupPrefixes.any(group::startsWith) || spec.groupFragments.any(group::contains) ||
                module.removeSuffix(spec.moduleSuffix) in spec.modules
        }
        check(forbidden.isEmpty()) { "${spec.reason}: ${forbidden.joinToString()}" }
        result.parentFile.mkdirs()
        result.writeText("verified\n")
    }
}
