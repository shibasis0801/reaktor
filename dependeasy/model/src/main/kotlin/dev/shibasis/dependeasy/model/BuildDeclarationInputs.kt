package dev.shibasis.dependeasy.model

import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

object BuildDeclarationInputs {
    private val excluded = setOf("build", ".gradle", ".github_modules", "node_modules", ".git", ".kotlin",
        "tmp", "temp", ".tmp", ".claude", ".codex", ".agents", ".idea")
    fun files(root: File): List<File> {
        val boundary = root.canonicalFile.toPath()
        val result = mutableListOf<File>()
        Files.walkFileTree(boundary, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
                if (directory != boundary && directory.fileName.toString() in excluded) FileVisitResult.SKIP_SUBTREE
                else FileVisitResult.CONTINUE

            override fun visitFile(path: Path, attributes: BasicFileAttributes): FileVisitResult {
                val name = path.fileName.toString()
                if (!name.endsWith(".gradle.kts") && name != "gradle.properties" && name != "local.properties" &&
                    name != "pnpm-workspace.yaml" && !name.endsWith(".kt")) return FileVisitResult.CONTINUE
                val relative = boundary.relativize(path).toString().replace(File.separatorChar, '/')
                val declaration = name.endsWith(".gradle.kts") || name == "gradle.properties" || name == "local.properties" ||
                    name == "pnpm-workspace.yaml" && path.parent == boundary
                val implementation = !declaration && name.endsWith(".kt") && run {
                    relative.startsWith("buildSrc/src/main/") || listOf("", "dependeasy/").any { prefix ->
                        listOf("gradle", "model", "toolchain", "buildSrc").any { relative.startsWith("$prefix$it/src/main/") } ||
                            relative.startsWith("${prefix}toolchain/src/contracts/")
                    }
                }
                if (declaration || implementation) {
                    val file = root.resolve(relative)
                    if ((attributes.isRegularFile || attributes.isSymbolicLink && file.isFile) &&
                        file.canonicalFile.toPath().startsWith(boundary)) result += file
                }
                return FileVisitResult.CONTINUE
            }
        })
        return result.sortedBy { it.invariantSeparatorsPath }
    }
    fun membership(root: File): String = MessageDigest.getInstance("SHA-256").digest(files(root).joinToString("\n") {
        it.relativeTo(root).invariantSeparatorsPath
    }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
