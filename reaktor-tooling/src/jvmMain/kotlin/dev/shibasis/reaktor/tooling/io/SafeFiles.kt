package dev.shibasis.reaktor.tooling.io

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

@OptIn(ExperimentalPathApi::class)
fun File.deleteTreeSafely(within: File): Boolean {
    val boundary = within.toPath().toAbsolutePath().normalize()
    val target = toPath().toAbsolutePath().normalize()
    for (path in listOf(boundary, target)) {
        for (ancestor in generateSequence(path) { it.parent }) {
            val systemAlias = ancestor in setOf(Path.of("/var"), Path.of("/tmp")) &&
                runCatching { ancestor.toRealPath() == Path.of("/private").resolve(ancestor.fileName) }.getOrDefault(false)
            require(!Files.isSymbolicLink(ancestor) || systemAlias) {
                "Refusing to delete through symbolic link $ancestor"
            }
        }
    }
    val allowed = within.canonicalFile.toPath()
    val actual = canonicalFile.toPath()
    require(boundary.parent != null && actual != allowed && actual.startsWith(allowed)) {
        "Refusing to delete $target outside $boundary"
    }
    if (!Files.exists(target, NOFOLLOW_LINKS)) return true
    actual.deleteRecursively()
    return !Files.exists(actual, NOFOLLOW_LINKS)
}
