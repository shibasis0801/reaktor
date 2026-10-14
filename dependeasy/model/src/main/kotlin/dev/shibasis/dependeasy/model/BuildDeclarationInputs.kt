package dev.shibasis.dependeasy.model

import java.io.File
import java.nio.file.Files

object BuildDeclarationInputs {
    private val excluded = setOf("build", ".gradle", ".github_modules", "node_modules", ".git", ".kotlin",
        "tmp", "temp", ".tmp", ".claude", ".codex", ".agents", ".idea")
    fun files(root: File): List<File> {
        val boundary = root.canonicalFile.toPath()
        return root.walkTopDown().onEnter { directory ->
            directory == root || directory.name !in excluded && !Files.isSymbolicLink(directory.toPath())
        }.filter { file ->
            val path = file.relativeTo(root).invariantSeparatorsPath
            val implementation = path.startsWith("buildSrc/src/main/") || path.startsWith("dependeasy/") &&
                listOf("gradle", "model", "toolchain", "buildSrc").any { path.startsWith("dependeasy/$it/src/main/") }
            file.isFile && file.canonicalFile.toPath().startsWith(boundary) &&
                (file.name.endsWith(".gradle.kts") || file.name in setOf("gradle.properties", "local.properties") ||
                    implementation && file.extension == "kt")
        }.sortedBy { it.invariantSeparatorsPath }.toList()
    }
    fun membership(root: File): String = BuildModelReader.digest(files(root).joinToString("\n") {
        it.relativeTo(root).invariantSeparatorsPath
    })
}
