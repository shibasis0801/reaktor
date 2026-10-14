package dev.shibasis.dependeasy.workspace

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import java.nio.file.Files

abstract class BuildDeclarationFiles : ValueSource<List<File>, BuildDeclarationFiles.Parameters> {
    interface Parameters : ValueSourceParameters {
        val root: DirectoryProperty
    }
    override fun obtain(): List<File> {
        val root = parameters.root.get().asFile
        val excluded = setOf("build", ".gradle", ".github_modules", "node_modules", ".git", ".kotlin", "tmp", "temp", ".tmp", ".claude", ".codex", ".agents", ".idea")
        return root.walkTopDown().onEnter { directory ->
            directory == root || directory.name !in excluded && directory != root.resolve("dependeasy") && !Files.isSymbolicLink(directory.toPath())
        }.filter { it.isFile && (it.name.endsWith(".gradle.kts") || it.name == "gradle.properties") && it.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) }
            .sortedBy { it.invariantSeparatorsPath }.toList()
    }
}
