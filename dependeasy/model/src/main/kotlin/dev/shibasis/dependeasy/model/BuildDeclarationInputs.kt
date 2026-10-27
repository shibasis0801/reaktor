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
            val name = file.name
            val declaration = name.endsWith(".gradle.kts") || name == "gradle.properties" || name == "local.properties" ||
                name == "pnpm-workspace.yaml" && file.parentFile == root
            val implementation = !declaration && name.endsWith(".kt") && run {
                val path = file.relativeTo(root).invariantSeparatorsPath
                path.startsWith("buildSrc/src/main/") || listOf("", "dependeasy/").any { prefix ->
                    listOf("gradle", "model", "toolchain", "buildSrc").any { path.startsWith("$prefix$it/src/main/") } ||
                        path.startsWith("${prefix}toolchain/src/contracts/")
                }
            }
            (declaration || implementation) && file.isFile && file.canonicalFile.toPath().startsWith(boundary)
        }.sortedBy { it.invariantSeparatorsPath }.toList()
    }
    fun membership(root: File): String = MessageDigest.getInstance("SHA-256").digest(files(root).joinToString("\n") {
        it.relativeTo(root).invariantSeparatorsPath
    }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
