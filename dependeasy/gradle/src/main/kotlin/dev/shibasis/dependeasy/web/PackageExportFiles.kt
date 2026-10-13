package dev.shibasis.dependeasy.web

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Retire compiler files without entering, resolving or removing installed dependencies. */
internal fun prunePackageFiles(directory: File, expected: Set<File>) {
    val root = directory.toPath().toAbsolutePath().normalize()
    require(!Files.isSymbolicLink(root)) { "Kotlin package output cannot be a symbolic link: $root" }
    if (!Files.isDirectory(root)) return
    val retained = expected.map { it.toPath().toAbsolutePath().normalize() }.toSet()
    require(retained.all { it.startsWith(root) && it != root }) { "Package files must stay inside $root" }
    fun dependencies(path: Path) = path != root && root.relativize(path).first().toString() == "node_modules"
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun preVisitDirectory(path: Path, attributes: BasicFileAttributes): FileVisitResult =
            if (dependencies(path)) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE

        override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
            if (!dependencies(path) && path !in retained) Files.delete(path)
            return FileVisitResult.CONTINUE
        }

        override fun postVisitDirectory(path: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            if (path != root && path.toFile().list().orEmpty().isEmpty()) Files.delete(path)
            return FileVisitResult.CONTINUE
        }
    })
}
