package dev.shibasis.dependeasy.workspace

import org.gradle.api.Describable
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import java.io.File
import dev.shibasis.dependeasy.model.BuildDeclarationInputs

abstract class BuildDeclarationFiles : ValueSource<List<File>, BuildDeclarationFiles.Parameters> {
    interface Parameters : ValueSourceParameters {
        val root: DirectoryProperty
    }
    override fun obtain(): List<File> = BuildDeclarationInputs.files(parameters.root.get().asFile)
}

internal abstract class BuildFilePresence : ValueSource<Boolean, BuildFilePresence.Parameters>, Describable {
    interface Parameters : ValueSourceParameters {
        val file: RegularFileProperty
    }
    override fun getDisplayName(): String = "optional build input ${parameters.file.get().asFile.name}"
    override fun obtain(): Boolean = parameters.file.get().asFile.isFile
}

internal fun Project.hasBuildFile(file: File): Boolean = providers.of(BuildFilePresence::class.java) {
    parameters.file.set(file)
}.get()
