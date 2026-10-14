package dev.shibasis.dependeasy.workspace

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File

abstract class BuildDeclarationFiles : ValueSource<List<File>, BuildDeclarationFiles.Parameters> {
    interface Parameters : ValueSourceParameters {
        val root: DirectoryProperty
    }
    override fun obtain(): List<File> {
        val root = parameters.root.get().asFile
        val excluded = setOf("build", ".gradle", ".github_modules", "node_modules", ".git", ".kotlin", "tmp", "temp", ".tmp")
        return root.walkTopDown().onEnter { directory ->
            directory == root || directory.name !in excluded && directory != root.resolve("dependeasy")
        }.filter { it.isFile && (it.name.endsWith(".gradle.kts") || it.name == "gradle.properties") }
            .sortedBy { it.invariantSeparatorsPath }.toList()
    }
}
