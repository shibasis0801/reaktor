package dev.shibasis.dependeasy.workspace

import org.gradle.api.file.DirectoryProperty
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
