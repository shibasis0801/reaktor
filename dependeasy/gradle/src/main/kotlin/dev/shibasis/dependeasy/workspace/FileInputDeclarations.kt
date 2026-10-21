package dev.shibasis.dependeasy.workspace

import dev.shibasis.dependeasy.model.*
import org.gradle.api.Task
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.ConfigurableFileTree
import org.gradle.api.file.FileCollection
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskProvider
import java.io.File
import java.util.IdentityHashMap

internal fun ConfigurableFileCollection.declarations(): List<FileInput> {
    val visited = java.util.Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    fun describe(value: Any): List<FileInput> {
        if (!visited.add(value)) return emptyList()
        return when (value) {
            is File -> listOf(FileInput(FileInputKind.File, value.absolutePath))
            is ConfigurableFileTree -> listOf(FileInput(FileInputKind.Tree, value.dir.absolutePath,
                value.includes.sorted(), value.excludes.sorted()))
            is Configuration -> listOf(FileInput(FileInputKind.Configuration, value.name))
            is ConfigurableFileCollection -> value.from.flatMap(::describe)
            is TaskProvider<*> -> listOf(FileInput(FileInputKind.Producer, value.name))
            is Provider<*> -> listOf(FileInput(FileInputKind.Provider, "Deferred file provider"))
            is FileCollection -> listOf(FileInput(FileInputKind.Collection, value.toString()))
            is Iterable<*> -> value.filterNotNull().flatMap(::describe)
            is Array<*> -> value.filterNotNull().flatMap(::describe)
            else -> listOf(FileInput(FileInputKind.Collection, value.javaClass.name))
        }
    }
    return from.flatMap(::describe).distinct().sortedWith(compareBy({ it.kind.name }, { it.identity }, { it.includes.joinToString() }, { it.excludes.joinToString() }))
}

internal fun Task.declaredFileInputs(): List<FileSet> {
    val declarations = when (this) {
        is dev.shibasis.dependeasy.process.ProcessTask -> sourceFiles.declarations()
        is dev.shibasis.dependeasy.tasks.KotlinCMakeTask -> sourceFiles.declarations()
        is dev.shibasis.dependeasy.tasks.ArtifactSizeReport -> artifacts.declarations()
        is dev.shibasis.dependeasy.codegen.KotlinObjectTask -> workerFleet.declarations() +
            extensions.findByType(GeneratedObject.Spec::class.java)?.texts?.values.orEmpty()
                .map { FileInput(FileInputKind.File, project.rootProject.file(it.path).absolutePath) }
        else -> emptyList()
    }
    return if (declarations.isEmpty()) emptyList() else listOf(FileSet(emptyList(), declarations = declarations.map { declaration ->
        declaration.copy(identity = if (declaration.kind in listOf(FileInputKind.File, FileInputKind.Tree))
            File(declaration.identity).relativeTo(project.rootDir).invariantSeparatorsPath else declaration.identity)
    }))
}
