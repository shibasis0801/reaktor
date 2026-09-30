package dev.shibasis.reaktor.tooling

import java.io.File

/** Literal composite-build roots visible in source. Dynamic settings require an evaluated model. */
data class GradleSourceRoots(val roots: List<File>, val unresolved: Boolean)

fun gradleSourceRoots(root: File): GradleSourceRoots {
    val settings = listOf("settings.gradle.kts", "settings.gradle").map { File(root, it) }.firstOrNull(File::isFile)
        ?: return GradleSourceRoots(emptyList(), false)
    val text = settings.readText()
    val calls = Regex("""includeBuild\s*\(""").findAll(text).count()
    val literal = Regex("""includeBuild\s*\(\s*["']([^"'$]+)["']""").findAll(text).toList()
    return GradleSourceRoots(literal.map { File(root, it.groupValues[1]).canonicalFile }.distinct(), literal.size != calls)
}
