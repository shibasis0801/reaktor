package dev.shibasis.reaktor.tooling

import dev.shibasis.dependeasy.model.BuildModel
import dev.shibasis.dependeasy.model.BuildModelReader
import java.io.File

data class GradleSourceRoots(val roots: List<File>, val unresolved: Boolean)

fun gradleSourceRoots(root: File): GradleSourceRoots {
    val model = readableBuildModel(root)
        ?: return GradleSourceRoots(emptyList(), listOf("settings.gradle.kts", "settings.gradle").any { root.resolve(it).isFile })
    return GradleSourceRoots(model.builds.map { root.resolve(it.directory).canonicalFile }
        .filter { it != root.canonicalFile }.distinct(), false)
}

internal fun readableBuildModel(root: File): BuildModel? = root.takeIf { it.resolve(BuildModelReader.RelativePath).isFile }
    ?.let { runCatching { BuildModelReader.readAllowingStale(it) }.getOrNull() }
