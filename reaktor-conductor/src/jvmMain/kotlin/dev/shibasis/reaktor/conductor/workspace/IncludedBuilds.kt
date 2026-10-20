package dev.shibasis.reaktor.conductor.workspace

import dev.shibasis.reaktor.tooling.gradleSourceRoots
import java.io.File
import java.nio.file.Path

internal fun includedBuildRoots(root: File): List<String> {
    val workspace = root.canonicalFile.toPath()
    val roots = gradleSourceRoots(root).roots.filter(File::isDirectory).map { it.canonicalFile.toPath() }.filterNot { it.startsWith(workspace) }
    return roots.filterNot { inner -> roots.any { outer -> outer != inner && inner.startsWith(outer) } }.map(Path::toString)
}
