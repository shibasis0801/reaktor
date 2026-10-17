package dev.shibasis.dependeasy.analysis

import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

internal class KotlinAnalysisArguments(
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val archive: Provider<RegularFile>,
    @get:Classpath val standardLibrary: FileCollection,
    @get:Classpath val commonMetadata: FileCollection,
) : CommandLineArgumentProvider {
    override fun asArguments() = listOf(
        "-Dreaktor.kotlin.archive=${archive.get().asFile.path}",
        "-Dreaktor.kotlin.stdlib=${standardLibrary.singleFile.path}",
        "-Dreaktor.kotlin.commonStdlib=${commonMetadata.singleFile.path}",
    )
}
