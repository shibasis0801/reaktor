package dev.shibasis.reaktor.tooling

import dev.shibasis.dependeasy.model.BuildModelReader
import java.io.File

data class GradleSourceRoots(val roots: List<File>, val unresolved: Boolean)

fun gradleSourceRoots(root: File): GradleSourceRoots {
    if (!root.resolve(BuildModelReader.RelativePath).isFile) return GradleSourceRoots(emptyList(), listOf("settings.gradle.kts", "settings.gradle").any { root.resolve(it).isFile })
    val model = BuildModelReader.read(root)
    return GradleSourceRoots(model.builds.map { root.resolve(it.directory).canonicalFile }
        .filter { it != root.canonicalFile }.distinct(), false)
}
