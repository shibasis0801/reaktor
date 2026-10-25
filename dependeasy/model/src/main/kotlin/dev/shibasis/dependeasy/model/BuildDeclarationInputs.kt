package dev.shibasis.dependeasy.model

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

object BuildDeclarationInputs {
    private val excluded = setOf("build", ".gradle", ".github_modules", "node_modules", ".git", ".kotlin",
        "tmp", "temp", ".tmp", ".claude", ".codex", ".agents", ".idea")
    fun files(root: File): List<File> {
        val boundary = root.canonicalFile.toPath()
        return root.walkTopDown().onEnter { directory ->
            directory == root || directory.name !in excluded && !Files.isSymbolicLink(directory.toPath())
        }.filter { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val implementation = path.startsWith("buildSrc/src/main/") || listOf("", "dependeasy/").any { prefix ->
                listOf("gradle", "model", "toolchain", "buildSrc").any { path.startsWith("$prefix$it/src/main/") } ||
                    path.startsWith("${prefix}toolchain/src/contracts/")
            }
            (file.name.endsWith(".gradle.kts") || file.name in setOf("gradle.properties", "local.properties") || path == "pnpm-workspace.yaml" ||
                implementation && file.extension == "kt") &&
                file.isFile && file.canonicalFile.toPath().startsWith(boundary)
        }.sortedBy { it.invariantSeparatorsPath }.toList()
    }
    fun membership(root: File): String = MessageDigest.getInstance("SHA-256").digest(files(root).joinToString("\n") {
        it.relativeTo(root).invariantSeparatorsPath
    }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
