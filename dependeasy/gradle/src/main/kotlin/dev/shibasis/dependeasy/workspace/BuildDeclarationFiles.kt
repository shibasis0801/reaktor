package dev.shibasis.dependeasy.workspace

import org.gradle.api.Describable
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
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

internal abstract class BuildPathSelection : ValueSource<String, BuildPathSelection.Parameters>, Describable {
    interface Parameters : ValueSourceParameters {
        val candidates: ListProperty<String>
        val directory: Property<Boolean>
    }
    override fun getDisplayName(): String = "optional build " + if (parameters.directory.get()) "directory" else "file"
    override fun obtain(): String = parameters.candidates.get().asSequence().map(::File)
        .firstOrNull { if (parameters.directory.get()) it.isDirectory else it.isFile }?.absolutePath.orEmpty()
}

internal fun Project.selectBuildPath(candidates: Provider<List<String>>, directory: Boolean): Provider<String> =
    providers.of(BuildPathSelection::class.java) {
        parameters.candidates.set(candidates)
        parameters.directory.set(directory)
    }

internal fun Project.hasBuildFile(file: File): Boolean = selectBuildPath(providers.provider { listOf(file.path) }, false).get().isNotEmpty()
internal fun Project.hasBuildDirectory(file: File): Boolean = selectBuildPath(providers.provider { listOf(file.path) }, true).get().isNotEmpty()
